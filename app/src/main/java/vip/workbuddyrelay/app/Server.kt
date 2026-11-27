package vip.workbuddyrelay.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 本机 OpenAI 兼容 HTTP 服务。
 * 只做协议需要的最小实现：HTTP/1.1、Content-Length 请求体、JSON 与 chunked SSE 响应。
 */
class RelayServer {
    @Volatile private var server: ServerSocket? = null
    @Volatile var running = false
        private set
    @Volatile var lastError = ""
        private set
    @Volatile var listenHost = "127.0.0.1"
        private set

    private var acceptThread: Thread? = null
    private val pool = java.util.concurrent.ThreadPoolExecutor(4, 8, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(32))
    private val gate = Semaphore(4)
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    private data class Req(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: String
    )

    fun start(cfg: Config): Boolean {
        if (running) return true
        return try {
            val addr = InetAddress.getByName(if (cfg.allowLan) "0.0.0.0" else "127.0.0.1")
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(addr, cfg.port), 50)
            server = s
            listenHost = if (cfg.allowLan) "0.0.0.0" else "127.0.0.1"
            running = true
            lastError = ""
            acceptThread = Thread({ acceptLoop(s) }, "relay-accept").apply { isDaemon = true; start() }
            Logs.add("服务已启动：http://$listenHost:${cfg.port}/v1")
            true
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            Logs.add("服务启动失败：$lastError")
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        pool.shutdownNow()
        acceptThread?.interrupt()
        acceptThread = null
        Logs.add("服务已停止")
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try {
                s.accept()
            } catch (e: Throwable) {
                if (running) Logs.add("accept 失败：${e.message}")
                break
            }
            clients.add(client)
            try { pool.execute { handle(client) } } catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
        }
    }

    private fun readLine(input: InputStream, limit: Int = 8192): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > limit) return null
        }
    }

    private fun readChunkedBytes(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.trim().substringBefore(';').toIntOrNull(16) ?: throw RelayError(400,"无效分块大小")
            if (size < 0 || size.toLong() + out.size() > 8 * 1024 * 1024) throw RelayError(413,"请求体过大")
            if (size <= 0) {
                readLine(input)
                break
            }
            var remaining = size
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n < 0) return out.toByteArray()
                out.write(buf, 0, n)
                remaining -= n
            }
            readLine(input)
            if (out.size() > 32 * 1024 * 1024) break
        }
        return out.toByteArray()
    }

    private fun readRequest(client: Socket, out: OutputStream): Req? {
        val input = BufferedInputStream(client.getInputStream())
        val line = readLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val q = target.indexOf('?')
        val path = (if (q >= 0) target.substring(0, q) else target).ifEmpty { "/" }
        val query = if (q >= 0) target.substring(q + 1) else ""
        val headers = LinkedHashMap<String, String>()
        var headerCount = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (++headerCount > 100) throw RelayError(400,"请求头过多")
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        if ((headers["expect"] ?: "").contains("100-continue", true)) {
            try {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (_: Throwable) {
            }
        }
        var bytes = ByteArray(0)
        if ((headers["transfer-encoding"] ?: "").contains("chunked", true)) {
            bytes = readChunkedBytes(input)
        } else {
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len < 0 || len > 8 * 1024 * 1024) throw RelayError(413,"请求体过大")
            if (len > 0) {
                val buf = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(buf, read, len - read)
                    if (n < 0) break
                    read += n
                }
                bytes = buf.copyOf(read)
            }
        }
        if ((headers["content-encoding"] ?: "").contains("gzip", true) && bytes.isNotEmpty()) {
            bytes = try {
                java.util.zip.GZIPInputStream(bytes.inputStream()).use { gz -> val decoded = gz.readNBytesCompat(8 * 1024 * 1024 + 1); if (decoded.size > 8 * 1024 * 1024) throw RelayError(413,"解压后请求体过大"); decoded }
            } catch (e: Throwable) {
                throw RelayError(400,"请求 gzip 无效或解压后过大")
            }
        }
        val body = String(bytes, Charsets.UTF_8)
        return Req(method, path, query, headers, body)
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30000
            val out = client.getOutputStream()
            val req = readRequest(client, out) ?: run { client.close(); return }
            val cfg = Store.config
            val started = System.currentTimeMillis()
            route(req, out, cfg)
            val ms = System.currentTimeMillis() - started
            if (req.path != "/health") {
                Logs.add(
                    "${req.method} ${req.path} body=${req.body.length}B " +
                        "te=${req.headers["transfer-encoding"] ?: "-"} " +
                        "ce=${req.headers["content-encoding"] ?: "-"} ${ms}ms"
                )
            }
            out.flush()
        } catch (e: Throwable) {
            Logs.add("请求处理异常：${safeMessage(e)}")
        } finally {
            clients.remove(client)
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun jsonHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json; charset=utf-8",
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-store",
        "Connection" to "close"
    )

    private fun respond(out: OutputStream, code: Int, body: String, contentType: String = "application/json; charset=utf-8", extra: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Cache-Control: no-store\r\n")
        extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun route(req:Req,out:OutputStream,cfg:Config) {
        if(req.method=="GET"&&(req.path=="/health"||req.path=="/healthz")){respond(out,200,JSONObject().put("status","ok").put("authenticated",Store.account!=null).toString());return}
        if(req.method=="OPTIONS"){respond(out,204,"","text/plain",mapOf("Access-Control-Allow-Methods" to "GET,POST,OPTIONS","Access-Control-Allow-Headers" to "Authorization,Content-Type,X-API-Key"));return}
        val key=req.headers["authorization"].orEmpty().let{if(it.startsWith("Bearer ",true))it.substring(7).trim()else ""}.ifBlank{req.headers["x-api-key"].orEmpty()}
        if(!java.security.MessageDigest.isEqual(key.toByteArray(),cfg.apiKey.toByteArray())){respond(out,401,OpenAI.error("API Key 无效","invalid_api_key").toString());return}
        try{when {
            req.method=="GET"&&req.path=="/v1/models"->{if(Store.modelsFor().length()==0||System.currentTimeMillis()/1000-Store.modelsAt>300)Upstream.models();respond(out,200,JSONObject().put("object","list").put("data",Store.modelsFor()).toString())}
            req.method=="GET"&&(req.path=="/status"||req.path=="/v1/status")->respond(out,200,JSONObject().put("running",running).put("logged_in",Store.account!=null).put("models",Store.modelsFor().length()).put("models_updated_at",Store.modelsAt).put("active_account",Store.active?.let{JSONObject().put("uid",it.uid).put("nickname",it.nickname).put("region",if(it.global)"global" else "cn")}?:JSONObject.NULL).put("account_count",Store.accounts.size).toString())
            req.method=="POST"&&req.path=="/v1/chat/completions"->chat(req,out)
            else->respond(out,404,OpenAI.error("未知路径","not_found").toString())
        }}catch(e:Throwable){respond(out,(e as? RelayError)?.status?:if(e is org.json.JSONException)400 else 502,OpenAI.error(safeMessage(e),(e as? RelayError)?.code?:"upstream_error").toString())}
    }
    private fun chat(req:Req,out:OutputStream){
        if(!gate.tryAcquire()){respond(out,429,OpenAI.error("并发请求过多","rate_limit").toString());return}
        var c:java.net.HttpURLConnection?=null;var streaming=false
        try{
            val src=try{JSONObject(req.body)}catch(_:Exception){throw RelayError(400,"请求体必须是 JSON 对象","invalid_request_error")}
            val stream=src.optBoolean("stream",false);val body=OpenAI.prepare(src)
            c=Upstream.chat(body)
            val aggregate=OpenAI.Aggregate();var sawFrame=false;var finished=false
            if(stream){out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\nX-Accel-Buffering: no\r\n\r\n".toByteArray());out.flush();streaming=true}
            fun emit(s:String){out.write("data: $s\n\n".toByteArray(Charsets.UTF_8));out.flush()}
            c.inputStream.bufferedReader(Charsets.UTF_8).use{reader->OpenAI.events(reader){payload->
                if(payload=="[DONE]"){false}else{
                    val chunk=OpenAI.normalize(try{JSONObject(payload)}catch(_:Exception){throw RelayError(502,"上游 SSE JSON 无效")});sawFrame=true
                    val choices=chunk.getJSONArray("choices");for(i in 0 until choices.length())if(choices.getJSONObject(i).str("finish_reason").isNotBlank())finished=true
                    if(stream)emit(chunk.toString())else aggregate.add(chunk)
                    true
                }
            }}
            if(!sawFrame||!finished)throw RelayError(502,"上游 SSE 提前结束，未收到结束原因","incomplete_stream")
            if(stream)emit("[DONE]")else respond(out,200,aggregate.result().toString())
        }catch(e:Throwable){
            if(streaming){out.write(("data: "+OpenAI.error(safeMessage(e),(e as? RelayError)?.code?:"upstream_error")+"\n\ndata: [DONE]\n\n").toByteArray());out.flush()}
            else throw e
        }finally{c?.disconnect();gate.release()}
    }
    fun refreshModels(){try{Upstream.models();Logs.add("模型列表已更新：${Store.modelsFor().length()} 个")}catch(e:Throwable){Store.modelsError=safeMessage(e);Logs.add(Store.modelsError)}}
}
private fun InputStream.readNBytesCompat(max:Int):ByteArray {val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(out.size()<max){val n=read(buffer,0,minOf(buffer.size,max-out.size()));if(n<0)break;out.write(buffer,0,n)};return out.toByteArray()}
