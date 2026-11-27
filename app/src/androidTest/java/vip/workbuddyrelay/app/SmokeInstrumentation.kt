package vip.workbuddyrelay.app

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Runs without opening any Activity or manipulating GUI. Never emits credentials. */
class SmokeInstrumentation:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){val results=Bundle();var server:RelayServer?=null
        try{
            Store.init(targetContext)
            check(Store.account==null){"Smoke suite requires signed-out installation"}
            val test="synthetic-keystore-roundtrip";val encrypted=Crypto.aesEncrypt(test);check(!encrypted.contains(test));check(Crypto.aesDecrypt(encrypted)==test)
            Store.config.keepAliveAudio=true;Store.saveConfig();val file=java.io.File(targetContext.filesDir,"relay/config.enc");check(!file.readText().contains(Store.config.apiKey));check(JSONObject(Crypto.aesDecrypt(file.readText())).getString("apiKey")==Store.config.apiKey)
            server=RelayServer();check(server.start(Config(port=18318,apiKey=Store.config.apiKey)))
            fun request(path:String,key:Boolean=false,body:String?=null):Pair<Int,JSONObject>{val c=URL("http://127.0.0.1:18318$path").openConnection() as HttpURLConnection;c.connectTimeout=5000;c.readTimeout=5000;if(key)c.setRequestProperty("Authorization","Bearer "+Store.config.apiKey);if(body!=null){c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toByteArray())}};try{val status=c.responseCode;val raw=(if(status<400)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};return status to JSONObject(raw)}finally{c.disconnect()}}
            check(request("/health").first==200)
            check(request("/v1/models").first==401)
            check(request("/v1/status",true).second.getBoolean("logged_in")==false)
            check(request("/v1/models",true).let{it.first==401&&it.second.getJSONObject("error").getString("code")=="login_required"})
            check(request("/v1/chat/completions",true,"{}").first==400)
            check(request("/v1/chat/completions",true,"{\"model\":\"test\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").let{it.first==401&&it.second.getJSONObject("error").getString("code")=="login_required"})
            check(request("/v1/responses",true).first==404)
            val pending=Login.start(false);check(pending.str("authUrl").startsWith("https://"));check(!Login.poll())
            results.putString("stream","PASS: AndroidKeyStore AES-GCM, encrypted API key persistence, HTTP health/auth/status/validation/login-required/routes plus live device OAuth state/pending poll (11 checks)")
            finish(Activity.RESULT_OK,results)
        }catch(e:Throwable){results.putString("stream","FAIL: "+e.javaClass.simpleName);finish(Activity.RESULT_CANCELED,results)}finally{server?.stop()}
    }
}
