# 服务生命周期与安全隐私保护模型 (Service Lifecycle & Security)

在移动操作系统（Android）上运行一个对外暴露网络端口的 HTTP/MCP 服务的核心挑战在于：**如何确保系统资源的零多余占用、前台保活合规性以及绝对的数据隐私与访问控制**。

本文档深入剖析 `AiControlService` 的运行机制、安全拦截模型与敏感数据防护规则。

---

## 1. `AiControlService` 运行架构与生命周期

`AiControlService` 继承自 Android 原生 `android.app.Service`，采用 **前台服务 (Foreground Service)** 运行模式，确保在锁屏挂机和后台执行时不被系统 LMK (Low Memory Killer) 误杀。

### 1.1 生命周期控制流

```mermaid
sequenceDiagram
    autonumber
    actor User as 用户界面 (Settings / ViewModel)
    participant Svc as AiControlService (Foreground Service)
    participant Ktor as Ktor CIO Engine (EmbeddedServer)
    participant MCP as MaaMcpServerFactory (MCP Protocol)

    User->>Svc: startService(ACTION_START)
    activate Svc
    Svc->>Svc: ensureNotificationChannel()
    Svc->>Svc: startForeground(NOTIFICATION_ID, notification)
    
    Svc->>Svc: 读取 AppSettings (aiControlEnabled, port, token)
    alt aiControlEnabled == false
        Svc->>Svc: stopSelf() (自退保护，绝不空转)
        deactivate Svc
    else 正常启用
        Svc->>Ktor: embeddedServer(CIO, port=18080, host=127.0.0.1)
        activate Ktor
        Ktor->>MCP: 挂载 /mcp (Streamable HTTP) 与 /sse 路由
        MCP-->>Ktor: 注册 20 项工具与鉴权拦截器
        Ktor-->>Svc: 端口监听建立成功
        Svc->>Svc: 发布通知栏状态: "AI 控制服务运行中 (127.0.0.1:18080)"
    end

    User->>Svc: startService(ACTION_STOP) / 用户关闭开关
    Svc->>Ktor: httpServer.stop(gracePeriod=1000, timeout=2000)
    deactivate Ktor
    Svc->>Svc: stopForeground(STOP_FOREGROUND_REMOVE)
    Svc->>Svc: stopSelf()
    deactivate Svc
```

### 1.2 零冷启动开销保证 (Zero Cold-Start Overhead)
- **开关设计**：系统设置项 `aiControlEnabled` 默认值为 `false`。
- **冷启动零加载**：在 App 冷启动过程中，Koin 依赖注入容器仅注册单例工厂定义，绝不实例化 `CIOApplicationEngine`，不分配网络端口，协程作用域保持未激活。对普通用户而言，无任何 CPU 或内存额外消耗。

---

## 2. Bearer Token 强鉴权机制

为防止本地流氓 App 或局域网恶意节点非法调用自动化接口，所有进入 `/mcp` 与 `/sse` 的请求必须通过严格的 Token 校验。

### 2.1 鉴权拦截流程
1. `AiControlService` 在启动时，从加密 DataStore 中读取持久化的 `aiMcpToken`（初次开启时自动生成高强度 UUID）；
2. Ktor 应用管道安装拦截中间件（Pipeline Interceptor）：
   ```kotlin
   intercept(ApplicationCallPipeline.Plugins) {
       val path = call.request.path()
       // 放行免鉴权端点（如健康检查）
       if (path == "/health" || path == "/") return@intercept

       val authHeader = call.request.headers[HttpHeaders.Authorization]
       val expected = "Bearer $token"
       if (authHeader.isNullOrBlank() || authHeader != expected) {
           call.respondText(
               text = """{"error":"Unauthorized","message":"Invalid or missing Bearer token"}""",
               contentType = ContentType.Application.Json,
               status = HttpStatusCode.Unauthorized
           )
           finish()
       }
   }
   ```
3. 任何缺少或错误的凭证请求均在协议解析最外层直接截断，返回 `401 Unauthorized`，杜绝未授权渗透。

---

## 3. 网络监听策略：本地回环 vs 局域网

### 3.1 默认绑定 `127.0.0.1` (Loopback Only)
- 默认情况下，服务仅监听 `127.0.0.1:18080`。
- **安全性**：Android 系统的 Linux 内核网络栈严格隔离本地回环，局域网中任何外部 IP 均无法访问该端口。
- **PC 直连方案**：PC 客户端仅需通过 ADB 转发：
  ```bash
  adb forward tcp:18080 tcp:18080
  ```
  即可在 PC 端以 `http://127.0.0.1:18080/mcp` 安全通信，通信数据流经 USB 线缆或受信任的 ADB 会话。

### 3.2 局域网访问 (`0.0.0.0`) 二次确认保护
- 若用户希望在局域网 NAS 或独立服务器上部署 Dify 并直接与手机通信，需在设置中勾选「允许局域网访问」。
- **安全警告弹窗**：触发开启时，App 会弹窗警示“开放局域网可能带来暴露风险，请确保处于受信任的私有 Wi-Fi 且保管好 Bearer Token”。
- 开启后监听地址切换为 `0.0.0.0`，此时局域网内设备可通过 `http://<手机局域网IP>:18080/mcp` 连接，鉴权规则依旧生效。

---

## 4. 日志隐私脱敏体系 (`LogRedactor`)

当智能体通过 `maa_get_logs` 工具排查执行故障时，诊断日志中可能包含用户的敏感信息（如游戏账号、Penguin Stats 凭据、沙盒内部绝对路径等）。

MaaMeow 内置了高性能正则脱敏器 `LogRedactor`，默认对所有日志输出执行脱敏：

### 4.1 脱敏规则对照表

| 敏感信息类型 | 原始数据样例 | 脱敏后替换结果 |
|---|---|---|
| **Bearer Token** | `Authorization: Bearer 8f14-9b2c-47a1` | `Authorization: Bearer sk-****` |
| **游戏账号名 / 渠道** | `"accountName": "DoctorKelsey@bilibili"` | `"accountName": "Doc****@***"` |
| **企鹅物流凭证** | `penguin_id: "pg_991823104"` | `penguin_id: "pg_****"` |
| **礼包 CDK** | `cdk: "MAA2026NOVEMB"` | `cdk: "MAA****"` |
| **沙盒绝对私有路径** | `/data/user/0/com.aliothmoon.maameow/files/run.log` | `[APP_SANDBOX]/run.log` |

---

## 5. 游戏截图隐私门控机制

屏幕截图不仅包含游戏战斗画面，在界面切换或意外弹出系统通知、聊天悬浮窗时，极易捕获用户的私聊内容或系统通知。

因此，`maa_screenshot` 工具被设计为 **显式二次授权门禁**：
1. **默认禁用**：在设置项中，`aiMcpScreenshotEnabled` 默认为 `false`。
2. **代码级拦截**：MCP Server 的工具执行闭包直接注入了状态探针：
   ```kotlin
   server.addTool(name = "maa_screenshot", ...) {
       if (!screenshotEnabled()) {
           return@addTool errorResult(
               "maa_screenshot is disabled by default. Enable 'Allow AI Screenshot' in MaaMeow AI Control Settings first."
           )
       }
       // 仅在明确授权后调用底层的 FrameSnapshotter
       bridge.captureScreenshotBase64().fold(...)
   }
   ```
3. 在用户未明确开启前，任何调用尝试都会被温和拒绝，并明确指引前往设置界面授权。
