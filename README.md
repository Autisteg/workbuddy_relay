# WorkBuddy Relay Android 0.3.1

> 本项目由 AI 辅助（vibe coding）完成：需求由用户提出，代码、协议核对、构建与验证由 AI 智能体在其手机上直接完成。请把它当作实验性工具，而非生产级软件。

把 WorkBuddy 的账号能力封装为本地 OpenAI 兼容接口的 Android 原生反向代理，使用 Kotlin 编写，包名 `vip.workbuddyrelay.app`，最低 Android 8.0（API 26），与 Trae Relay 相互独立。默认基础地址：`http://127.0.0.1:8318/v1`。

## 使用方式

1. 打开应用，创建设备授权链接，在浏览器中打开并完成你自己的 WorkBuddy 授权。返回应用后每 3 秒轮询一次，最长 10 分钟。已创建的待授权链接在进程重启后仍然保留，可点「继续检查授权」。国内版与国际版**可以同时各登录一个账号**，互不覆盖。每个账号登录时选择的区域会被显式记录，之后切换账号不会因域名缺失而被误判成国内版。「管理账号」可查看全部账号、切换当前账号或单独删除；顶部的国际版开关也会**直接切换当前账号**（该区域未登录时会提示先授权）。

   模型目录统一走 `GET /v3/config`（国内域与国际域均可用），旧的 `/console/enterprises/personal/models` 在国际域直接返回 500。该接口按渠道返回不同文档，因此使用 CLI 渠道的 User-Agent（`CLI/2.63.2 CodeBuddy/2.63.2`）读取 `cli` 模型名单；倍率直接取上游 `credits` 字段（如 `x0.79`），上游没给就留空，不做估算。也可以直接导入你自己的 WorkBuddy 凭据 JSON，支持嵌套 `{auth:{...},account:{...}}` 与扁平两种格式，可加 `"global": true` 指定国际版。令牌与账号信息不会出现在日志里。
2. 启动服务，把应用内生成的本地 API Key 复制到你的 OpenAI 兼容客户端。基础地址填上面的地址，模型从 `/v1/models` 或应用内动态模型列表中选取。
3. 支持流式与非流式 Chat Completions，以及 OpenAI 工具调用。工具由调用方执行：本应用只转发工具定义、模型返回的工具调用与工具结果，自身不执行任何工具。
4. 登录后可点击「立即签到领积分」执行每日签到，并点击「刷新积分」查询当前可用积分。重复签到会显示「今日已签到」，不会把幂等结果当成失败。
5. 若某个账号的上游目录确实没有返回模型，可用「手动添加模型」逐行填写模型 ID，它会进入 `/v1/models` 并带 `manual: true` 标记。
6. 「查看模型」会显示模型名称、上下文、最大输出和上游返回的倍率字段；如果上游没有提供倍率，会明确显示「倍率：上游未提供」，不会自行估算。

## 网络与保活

默认只绑定回环地址；需要局域网访问时必须在设置里显式开启，且仍然要求 API Key。设置项在重启服务后生效。

可选的静音音频保活默认开启：这台手机的 ColorOS 会立刻冻结仅持有 WakeLock 的普通前台服务，开启该机制后 HTTP 才真正可达。开关在设置中可见、可关闭。系统级后台限制仍可能需要额外允许后台活动，通知权限由界面主动申请。

## 安全

凭据、待授权 OAuth 状态与链接、API Key 与配置、模型缓存均使用 AndroidKeyStore AES-256-GCM 加密，以 AtomicFile 写入应用私有目录。已禁用备份，界面启用了 FLAG_SECURE。对话内容与上游原始错误不做持久化。API Key 只能通过显式的「复制 API Key」动作取出。release APK 不可调试。`ci/release.keystore` 是本项目专用签名密钥，与 Trae 应用不同，升级时需保留。

## 构建

使用配套的 aarch64 Android SDK 与 qemu aapt2 wrapper：

```sh
JAVA_HOME=/opt/jdk ANDROID_HOME=/workspace/android-sdk GRADLE_USER_HOME=/workspace/gradle-home \
  /workspace/tools/gradle-8.7/bin/gradle --no-daemon testReleaseUnitTest assembleRelease \
  -Pandroid.aapt2FromMavenOverride=/workspace/android-sdk/build-tools/34.0.0/aapt2
```

产物：`app/build/outputs/apk/release/app-release.apk`。仓库也带有 GitHub Actions 工作流 `.github/workflows/build-apk.yml`，推送后会在云端构建并上传 APK。预编译版本见 [Releases](https://github.com/Autisteg/workbuddy_relay/releases)。

## 验证状态

已通过：9 项 JVM 协议测试；11 项设备侧 instrumentation 检查（Keystore 加密往返与加密持久化、真实本机 HTTP 路由 / 鉴权 / 参数校验 / 未登录行为、真实 CN OAuth state 创建与授权前轮询）。release 安装后其前台服务在 8318 端口实测响应：`/health` 返回 200 且 `authenticated=false`，未带 Key 请求 `/v1/models` 返回 401。全程未使用 GUI 自动化。

未验证：尚无真实账号完成授权，因此已授权状态下的动态模型获取、令牌刷新、真实上游聊天、工具调用、签到与积分接口仍待用户授权后实测；Global 区域登录流程仅按参考实现完成区域路由，未做线上验证。倍率字段按上游实际返回兼容，当前接口未返回时界面会明确标注未提供。界面已编译，但未人工操作验证。本版本为单账号，不含账号池、后台定时签到与 Responses API。

## 协议参考

- [240xu/workbuddy2api](https://github.com/240xu/workbuddy2api) at `e4d5d3a`
- 官方 npm 包 `@tencent-ai/codebuddy-code` 2.187.0（补齐参考仓库缺失的 OAuth token / 账号轮询细节）

细节见 [PROTOCOL.md](PROTOCOL.md) 与 [TESTING.md](TESTING.md)。

## 免责声明

本项目仅供学习与技术研究，需自行遵守 WorkBuddy / CodeBuddy 的服务条款并承担使用风险。
