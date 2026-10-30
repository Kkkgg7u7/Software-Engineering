> 2026-10-29 版本提示：本文含早期狼人杀设计与操作示例。当前交付范围、规则和生产运行方式以 [当前状态](JIUJIU_CURRENT_STATUS.md)、[运行与部署](RELEASE_AND_OPERATIONS.md) 及对应阶段报告为准；旧的角色/人数/多数决、禁止 WebRTC 或删除卷升级等说法已被新需求覆盖。

# REST 接口文档（API）

> 新增平台用户/好友/媒体接口见 [SOCIAL_API.md](SOCIAL_API.md)。原有局内 /api/chat 接口继续独立保留。

> 本文档面向**前后端开发者与测试人员**，解决一个问题：**不读后端代码就能准确调用每一个 HTTP 接口**——包含全部字段、全部错误码、以及可直接复制执行的 curl。

---

## 一、通用约定

### 1.1 基本信息

| 项 | 值 |
| --- | --- |
| Base URL（开发） | `http://127.0.0.1:8080` |
| Base URL（前端调用前缀） | `/api`（开发期由 Vite 代理到 8080；生产由 Nginx 同源转发） |
| 协议 | HTTP/1.1，请求与响应均为 `application/json; charset=UTF-8` |
| 实例配置来源 | `backend/src/main/resources/application.yml` → `server.port: ${SERVER_PORT:8080}` |
| 压缩 | `server.compression.enabled: true` |
| Jackson 行为 | `default-property-inclusion: non_null`（**为 null 的字段不会出现在 JSON 里**）、`time-zone: Asia/Shanghai` |

### 1.2 统一响应体

所有 REST 接口（包括错误响应）都返回 `ApiResponse` 结构，定义于 `common/ApiResponse.java`：

```json
{
  "code": 0,
  "message": "ok",
  "data": { },
  "timestamp": 1733558400000
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | int | **0 表示成功**；非 0 为业务错误码，见 1.3 |
| `message` | string | 成功时为 `"ok"`；失败时为错误描述（业务异常会追加 `: 详细原因`） |
| `data` | object / array / null | 业务数据；失败时为 `null` |
| `timestamp` | long | 服务端生成响应体的毫秒时间戳 |

> **HTTP 状态码约定**：本项目**业务错误一律返回 HTTP 200**，错误信息在 `code` 字段里。这是 `SecurityConfig.writeError()` 与 `GlobalExceptionHandler` 的统一行为，前端 Axios 拦截器只解析 `code`。
>
> 唯一的例外是 `GlobalExceptionHandler.handleOther()` 上的 `@ResponseStatus(HttpStatus.OK)`——它同样返回 200。

### 1.3 错误码全表

从 `backend/src/main/java/com/werewolf/common/ErrorCode.java` **完整抄录**：

#### 通用（1xxx）

| code | 枚举名 | message |
| --- | --- | --- |
| `1000` | `BAD_REQUEST` | 请求参数不合法 |
| `1001` | `UNAUTHORIZED` | 未登录或登录已过期 |
| `1002` | `FORBIDDEN` | 没有权限执行该操作 |
| `1003` | `NOT_FOUND` | 资源不存在 |
| `1004` | `INTERNAL_ERROR` | 服务器内部错误 |
| `1005` | `RATE_LIMITED` | 操作过于频繁，请稍后再试 |

#### 用户 / 认证（2xxx）

| code | 枚举名 | message |
| --- | --- | --- |
| `2000` | `USERNAME_TAKEN` | 用户名已被注册 |
| `2001` | `NICKNAME_TAKEN` | 昵称已被占用 |
| `2002` | `USER_NOT_FOUND` | 用户不存在 |
| `2003` | `PASSWORD_WRONG` | 用户名或密码错误 |
| `2004` | `ACCOUNT_DISABLED` | 账号已被禁用 |

#### 房间（3xxx）

| code | 枚举名 | message |
| --- | --- | --- |
| `3000` | `ROOM_NOT_FOUND` | 房间不存在或已解散 |
| `3001` | `ROOM_PASSWORD_WRONG` | 房间密码错误 |
| `3002` | `ROOM_FULL` | 房间人数已满 |
| `3003` | `ROOM_NOT_WAITING` | 房间已开始游戏，无法加入 |
| `3004` | `NOT_IN_ROOM` | 你不在该房间中 |
| `3005` | `NOT_HOST` | 只有房主可以执行该操作 |
| `3006` | `ALREADY_IN_ROOM` | 你已在其它房间中 |
| `3007` | `PLAYER_NOT_READY` | 还有玩家未准备 |
| `3008` | `ROOM_CONFIG_INVALID` | 房间配置不合法(角色数量与人数不匹配) |

#### 游戏（4xxx）

| code | 枚举名 | message |
| --- | --- | --- |
| `4000` | `GAME_NOT_FOUND` | 当前房间没有进行中的游戏 |
| `4001` | `GAME_ALREADY_STARTED` | 游戏已经开始 |
| `4002` | `GAME_NOT_YOUR_TURN` | 还没轮到你发言 |
| `4003` | `GAME_WRONG_PHASE` | 当前阶段不允许该操作 |
| `4004` | `GAME_ROLE_NOT_ALLOWED` | 你的角色不能执行该操作 |
| `4005` | `GAME_PLAYER_DEAD` | 该玩家已出局 |
| `4006` | `GAME_TARGET_INVALID` | 目标不合法 |
| `4007` | `GAME_ALREADY_ACTED` | 本回合你已经行动过了 |
| `4008` | `GAME_POTION_USED` | 该药水已经用过了 |
| `4009` | `GAME_ALREADY_VOTED` | 你已经投过票了 |

#### AI（5xxx）

| code | 枚举名 | message |
| --- | --- | --- |
| `5000` | `AI_UNAVAILABLE` | AI 服务暂不可用，已切换为规则 AI |
| `5001` | `AI_OUTPUT_INVALID` | AI 输出格式不合法，已被规则 AI 接管 |

> **重要说明**：`5000` 与 `5001` 目前**只在 `ErrorCode` 中定义，运行时代码不主动抛出**。AI 不可用/输出非法时的实际行为是**静默降级到 `RuleAgent` 并写日志**（见 `AiService.decide` 与 `LlmAgent.decide`），不会中断对局。这两个码保留给前端做提示 UI 使用。

#### 前端错误码常量

`frontend/src/types/index.ts` 里的 `ErrorCode` 常量对象与上表**一一对应**（含 `as const`）。前端 `AUTH_ERROR_CODES = [1001]`，收到 1001 会清除本地 Token 并跳转登录页。

### 1.4 鉴权方式

| 项 | 说明 |
| --- | --- |
| 方案 | Spring Security + JWT（HS256，无状态） |
| 请求头 | `Authorization: Bearer <token>` |
| 签发 | `JwtTokenService.generate()`，`subject` = `userId`，额外 claim：`username`、`nickname` |
| 有效期 | `werewolf.jwt.expire-minutes`，默认 `1440` 分钟（24 小时） |
| issuer | `werewolf-game` |
| 密钥 | `werewolf.jwt.secret`，**至少 32 字节**，否则启动即失败 |
| WebSocket | Token 放 `?token=<JWT>` query 参数或 STOMP `CONNECT` 帧的 `Authorization` native header，详见 [docs/WEBSOCKET.md](WEBSOCKET.md) |

**免鉴权白名单**（`SecurityConfig.filterChain`）：

```java
.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
.requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
.requestMatchers("/api/health", "/actuator/**").permitAll()
.requestMatchers("/ws/**", "/ws-native/**").permitAll()
.requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
.anyRequest().authenticated()
```

> `/api/auth/logout` **不在**白名单里，需要 Token。
> 其余全部接口（含 `/api/game/meta`、`/api/lobby/rooms`）**都需要 Token**。

**未鉴权时的响应**（由 `authenticationEntryPoint` 直接写回，HTTP 200）：

```json
{ "code": 1001, "message": "未登录或登录已过期", "data": null, "timestamp": 1733558400000 }
```

### 1.5 请求体校验约定

带 `@Valid` 的接口在校验失败时，由 `GlobalExceptionHandler.handleValidation` 返回：

```json
{
  "code": 1000,
  "message": "请求参数不合法: 房间名称不能为空; 房间人数至少 4 人",
  "data": null,
  "timestamp": 1733558400000
}
```

多个字段错误用 `"; "` 拼接。请求体不是合法 JSON 时返回 `1000` + `"请求体格式错误，期望合法 JSON"`。

### 1.6 curl 使用说明

- **bash / Linux / macOS**：下文所有示例可直接复制执行。
- **Windows PowerShell**：`curl` 是 `Invoke-WebRequest` 的别名，**不要直接用**。两种办法：
  1. 用 `curl.exe`（Windows 10 1803+ 自带）：把示例里的 `curl` 换成 `curl.exe`；
  2. 用 `Invoke-RestMethod`，第 1.6.1 节给出对照示例。
- 换行符 `\` 在 PowerShell 里要写成反引号 `` ` ``，或者干脆写成一行。

#### 1.6.1 登录（PowerShell vs bash 对照）

**bash：**

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo1234"}'
```

**Windows PowerShell：**

```powershell
$body = '{"username":"demo","password":"demo1234"}'
$resp = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" `
  -Method Post -ContentType "application/json" -Body $body
$resp.data.token
```

#### 1.6.2 把 Token 存成变量（后续示例都用它）

**bash：**

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo1234"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "$TOKEN"
```

**Windows PowerShell：**

```powershell
$TOKEN = (Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post `
  -ContentType "application/json" `
  -Body '{"username":"demo","password":"demo1234"}').data.token
$TOKEN
```

---

## 二、认证与用户

### 2.1 `POST /api/auth/register` — 注册

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ❌ 否 |
| Controller | `auth/AuthController.register` |
| Service | `auth/AuthService.register` |

**请求体字段：**

| 字段 | 类型 | 必填 | 约束（Jakarta Validation） | 说明 |
| --- | --- | --- | --- | --- |
| `username` | string | ✅ | `@NotBlank`、`@Size(3,20)`、`@Pattern("^[A-Za-z0-9_]+$")` | 只能字母数字下划线 |
| `password` | string | ✅ | `@NotBlank`、`@Size(6,32)` | 服务端 BCrypt 加密后存储 |
| `nickname` | string | ✅ | `@NotBlank`、`@Size(1,16)` | 全局唯一 |
| `avatar` | string | ❌ | 无 | 留空时默认 `/assets/avatars/bot-01.svg` |

**成功响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 86400,
    "user": {
      "id": 6,
      "username": "newbie",
      "nickname": "新玩家",
      "avatar": "/assets/avatars/bot-01.svg",
      "totalGames": 0,
      "winGames": 0
    }
  },
  "timestamp": 1733558400000
}
```

| 响应字段 | 类型 | 说明 |
| --- | --- | --- |
| `token` | string | JWT，之后所有请求用它 |
| `expiresIn` | long | **秒**（`expireMinutes * 60`） |
| `user.id` | number | 用户 ID |
| `user.username` | string | 登录名 |
| `user.nickname` | string | 展示名 |
| `user.avatar` | string | 头像路径 |
| `user.totalGames` | number | 总对局数（初始 0） |
| `user.winGames` | number | 胜场数（初始 0） |

**可能错误码：** `1000`（参数校验失败）、`2000`（用户名已注册）、`2001`（昵称已占用）

**bash：**

```bash
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"newbie","password":"demo1234","nickname":"新玩家"}'
```

**Windows PowerShell：**

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/auth/register" -Method Post `
  -ContentType "application/json" `
  -Body '{"username":"newbie","password":"demo1234","nickname":"新玩家"}'
```

---

### 2.2 `POST /api/auth/login` — 登录

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ❌ 否 |
| Controller | `auth/AuthController.login` |
| Service | `auth/AuthService.login` |

**请求体字段：**

| 字段 | 类型 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- | --- |
| `username` | string | ✅ | `@NotBlank` | 登录名 |
| `password` | string | ✅ | `@NotBlank` | 明文密码，服务端用 `passwordEncoder.matches` 比对 |

**成功响应：** 结构同 2.1。

**可能错误码：** `1000`、`2003`（用户名或密码错误）

> **安全细节**：用户不存在与密码错误**返回同一个码 `2003`**，不泄露「该用户名是否存在」。同时 `SecurityConfig` 里 `DaoAuthenticationProvider.setHideUserNotFoundExceptions(true)`。

**bash：**

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo1234"}'
```

**Windows PowerShell：**

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post `
  -ContentType "application/json" `
  -Body '{"username":"demo","password":"demo1234"}'
```

---

### 2.3 `POST /api/auth/logout` — 退出登录

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `auth/AuthController.logout` |
| 请求体 | 无 |

**成功响应：**

```json
{ "code": 0, "message": "ok", "data": null, "timestamp": 1733558400000 }
```

**可能错误码：** `1001`

> **实现说明**：JWT 是无状态的，服务端**不会**把 Token 加入黑名单——`logout` 只做了一件事：`Long userId = CurrentUser.id();`，即校验 Token 有效。真正的登出由客户端完成：清除 `localStorage` 里的 `werewolf_token` / `werewolf_user`，并断开 WebSocket（见 `frontend/src/stores/auth.ts` 的 `clear()`）。

**bash：**

```bash
curl -s -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer $TOKEN"
```

**Windows PowerShell：**

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/auth/logout" -Method Post `
  -Headers @{ Authorization = "Bearer $TOKEN" }
```

---

### 2.4 `GET /api/users/me` — 获取当前用户资料

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `user/UserController.me` |

**响应 `data`（`AuthResponse.UserInfo`）：**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | number | 用户 ID |
| `username` | string | 登录名 |
| `nickname` | string | 昵称 |
| `avatar` | string | 头像 |
| `totalGames` | number | 总对局数 |
| `winGames` | number | 胜场数 |

**成功响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": { "id": 1, "username": "demo", "nickname": "演示玩家",
            "avatar": "/assets/avatars/bot-01.svg", "totalGames": 3, "winGames": 2 },
  "timestamp": 1733558400000
}
```

**可能错误码：** `1001`、`2002`（用户不存在）

**bash：**

```bash
curl -s http://localhost:8080/api/users/me -H "Authorization: Bearer $TOKEN"
```

---

### 2.5 `PUT /api/users/me` — 修改昵称 / 头像

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `user/UserController.updateMe` |
| Service | `auth/AuthService.updateProfile` |
| 注意 | 请求体**没有** `@Valid`，校验在 Service 里做 |

**请求体字段：**

| 字段 | 类型 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- | --- |
| `nickname` | string | ❌ | `@Size(max=16)`（注解存在，但请求体未标 `@Valid`，实际由 Service 判断非空 + 唯一性） | 留空/不传则不改 |
| `avatar` | string | ❌ | 无 | 留空/不传则不改 |

**成功响应：** 同 2.4，返回更新后的 `UserInfo`。

**可能错误码：** `1001`、`2002`、`2001`（新昵称已被别人占用）

**bash：**

```bash
curl -s -X PUT http://localhost:8080/api/users/me \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"月光猎人","avatar":"/assets/avatars/bot-07.svg"}'
```

**Windows PowerShell：**

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/users/me" -Method Put `
  -Headers @{ Authorization = "Bearer $TOKEN" } -ContentType "application/json" `
  -Body '{"nickname":"月光猎人","avatar":"/assets/avatars/bot-07.svg"}'
```

---

### 2.5.1 用户资料扩展与修改密码

GET /api/users/me、PUT /api/users/me、注册/登录成功的 user 均增加 signature、createdAt、updatedAt。时间格式为服务端 LocalDateTime ISO 字符串。禁止返回 password/authVersion。

PUT /api/users/me 支持可选 nickname（1—16 位且非空白）、signature（最多 120 个 UTF-16 单位，可清空）、avatar（最多255位的 HTTP(S) 地址或 /assets/avatars/ 默认素材路径）。省略字段保留原值，未知 userId 不作为身份依据；只修改 JWT 对应账号。格式错误为1000，昵称冲突2001。

POST /api/users/me/password 需要登录，请求 `{ "currentPassword": "原密码", "newPassword": "新密码" }`，两字段均6—32位，返回 `ApiResponse<Void>`。原密码错误2003，新密码与原密码相同/格式错误1000。成功后 BCrypt 更新密码并原子增加认证版本，旧 JWT 失效，旧实时连接在事务提交后以4002关闭，客户端清理本地会话并重新登录。不要使用旧 Token 再调用 logout 作为成功条件。

资料、登录、战绩采用局部字段更新，避免并发更新覆盖密码。头像上传与公开用户搜索在后续媒体/好友阶段实现，当前头像仅支持默认素材与 URL。

### 2.6 `GET /api/health` — 健康检查

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ❌ 否（白名单） |
| Controller | `user/UserController.health` |

**响应 `data` 字段：**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `status` | string | 固定 `"UP"` |
| `service` | string | 固定 `"werewolf-server"` |
| `activeRooms` | number | **内存中**的房间运行时数量（`roomManager.size()`） |
| `aiProvider` | string | AI 提供方描述。三种可能值见下。**本轮交付默认关闭 AI** |
| `llmEnabled` | boolean | 是否具备真实 LLM 调用能力（`properties.isLlmUsable()`） |
| `time` | number | 服务端毫秒时间戳 |

**`aiProvider` 的三种取值**（`AiService.providerDescription()`）：

| 条件 | 返回值 | 说明 |
| --- | --- | --- |
| **`werewolf.ai.enabled=false`（默认）** | `规则 AI（AI 总开关关闭）` | **本轮交付的默认状态**，不会创建 AI 座位、不发起任何外部请求 |
| `enabled=true` 但 `api-key` 为空 | `规则 AI（未配置 API Key，零外部依赖）` | 可选增强：本地规则 AI，零成本 |
| `enabled=true` 且已配置 Key | `LLM(deepseek-chat @ https://api.deepseek.com) + 规则 AI 兜底` | 可选增强：大模型驱动 |

**成功响应（默认配置下的真实输出）：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "status": "UP",
    "service": "werewolf-server",
    "activeRooms": 2,
    "aiProvider": "规则 AI（AI 总开关关闭）",
    "llmEnabled": false,
    "time": 1733558400000
  },
  "timestamp": 1733558400000
}
```

**bash：**

```bash
curl -s http://localhost:8080/api/health
```

---

## 三、房间

### 3.1 `POST /api/rooms` — 创建房间

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `room/RoomController.create` |
| Service | `room/RoomService.createRoom` |
| 副作用 | 创建 `rooms` + `room_players` 记录；若 `allowAi=true` 则按 `aiCount` 添加 AI 座位（**默认不添加**）；向 `/topic/rooms` 广播 `ROOM_UPDATE` |

**请求体字段（`CreateRoomRequest`）：**

| 字段 | 类型 | 必填 | 默认值 | 约束 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `roomName` | string | ✅ | — | `@NotBlank`、`@Size(max=30)` | 房间名 |
| `password` | string | ❌ | `null` | `@Size(max=32)` | 留空=无密码；存 BCrypt 哈希 |
| `maxPlayers` | number | ❌ | `8` | `@Min(4)`、`@Max(12)` | 最大人数 |
| `werewolfCount` | number | ❌ | `2` | `@Min(1)` | 狼人数 |
| `aiCount` | number | ❌ | **`0`** | `@Min(0)`、`@Max(8)` | AI NPC 数量。**默认 0：房间由真人玩家组成** |
| `allowAi` | boolean | ❌ | **`false`** | — | 是否允许 AI 加入。**默认 false**（配合 `werewolf.ai.enabled=false`，本轮交付默认不含 AI） |
| `publicRoom` | boolean | ❌ | `true` | — | 是否出现在大厅列表 |

> **可选增强提示**：`aiCount` 与 `allowAi` 的默认值是有意设成「无 AI」的。需要演示 AI NPC 时，请**同时**满足两个条件：① `POST /api/rooms` 里显式传 `"allowAi":true,"aiCount":3`；② 后端启动时设 `WEREWOLF_AI_ENABLED=true`。详见 [README 第十节](../README.md)。

**额外的组合校验**（`RoomService.validateConfig(maxPlayers, werewolfCount, aiCount)`，失败抛 `3008 ROOM_CONFIG_INVALID`）：

| 规则 | 失败提示 |
| --- | --- |
| `4 <= maxPlayers <= 12` | `房间人数必须在 4~12 之间` |
| `1 <= werewolfCount <= maxWerewolfCount(maxPlayers)` | `狼人数量必须在 1~N 之间（需为预言家和女巫留出座位，且少于人数的一半）` |
| `0 <= aiCount <= maxPlayers - 1` | `AI 数量必须在 0~N 之间（至少留 1 个真人座位）` |
| `maxPlayers >= werewolfCount + 2` | `座位不足：需要为预言家和女巫各留 1 个座位，N 人局最多 M 只狼` |

其中 `maxWerewolfCount(n) = max(0, min(⌊(n-1)/2⌋, n - 2))`（`RoleAssigner.maxWerewolfCount`，`n` 即 `maxPlayers`）。

| `maxPlayers` | `maxWerewolfCount` | `maxPlayers` | `maxWerewolfCount` |
| --- | --- | --- | --- |
| 4 | 1 | 9 | 4 |
| 5 | 2 | 10 | 4 |
| 6 | 2 | 11 | 5 |
| 7 | 3 | 12 | 5 |
| 8 | 3 | — | — |

> ⚠️ **注意没有 `maxPlayers - aiCount >= werewolfCount + 1` 这条约束**。AI 占用的也是**真实座位**，开局时 `total = 真人 + AI`，因此只需保证「总座位数能放下 w 个狼 + 2 个神职」即可。`{"maxPlayers":4,"werewolfCount":1,"aiCount":3,"allowAi":true}`（房主 1 真人 + 3 AI）是**合法**配置，可以直接开局。
>
> **开局时还有一次「实际入座人数」校验**（`RoomService.startGame`）：`total >= 4`，否则 `3008`（「至少需要 4 名玩家（含 AI）才能开始，当前 N 人」）。这是为了防止「房间容量 4 人、实际只坐了 3 人」时发出 `[WEREWOLF, SEER, WITCH]` 这种**没有平民**的退化配置。

**成功响应 `data`（`RoomView`）：**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | number | 房间 ID |
| `roomCode` | string | **6 位**房间码，字符集 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`（去掉了易混的 `I`/`O`/`0`/`1`） |
| `roomName` | string | 房间名 |
| `hasPassword` | boolean | 是否有密码（**不返回密码本身**） |
| `maxPlayers` | number | 最大人数 |
| `werewolfCount` | number | 狼人数 |
| `aiCount` | number | AI 数量 |
| `allowAi` | boolean | 是否允许 AI |
| `publicRoom` | boolean | 是否公开 |
| `status` | string | `WAITING` / `PLAYING` / `ENDED` |
| `hostUserId` | number | 房主 userId |
| `hostNickname` | string | 房主昵称（房主不在座位里时为 `null`） |
| `playerCount` | number | 当前座位数（含 AI） |
| `humanCount` | number | 真人数量 |
| `readyCount` | number | 已准备人数（AI 恒为已准备） |
| `allReady` | boolean | 是否全部已准备（`seats` 非空且 `ready == playerCount`） |
| `seats` | array | 座位数组，见下 |
| `mySeatId` | number \| null | 请求者座位号 |
| `iAmHost` | boolean | 请求者是否房主 |
| `iAmReady` | boolean | 请求者是否已准备 |

`seats[]` 元素（`RoomView.SeatView`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `seatId` | number | 座位号，1-based |
| `userId` | number | 用户 ID（**AI 是负数**，如 `-1`、`-2`） |
| `nickname` | string | 昵称 |
| `avatar` | string | 头像 |
| `ai` | boolean | 是否 AI |
| `ready` | boolean | 是否准备 |
| `alive` | boolean | 是否存活（等待阶段恒为 `true`） |
| `role` | string \| null | **始终为 `null`**——`RoomRuntime.roomView()` 不填充该字段 |
| `host` | boolean | 是否房主 |

**成功响应示例：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 1, "roomCode": "K7M2QP", "roomName": "答辩演示",
    "hasPassword": false, "maxPlayers": 4, "werewolfCount": 1, "aiCount": 0,
    "allowAi": true, "publicRoom": true, "status": "WAITING",
    "hostUserId": 1, "hostNickname": "演示玩家",
    "playerCount": 1, "humanCount": 1, "readyCount": 0, "allReady": false,
    "seats": [
      { "seatId": 1, "userId": 1, "nickname": "演示玩家",
        "avatar": "/assets/avatars/bot-01.svg", "ai": false,
        "ready": false, "alive": true, "host": true }
    ],
    "mySeatId": 1, "iAmHost": true, "iAmReady": false
  },
  "timestamp": 1733558400000
}
```

**可能错误码：** `1000`、`1001`、`2002`、`3006`（你已在其它房间中）、`3008`（配置不合法）

**bash：**

```bash
curl -s -X POST http://localhost:8080/api/rooms \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"roomName":"答辩演示","maxPlayers":4,"werewolfCount":1,"aiCount":0,"publicRoom":true}'
```

**Windows PowerShell：**

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/rooms" -Method Post `
  -Headers @{ Authorization = "Bearer $TOKEN" } -ContentType "application/json" `
  -Body '{"roomName":"答辩演示","maxPlayers":4,"werewolfCount":1,"aiCount":0}'
```

**带密码 + 3 个 AI 的房间（可选增强：一个人也能开局）：**

```bash
curl -s -X POST http://localhost:8080/api/rooms \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"roomName":"AI 练习房","password":"secret123","maxPlayers":4,"werewolfCount":1,"aiCount":3,"allowAi":true,"publicRoom":false}'
```

> 注意 `allowAi` 必须显式为 `true`，否则 `RoomService.addBots()` 里的 `if (!Boolean.TRUE.equals(room.getAllowAi())) return;` 会直接跳过创建 AI 座位。同时后端需以 `WEREWOLF_AI_ENABLED=true` 启动，否则 AI 座位不会行动。

---

### 3.2 `POST /api/rooms/join` — 加入房间

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `room/RoomController.join` |
| Service | `room/RoomService.joinRoom` |
| 注意 | 请求体**没有** `@Valid` |

**请求体字段（`JoinRoomRequest`）：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `roomCode` | string | ✅ | 房间码，**大小写不敏感**（服务端 `normalizeCode` 转大写） |
| `password` | string | ❌ | 房间有密码时必填 |

**行为细节：**

1. 如果**已经在**这个房间里 → 视为**重连**，标记在线并直接返回，不报错；
2. 如果已在**其它**房间 → `3006`；
3. 房间 `gameStatus == PLAYING` → `3003`；
4. 房间有密码且不匹配 → `3001`；
5. 座位已满 → `3002`；
6. 分配**最小空闲座位号**（`RoomRuntime.nextFreeSeat()`）。

**成功响应：** `RoomView`，结构同 3.1。

**可能错误码：** `1001`、`2002`、`3000`、`3001`、`3002`、`3003`、`3006`

**bash：**

```bash
curl -s -X POST http://localhost:8080/api/rooms/join \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"roomCode":"K7M2QP","password":"secret123"}'
```

**无密码房间：**

```bash
curl -s -X POST http://localhost:8080/api/rooms/join \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"roomCode":"K7M2QP"}'
```

---

### 3.3 `GET /api/rooms/public` — 公开房间列表

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `room/RoomController.publicRooms` |
| Service | `room/RoomService.listPublicRooms` |

**筛选条件**（`RoomManager.publicWaitingRooms()`，三个条件同时满足才出现）：

1. `rooms.public_room = true`
2. `rooms.game_status = 'WAITING'`
3. `!runtime.isFull()`（还有空位）

**排序**：按房间创建时间**倒序**（最新的在前）。

**成功响应：** `RoomView` 数组。

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    { "id": 2, "roomCode": "A3B9XY", "roomName": "随便玩", "playerCount": 2,
      "humanCount": 2, "maxPlayers": 8, "werewolfCount": 2, "aiCount": 0,
      "hasPassword": false, "publicRoom": true, "status": "WAITING",
      "seats": [ ], "iAmHost": false, "iAmReady": false }
  ],
  "timestamp": 1733558400000
}
```

**可能错误码：** `1001`

**bash：**

```bash
curl -s http://localhost:8080/api/rooms/public -H "Authorization: Bearer $TOKEN"
```

---

### 3.4 `GET /api/rooms/current` — 我当前所在的房间

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `room/RoomController.current` |
| Service | `room/RoomService.currentRoom` |

**响应：** `RoomView`；**不在任何房间时 `data` 为 `null`**（`code` 仍为 `0`，不报错）。

```json
{ "code": 0, "message": "ok", "data": null, "timestamp": 1733558400000 }
```

> 前端用它实现「刷新页面后恢复我在哪个房间」（`roomStore.restoreCurrent()`）。

**可能错误码：** `1001`

**bash：**

```bash
curl -s http://localhost:8080/api/rooms/current -H "Authorization: Bearer $TOKEN"
```

---

### 3.5 `GET /api/rooms/{roomCode}` — 房间详情

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `room/RoomController.detail` |
| 路径参数 | `roomCode`：房间码，大小写不敏感 |
| 注意 | **不要求调用者在房间内**（可能是邀请链接预览） |

**成功响应：** `RoomView`。

**可能错误码：** `1001`、`3000`

**bash：**

```bash
curl -s http://localhost:8080/api/rooms/K7M2QP -H "Authorization: Bearer $TOKEN"
```

---

### 3.6 `PUT /api/rooms/{roomCode}` — 修改房间配置

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `room/RoomController.update` |
| Service | `room/RoomService.updateRoom` |
| 权限 | **仅房主**（`requireHost`，否则 `3005`） |
| 阶段限制 | 游戏进行中（`isGameStarted() && phase != END`）返回 `3003` |

**请求体字段（`UpdateRoomRequest`，全部可选）：**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `roomName` | string | `@Size(max=30)`；非空才生效 |
| `maxPlayers` | number | **不能小于当前真人数**，否则 `3008` |
| `werewolfCount` | number | 与 `maxPlayers` 组合校验 |
| `aiCount` | number | 增大则补 AI 座位，减小则从**最大座位号**开始删并重排座位号 |
| `allowAi` | boolean | 关闭后不再补 AI |
| `publicRoom` | boolean | 是否公开 |
| `password` | string | 非空则更新密码（BCrypt） |
| `clearPassword` | boolean | `true` 时**清除密码**（优先级高于 `password`） |

**成功响应：** 更新后的 `RoomView`。

**可能错误码：** `1001`、`3003`、`3004`、`3005`、`3008`

**bash（清空密码 + 改成 6 人房）：**

```bash
curl -s -X PUT http://localhost:8080/api/rooms/K7M2QP \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"maxPlayers":6,"werewolfCount":2,"aiCount":0,"clearPassword":true}'
```

---

### 3.7 `DELETE /api/rooms/{roomCode}` — 解散房间

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `room/RoomController.dissolve` |
| Service | `room/RoomService.dissolve(runtime, "房主主动解散")` |
| 权限 | **仅房主**——非房主返回 `3005`（在 Controller 里判断，`ApiResponse.fail(ErrorCode.NOT_HOST)`） |

**行为：** 把 `rooms.game_status` 置为 `ENDED`，**删除该房间的全部 `room_players` 记录**，从 `RoomManager` 移除运行时，广播房间列表刷新。

**成功响应：**

```json
{ "code": 0, "message": "ok", "data": null, "timestamp": 1733558400000 }
```

**可能错误码：** `1001`、`3000`、`3005`

**bash：**

```bash
curl -s -X DELETE http://localhost:8080/api/rooms/K7M2QP \
  -H "Authorization: Bearer $TOKEN"
```

> **注意**：如果自己是房主，调 `POST /api/rooms/{roomCode}/leave` 也会**解散房间**（见 3.9）。

---

### 3.8 `POST /api/rooms/{roomCode}/ready` — 准备 / 取消准备

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `room/RoomController.ready` |
| Service | `room/RoomService.setReady` + `messenger.roomUpdate` |

**请求体（可选，`@RequestBody(required = false)`）：**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `ready` | boolean | **不传 body 或不传该字段时默认 `true`**；传 `false` 取消准备 |

**限制**：游戏进行中（`isGameStarted() && phase != END`）返回 `3003`，提示「游戏进行中无法修改准备状态」。

**成功响应：** 更新后的 `RoomView`。同时通过 WebSocket 向房间内每个人推送各自的 `RoomView`。

**可能错误码：** `1001`、`3003`、`3004`、`3000`

**bash（准备）：**

```bash
curl -s -X POST http://localhost:8080/api/rooms/K7M2QP/ready \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ready":true}'
```

**bash（取消准备）：**

```bash
curl -s -X POST http://localhost:8080/api/rooms/K7M2QP/ready \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ready":false}'
```

**bash（默认准备，不传 body）：**

```bash
curl -s -X POST http://localhost:8080/api/rooms/K7M2QP/ready \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{}'
```

---

### 3.9 `POST /api/rooms/{roomCode}/leave` — 退出房间

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `room/RoomController.leave` |
| Service | `room.RoomService.leaveRoom` |

**行为（重要）：**

| 角色 | 行为 |
| --- | --- |
| 普通玩家 | 移除座位、重建 `room_players`、广播状态、播报「XX 离开了房间」 |
| **房主** | **整个房间解散**（`dissolve(runtime, "房主已退出，房间解散")`）——本项目不做房主转移 |

**成功响应：**

```json
{ "code": 0, "message": "ok", "data": null, "timestamp": 1733558400000 }
```

**可能错误码：** `1001`、`3004`

**bash：**

```bash
curl -s -X POST http://localhost:8080/api/rooms/K7M2QP/leave \
  -H "Authorization: Bearer $TOKEN"
```

---

### 3.10 `DELETE /api/rooms/{roomCode}/seats/{seatId}` — 房主踢人

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `room/RoomController.kick` |
| Service | `room/RoomService.kick` |
| 权限 | **仅房主**，否则 `3005` |

**路径参数：**

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `roomCode` | string | 房间码 |
| `seatId` | number | 目标座位号（1-based） |

**行为：**

| 目标 | 行为 |
| --- | --- |
| 真人 | 通过 `/user/queue/error` 推送 `KICKED` 报文（「你已被移出房间」）→ 移除座位 → 落库 → 广播 |
| AI | 从**最大座位号**开始删掉一个 AI，并把 `rooms.ai_count` 减 1，**重排座位号**（`renumberSeats`） |

**限制**：房主不能踢自己（`1000`，提示「房主不能踢出自己」）；座位不存在返回 `4006`。

**成功响应：**

```json
{ "code": 0, "message": "ok", "data": null, "timestamp": 1733558400000 }
```

**可能错误码：** `1000`、`1001`、`3004`、`3005`、`4006`、`3000`

**bash（踢 3 号座位）：**

```bash
curl -s -X DELETE http://localhost:8080/api/rooms/K7M2QP/seats/3 \
  -H "Authorization: Bearer $TOKEN"
```

---

### 3.11 `GET /api/lobby/rooms` — 大厅房间列表

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `websocket/LobbyController.rooms` |
| 说明 | 与 `GET /api/rooms/public` **功能完全等价**，只是路径不同（前端 `lobbyApi.rooms()` 用的是这个） |

**实时刷新机制**（`LobbyController` 的类注释）：

> REST 提供首次拉取，WebSocket 负责增量通知：`/topic/rooms` 收到 `{"type":"ROOM_UPDATE","data":{"refresh":true}}` 后，前端**再调用本接口**拉全量，避免在 WebSocket 消息体里塞大对象。

**成功响应：** `RoomView` 数组。

**可能错误码：** `1001`

**bash：**

```bash
curl -s http://localhost:8080/api/lobby/rooms -H "Authorization: Bearer $TOKEN"
```

---

### 3.12 `POST /api/rooms/match` — 快速匹配

需要登录，无请求体，响应为现有 `ApiResponse<RoomView>`。身份由 JWT 获取，客户端不传 userId。

当前策略：已在房间时幂等返回当前房间；否则筛选公开、无密码、WAITING、未满、4 人、1 狼、0 AI 的房间，优先填入人数更多、等待更久的房间。无候选时创建同配置的公开“快速匹配房”。匹配成功进入等待房，仍需非房主玩家准备、房主开局。

匹配不加入密码房、私人房或已开局房。取消使用现有 `POST /api/rooms/{roomCode}/leave`；若客户端在分配完成前点击取消，应等响应确认实际房间后再退出。房主退出仍按现有规则解散房间。当前不是可选人数/玩法的通用匹配接口，后续按游戏目录阶段扩展。

Controller：`RoomController.match`；Service：`RoomService.matchRoom`。通常业务错误沿用认证/用户/房间错误码，未新增错误码。回归：`RoomMatchIntegrationTest` 覆盖兼容房加入与幂等、排除私人/密码/非兼容房、8 个并发请求分入两个四人房。

```bash
curl -s -X POST http://localhost:8080/api/rooms/match -H "Authorization: Bearer $TOKEN"
```

## 四、游戏

### 4.1 `POST /api/game/start` — 房主开始游戏

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `game/GameController.start` |
| Service | `game/GameService.startGame` → `room/RoomService.startGame` → `RoomRuntime.startGame()` |

**请求体：** 无。

**服务端校验链（按顺序，任一条失败即返回）：**

| # | 检查 | 失败码 |
| --- | --- | --- |
| 1 | 调用者在房间中 | `3004` |
| 2 | 调用者是房主 | `3005` |
| 3 | 游戏未在进行（`isGameStarted() && phase != END`） | `4001` |
| 4 | 座位数 ≥ 4 | `3008`（提示「至少需要 4 名玩家（含 AI）才能开始，当前 N 人」） |
| 5 | 所有**真人**都已准备（AI 恒为已准备） | `3007`（提示「XX 还没有准备」） |
| 6 | `RoleAssigner.validateConfig(total, werewolfCount)` 通过 | `3008` |

> 若当前 `phase == END`（上一局刚结束），会**先自动 `resetToWaiting()`** 再开局，无需手动重置。

**成功响应 `data`：** 调用者视角的 `GameView`（含自己的 `me`）。结构见 4.3。

**副作用：** 分配角色 → 进入 `STARTING`（发牌）阶段 → 广播 `GAME_START` + `PHASE_CHANGE` 事件 → 广播状态 → 登记 AI 调度 → 落库座位。

**可能错误码：** `1001`、`3004`、`3005`、`3007`、`3008`、`4001`

**bash：**

```bash
curl -s -X POST http://localhost:8080/api/game/start \
  -H "Authorization: Bearer $TOKEN"
```

---

### 4.2 `POST /api/game/action` — 提交一次行动

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `game/GameController.action` |
| Service | `game/GameService.applyAction` → `RoomRuntime.applyHumanAction` → `WerewolfGameEngine.applyAction` |
| 说明 | 与 STOMP 的 `/app/room/{roomCode}/action` **走同一套校验**，HTTP 便于调试与自动化测试 |

**请求体字段（`ActionRequest`）：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `action` | string | ✅ | `@NotBlank`。取值必须是 `ActionType` 之一，大小写不敏感 |
| `targetSeatId` | number | 视动作而定 | 目标座位号；`WITCH_PASS` / `ABSTAIN` / `SPEAK` 可为空 |
| `speech` | string | 视动作而定 | `SPEAK` 必填；超过 500 字符会被**截断**（不是报错） |

**`action` 取值与目标要求：**

| `action` | 需要 `targetSeatId` | 允许的阶段 | 允许的角色 | 服务端额外约束 |
| --- | --- | --- | --- | --- |
| `WOLF_KILL` | ✅ 必填 | `NIGHT` | 狼人 | 目标必须存活、**不能是狼同伴**、本回合未行动过 |
| `SEER_CHECK` | ✅ 必填 | `NIGHT` | 预言家 | 目标必须存活、**不能是自己**、**不能重复查验同一人** |
| `WITCH_HEAL` | ❌ 服务端忽略（固定救当晚被刀者） | `NIGHT` | 女巫 | 解药必须还在；当晚必须有人被刀；本回合未行动过 |
| `WITCH_POISON` | ✅ 必填 | `NIGHT` | 女巫 | 毒药必须还在；目标必须存活、不能是自己 |
| `WITCH_PASS` | ❌ | `NIGHT` | 女巫 | 本回合未行动过 |
| `SPEAK` | ❌ | `DISCUSSION` | 任意存活 | **必须是当前发言人**；`speech` 非空；超 500 字符截断 |
| `VOTE` | ✅ 必填 | `VOTING` | 任意存活 | 目标必须存活、**不能是自己**、本回合未投过票 |
| `ABSTAIN` | ❌ | `VOTING` | 任意存活 | 本回合未投过票 |

**成功响应 `data`：** 调用者视角的 `GameView`。

> **注意**：这是**同步返回**的便捷通道。STOMP 通道不返回响应体，而是通过 `/user/queue/game` 补发状态 + `/user/queue/error` 报错。

**可能的错误码（按 `applyAction` 校验顺序）：**

| 错误码 | 触发条件 |
| --- | --- |
| `1000` | `action` 为空或不在 `ActionType` 枚举内（「未知的行动类型: XXX」） |
| `1001` | Token 无效 |
| `3004` | 不在房间中 |
| `4000` | 阶段为 `WAITING`（「游戏还没有开始」） |
| `4005` | 座位不存在，或玩家已出局 |
| `4003` | 阶段不允许该操作 |
| `4004` | 角色不允许该操作（例如平民提交 `WOLF_KILL`） |
| `4007` | 本回合已行动过 |
| `4006` | 目标为空 / 不存在 / 已出局 / 是自己 / 是狼同伴 / 重复查验 |
| `4008` | 药水已用过 |
| `4009` | 已投过票 |
| `4002` | 发言时不是当前发言人 |

**bash（狼人刀 3 号）：**

```bash
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"WOLF_KILL","targetSeatId":3}'
```

**bash（预言家查验 2 号）：**

```bash
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"SEER_CHECK","targetSeatId":2}'
```

**bash（女巫用毒药毒 4 号）：**

```bash
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"WITCH_POISON","targetSeatId":4}'
```

**bash（女巫不用药）：**

```bash
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"WITCH_PASS"}'
```

**bash（发言）：**

```bash
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"SPEAK","speech":"我是 1 号，昨晚平安夜，我怀疑 3 号。"}'
```

**bash（投票）：**

```bash
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"VOTE","targetSeatId":3}'
```

**bash（弃票）：**

```bash
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"ABSTAIN"}'
```

**Windows PowerShell（投票）：**

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/game/action" -Method Post `
  -Headers @{ Authorization = "Bearer $TOKEN" } -ContentType "application/json" `
  -Body '{"action":"VOTE","targetSeatId":3}'
```

---

### 4.3 `GET /api/game/state` — 我看到的游戏状态

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `game/GameController.state` |
| Service | `game/GameService.state` → `RoomRuntime.projectFor(mySeatId)` |

**响应 `data`（`GameView`）：**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `roomId` | number | 房间 ID |
| `roomCode` | string | 房间码 |
| `roomName` | string | 房间名 |
| `phase` | string | `WAITING` / `STARTING` / `NIGHT` / `DAY` / `DISCUSSION` / `VOTING` / `CHECK_WINNER` / `END` |
| `phaseLabel` | string | 中文标签，如「夜晚」 |
| `round` | number | 第几轮（夜晚编号），从 1 开始；未开局为 0 |
| `phaseSecondsLeft` | number | 当前阶段剩余秒数 |
| `maxPlayers` | number | 房间最大人数 |
| `werewolfCount` | number | 本局狼人数 |
| `gameOver` | boolean | 是否已结束 |
| `winner` | string \| null | `VILLAGE` / `WEREWOLF`；未结束为 `null` |
| `winnerLabel` | string \| null | 「好人阵营」/「狼人阵营」 |
| `dayAnnounced` | boolean | 白天死讯是否已公布 |
| `currentSpeakerSeatId` | number \| null | 当前发言人座位号（仅讨论阶段） |
| `currentSpeakerNickname` | string \| null | 当前发言人昵称 |
| `lastExiledSeatId` | number \| null | 上一轮被放逐的座位号 |
| `lastNightDeaths` | number[] | 上一晚死亡座位号（已排序） |
| `players` | array | 见下 |
| `voteCounts` | object | `{"3": 2, "4": 1}` —— **被投座位号 → 得票数**（不公布谁投了谁） |
| `votedCount` | number | 已投票人数 |
| `aliveCount` | number | 存活人数 |
| `abstainCount` | number | 弃票数 |
| `me` | object \| null | **仅本人可见的私有信息**，见下 |
| `notices` | string[] | 最近 5 条系统提示（「X 号被投票放逐」「昨晚是平安夜」） |

`players[]` 元素（`GameView.PlayerView`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `seatId` | number | 座位号 |
| `userId` | number | 用户 ID（AI 为负数） |
| `nickname` | string | 昵称 |
| `avatar` | string | 头像 |
| `alive` | boolean | 是否存活 |
| `ready` | boolean | 是否准备 |
| `ai` | boolean | 是否 AI |
| `speaking` | boolean | 是否正在发言 |
| `voted` | boolean | 本轮是否已投票 |
| `role` | string | **仅 `gameOver == true` 时存在**；否则该键在 JSON 中不出现 |

`me`（`GameView.PrivateView`，`@JsonInclude(NON_NULL)`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `role` | string | 自己的身份 |
| `roleLabel` | string | 「狼人」/「预言家」/「女巫」/「平民」 |
| `camp` | string | `WEREWOLF` / `VILLAGE` |
| `skill` | string | 技能描述 |
| `assetName` | string | 素材名（小写枚举名），用于拼 `/assets/roles/{assetName}-card.svg` |
| `wolfTeammates` | number[] | **仅狼人**：狼同伴座位号（含已出局的） |
| `healPotionAvailable` | boolean | **仅女巫**：解药是否可用 |
| `poisonPotionAvailable` | boolean | **仅女巫**：毒药是否可用 |
| `checkRecords` | array | **仅预言家**：`[{round, seatId, nickname, wolf, label}]` |
| `availableActions` | string[] | 当前可执行的行动（供前端渲染按钮） |
| `selectableTargets` | number[] | 当前可选的目标座位号（供前端置灰非法座位） |

**成功响应示例（狼人视角，夜晚）：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "roomId": 1, "roomCode": "K7M2QP", "roomName": "答辩演示",
    "phase": "NIGHT", "phaseLabel": "夜晚", "round": 1, "phaseSecondsLeft": 22,
    "maxPlayers": 4, "werewolfCount": 1,
    "gameOver": false, "dayAnnounced": false,
    "lastNightDeaths": [], "voteCounts": {}, "votedCount": 0,
    "aliveCount": 4, "abstainCount": 0,
    "players": [
      { "seatId": 1, "userId": 1, "nickname": "演示玩家", "alive": true,
        "ready": true, "ai": false, "speaking": false, "voted": false },
      { "seatId": 2, "userId": 2, "nickname": "1号玩家", "alive": true,
        "ready": true, "ai": false, "speaking": false, "voted": false },
      { "seatId": 3, "userId": 3, "nickname": "2号玩家", "alive": true,
        "ready": true, "ai": false, "speaking": false, "voted": false },
      { "seatId": 4, "userId": 4, "nickname": "3号玩家", "alive": true,
        "ready": true, "ai": false, "speaking": false, "voted": false }
    ],
    "me": {
      "role": "WEREWOLF", "roleLabel": "狼人", "camp": "WEREWOLF",
      "skill": "夜晚与同伴共同猎杀一名玩家", "assetName": "werewolf",
      "wolfTeammates": [],
      "availableActions": ["WOLF_KILL"],
      "selectableTargets": [2, 3, 4]
    },
    "notices": []
  },
  "timestamp": 1733558400000
}
```

**可能错误码：** `1001`、`3004`、`3000`

**bash：**

```bash
curl -s http://localhost:8080/api/game/state -H "Authorization: Bearer $TOKEN"
```

**Windows PowerShell：**

```powershell
(Invoke-RestMethod -Uri "http://localhost:8080/api/game/state" `
  -Headers @{ Authorization = "Bearer $TOKEN" }).data | ConvertTo-Json -Depth 6
```

---

### 4.4 `GET /api/game/state/{roomCode}` — 按房间码查状态（观战 / 重连）

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `game/GameController.stateByCode` |
| Service | `game/GameService.stateByCode` |

**行为差异（重要）：**

```java
public GameView stateByCode(String roomCode, Long userId) {
    RoomRuntime runtime = roomService.requireByCode(roomCode);
    PlayerState me = runtime.getState().byUser(userId);
    Integer seatId = me == null ? null : me.getSeatId();
    return runtime.projectFor(seatId);   // 不在房间内时 seatId == null → 无 me 字段
}
```

| 情况 | 返回内容 |
| --- | --- |
| 在房间内 | 带 `me` 的完整私有视图 |
| **不在房间内（观战）** | 公共视图，**`me` 字段不出现**（不会泄露任何人的身份） |

**成功响应：** `GameView`，结构同 4.3。

**可能错误码：** `1001`、`3000`

**bash：**

```bash
curl -s http://localhost:8080/api/game/state/K7M2QP -H "Authorization: Bearer $TOKEN"
```

---

### 4.5 `GET /api/game/speeches` — 本局公开发言记录

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `game/GameController.speeches` |
| Service | `game/GameService.speeches` |

**响应 `data`：** 数组，按发言时间顺序。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `round` | number | 第几轮 |
| `seatId` | number | 发言人座位号 |
| `nickname` | string | 发言人昵称 |
| `content` | string | 发言内容（最长 500 字符） |
| `ai` | boolean | 是否 AI 发言 |

**成功响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    { "round": 1, "seatId": 1, "nickname": "演示玩家", "content": "我是 1 号，昨晚平安夜。", "ai": false },
    { "round": 1, "seatId": 2, "nickname": "AI·月影", "content": "我是 2 号。我是平民，只能靠发言和投票找狼。 我的建议是先聚焦 3 号。", "ai": true }
  ],
  "timestamp": 1733558400000
}
```

**可能错误码：** `1001`、`3004`、`3000`

**bash：**

```bash
curl -s http://localhost:8080/api/game/speeches -H "Authorization: Bearer $TOKEN"
```

---

### 4.6 `POST /api/game/reset` — 再来一局（回到等待状态）

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `game/GameController.reset` |
| 权限 | **仅房主**（Controller 里判断，非房主返回 `3005`） |

**行为**（`RoomRuntime.resetToWaiting()`）：

| 重置项 | 结果 |
| --- | --- |
| 阶段 | `WAITING`，`phaseSecondsLeft = 0`，`round = 0` |
| 胜负 | `finished = false`，`winner = null` |
| 投票 / 死讯 / 发言 / 事件流 | 全部清空 |
| 每个玩家 | `alive = true`、**`role = null`**、`ready = isAi()`（真人需要重新准备）、药水重置、查验记录清空 |
| 房间 | `rooms.game_status = 'WAITING'` |

**成功响应：**

```json
{ "code": 0, "message": "ok", "data": null, "timestamp": 1733558400000 }
```

**可能错误码：** `1001`、`3004`、`3005`

**bash：**

```bash
curl -s -X POST http://localhost:8080/api/game/reset \
  -H "Authorization: Bearer $TOKEN"
```

---

### 4.6.1 `POST /api/game/meta` 之外的补充说明

`meta` 是 `GET`，见下一节。

---

### 4.7 `GET /api/game/meta` — 元数据（角色 / 阶段 / 行动 / AI 状态）

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `game/GameController.meta` |
| 用途 | 前端渲染图例与提示时**避免硬编码字符串** |

**响应 `data` 字段：**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `roles` | array | 4 个角色的元数据 |
| `phases` | array | 8 个阶段的元数据 |
| `actions` | string[] | `["WOLF_KILL","SEER_CHECK","WITCH_HEAL","WITCH_POISON","WITCH_PASS","SPEAK","VOTE","ABSTAIN"]` |
| `aiProvider` | string | 同 `/api/health` 的 `aiProvider`。**默认配置下为「规则 AI（AI 总开关关闭）」** |
| `llmEnabled` | boolean | 是否具备真实 LLM 能力。**默认配置下为 `false`** |
| `cardBack` | string | 固定 `"/assets/roles/hidden-back.svg"` |
| `effects` | object | 事件键 → 特效素材路径，8 项 |

`roles[]` 元素（`RoleMeta`）：

| 字段 | 类型 | 示例 |
| --- | --- | --- |
| `name` | string | `"WEREWOLF"` |
| `label` | string | `"狼人"` |
| `camp` | string | `"WEREWOLF"` |
| `campLabel` | string | `"狼人阵营"` |
| `skill` | string | `"夜晚与同伴共同猎杀一名玩家"` |
| `asset` | string | `"/assets/roles/werewolf-card.svg"` |
| `icon` | string | `"/assets/roles/werewolf-icon.svg"` |
| `token` | string | `"/assets/roles/werewolf-token.svg"` |

`phases[]` 元素（`PhaseMeta`）：

| 字段 | 类型 | 示例 |
| --- | --- | --- |
| `name` | string | `"NIGHT"` |
| `label` | string | `"夜晚"` |
| `isNight` | boolean | `true` |

`effects` 对象的全部键值：

| 键 | 素材路径 |
| --- | --- |
| `WOLF_KILL` | `/assets/effects/wolf-kill.svg` |
| `SEER_CHECK` | `/assets/effects/seer-check.svg` |
| `WITCH_HEAL` | `/assets/effects/witch-heal.svg` |
| `WITCH_POISON` | `/assets/effects/witch-poison.svg` |
| `VOTE` | `/assets/effects/vote.svg` |
| `DAWN` | `/assets/effects/dawn.svg` |
| `WIN_GOOD` | `/assets/effects/win-good.svg` |
| `WIN_WEREWOLF` | `/assets/effects/win-werewolf.svg` |

**成功响应（节选）：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "roles": [
      { "name": "WEREWOLF", "label": "狼人", "camp": "WEREWOLF", "campLabel": "狼人阵营",
        "skill": "夜晚与同伴共同猎杀一名玩家",
        "asset": "/assets/roles/werewolf-card.svg",
        "icon": "/assets/roles/werewolf-icon.svg",
        "token": "/assets/roles/werewolf-token.svg" }
    ],
    "phases": [ { "name": "WAITING", "label": "等待开始", "isNight": false } ],
    "actions": ["WOLF_KILL","SEER_CHECK","WITCH_HEAL","WITCH_POISON","WITCH_PASS","SPEAK","VOTE","ABSTAIN"],
    "aiProvider": "规则 AI（AI 总开关关闭）",
    "llmEnabled": false,
    "cardBack": "/assets/roles/hidden-back.svg",
    "effects": { "WOLF_KILL": "/assets/effects/wolf-kill.svg" }
  },
  "timestamp": 1733558400000
}
```

**可能错误码：** `1001`

**bash：**

```bash
curl -s http://localhost:8080/api/game/meta -H "Authorization: Bearer $TOKEN"
```

**Windows PowerShell（只看角色名与标语）：**

```powershell
$meta = (Invoke-RestMethod -Uri "http://localhost:8080/api/game/meta" `
  -Headers @{ Authorization = "Bearer $TOKEN" }).data
$meta.roles | Select-Object name, label, campLabel, skill | Format-Table -AutoSize
```

---

## 五、聊天

### 5.1 `GET /api/chat/{roomCode}/history` — 聊天历史

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `websocket/ChatController.history` |
| Service | `websocket/ChatService.history` |

**路径 / 查询参数：**

| 参数 | 位置 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `roomCode` | path | string | — | 房间码 |
| `limit` | query | number | `100` | 拉到的最多消息条数。服务端会**夹到 1~200**（`Math.min(Math.max(limit,1),200)`） |

**可见性过滤规则**（`ChatService.history`，按 `message_type` 逐条判断）：

| 消息类型 | 谁能看到 |
| --- | --- |
| `PUBLIC` | 房间内所有人 |
| `SYSTEM` | 房间内所有人 |
| `WOLF` | **仅狼人**（用当前对局的 `state.players` 判断哪些 userId 是狼） |
| `PRIVATE` | 仅 `sender_id` 或 `receiver_id` 等于请求者 |

> **调用者必须在房间内**，否则 `3004`。

**响应 `data`：** `Message` 数组，**按 id 升序**（服务端先按 id 倒序查 limit 条，过滤后再升序排序）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | number | 消息 ID |
| `roomId` | number | 房间 ID（**不是房间码**） |
| `senderId` | number | 发送者 userId；系统消息为 `0` |
| `receiverId` | number \| null | 私聊接收者；非私聊为 `null`（JSON 中不出现） |
| `content` | string | 内容（最长 500 字符） |
| `messageType` | string | `PUBLIC` / `PRIVATE` / `WOLF` / `SYSTEM` |
| `channel` | string | `LOBBY`（阶段为 `WAITING` 时）/ `GAME`（对局中） |
| `senderNickname` | string | 发送者昵称；系统消息为 `"系统"` |
| `isAi` | boolean | 是否来自 AI NPC |
| `createdAt` | string | `LocalDateTime`，序列化为 ISO 字符串（`write-dates-as-timestamps: false`） |

**成功响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    { "id": 1, "roomId": 1, "senderId": 1, "content": "大家好",
      "messageType": "PUBLIC", "channel": "LOBBY", "senderNickname": "演示玩家",
      "isAi": false, "createdAt": "2024-12-07T20:15:30" }
  ],
  "timestamp": 1733558400000
}
```

**可能错误码：** `1001`、`3004`、`3000`

**bash：**

```bash
curl -s "http://localhost:8080/api/chat/K7M2QP/history?limit=50" \
  -H "Authorization: Bearer $TOKEN"
```

---

### 5.2 `POST /api/chat/{roomCode}/send` — 发送聊天消息（HTTP 兜底）

| 项 | 值 |
| --- | --- |
| 是否需要 Token | ✅ 是 |
| Controller | `websocket/ChatController.send` |
| Service | `websocket/ChatService.send` |
| 说明 | 与 STOMP 的 `/app/chat/{roomCode}/send` **走同一套校验与投递逻辑**。实时场景用 STOMP，本接口便于 curl 测试 |

**请求体字段（`ChatService.ChatRequest`）：**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `content` | string | ✅ | 非空白；超过 500 字符**截断**（不是报错） |
| `receiverId` | number | 私聊必填 | 接收者 userId。**只要非空就按私聊处理** |
| `type` | string | ❌ | `PUBLIC` / `PRIVATE` / `WOLF`，缺省 `PUBLIC`。大小写不敏感 |

**频道判定逻辑**（`ChatService.resolveType`，优先级从上到下）：

| 顺序 | 条件 | 判定结果 |
| --- | --- | --- |
| 1 | `type == "WOLF"` | 检查发送者是否狼人，不是则 `4004`；是则 `WOLF` |
| 2 | `type == "PRIVATE"` **或** `receiverId != null` | `PRIVATE` |
| 3 | 其它 | `PUBLIC` |

**私聊额外校验**：`receiverId` 非空，且接收者**必须在当前房间内**（`runtime.getState().byUser(receiverId) != null`），否则 `4006`（「私聊对象不在房间内」）。

**投递规则：**

| 类型 | 投递目的地 |
| --- | --- |
| `PUBLIC` | `/topic/room/{roomCode}/chat`（房间内所有人） |
| `PRIVATE` | 只发给发送者与接收者的 `/topic/room/{roomCode}/chat`（**通过 `/user` 定向投递实现**，见下） |
| `WOLF` | 只发给**所有存活狼人且非 AI**（`p.isWolf() && !p.isAi() && userId > 0`） |

> 私聊与狼人频道用的是 `GameEventMessenger.privateChat(userId, roomCode, message)`，它调用 `convertAndSendToUser(...)` 投递到 `/topic/room/{roomCode}/chat` 这个目的地——**对该用户的那一条连接**。第三人虽然订阅了同一个目的地，但收不到这条消息。
>
> `room_players` 落库时，AI 的 `user_id` 存的是 `0`（`p.getUserId() == null ? 0L : p.getUserId()`，而 AI 的 userId 是负数，不会命中该分支）。

**成功响应 `data`：** 落库后的 `Message` 对象（含服务端生成的 `id` 与 `createdAt`）。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 42, "roomId": 1, "senderId": 1, "content": "3 号很可疑",
    "messageType": "PUBLIC", "channel": "GAME", "senderNickname": "演示玩家",
    "isAi": false, "createdAt": "2024-12-07T20:31:05"
  },
  "timestamp": 1733558400000
}
```

**可能错误码：** `1000`（内容为空）、`1001`、`3004`、`3000`、`4004`（好人用狼人频道）、`4006`（私聊对象不在房间）

**bash（公共发言）：**

```bash
curl -s -X POST http://localhost:8080/api/chat/K7M2QP/send \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"3 号很可疑","type":"PUBLIC"}'
```

**bash（私聊给 userId=3 的玩家）：**

```bash
curl -s -X POST http://localhost:8080/api/chat/K7M2QP/send \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"我是预言家，昨晚验了 4 号","receiverId":3,"type":"PRIVATE"}'
```

**bash（狼人频道，仅狼人能成功）：**

```bash
curl -s -X POST http://localhost:8080/api/chat/K7M2QP/send \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"今晚刀 3 号","type":"WOLF"}'
```

**Windows PowerShell（公共发言）：**

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/chat/K7M2QP/send" -Method Post `
  -Headers @{ Authorization = "Bearer $TOKEN" } -ContentType "application/json" `
  -Body '{"content":"3 号很可疑","type":"PUBLIC"}'
```

---

## 六、接口总览速查表

| # | 方法 | 路径 | Token | 主要错误码 |
| --- | --- | --- | --- | --- |
| 1 | POST | `/api/auth/register` | ❌ | 1000, 2000, 2001 |
| 2 | POST | `/api/auth/login` | ❌ | 1000, 2003 |
| 3 | POST | `/api/auth/logout` | ✅ | 1001 |
| 4 | GET | `/api/users/me` | ✅ | 1001, 2002 |
| 5 | PUT | `/api/users/me` | ✅ | 1001, 2001, 2002 |
| 6 | GET | `/api/health` | ❌ | — |
| 7 | POST | `/api/rooms` | ✅ | 1000, 3006, 3008 |
| 8 | POST | `/api/rooms/join` | ✅ | 3000, 3001, 3002, 3003, 3006 |
| 9 | GET | `/api/rooms/public` | ✅ | 1001 |
| 10 | GET | `/api/rooms/current` | ✅ | 1001 |
| 11 | GET | `/api/rooms/{roomCode}` | ✅ | 1001, 3000 |
| 12 | PUT | `/api/rooms/{roomCode}` | ✅ | 3003, 3004, 3005, 3008 |
| 13 | DELETE | `/api/rooms/{roomCode}` | ✅ | 3000, 3005 |
| 14 | POST | `/api/rooms/{roomCode}/ready` | ✅ | 3000, 3003, 3004 |
| 15 | POST | `/api/rooms/{roomCode}/leave` | ✅ | 3004 |
| 16 | DELETE | `/api/rooms/{roomCode}/seats/{seatId}` | ✅ | 1000, 3004, 3005, 4006 |
| 17 | GET | `/api/lobby/rooms` | ✅ | 1001 |
| 18 | POST | `/api/game/start` | ✅ | 3004, 3005, 3007, 3008, 4001 |
| 19 | POST | `/api/game/action` | ✅ | 4000, 4002, 4003, 4004, 4005, 4006, 4007, 4008, 4009 |
| 20 | GET | `/api/game/state` | ✅ | 1001, 3000, 3004 |
| 21 | GET | `/api/game/state/{roomCode}` | ✅ | 1001, 3000 |
| 22 | GET | `/api/game/speeches` | ✅ | 1001, 3000, 3004 |
| 23 | POST | `/api/game/reset` | ✅ | 1001, 3004, 3005 |
| 24 | GET | `/api/game/meta` | ✅ | 1001 |
| 25 | GET | `/api/chat/{roomCode}/history` | ✅ | 1001, 3000, 3004 |
| 26 | POST | `/api/chat/{roomCode}/send` | ✅ | 1000, 1001, 3000, 3004, 4004, 4006 |
| 27 | POST | `/api/rooms/match` | ✅ | 1001, 2002；沿用房间业务错误 |

---

## 七、一个可直接执行的完整冒烟脚本

**bash（Linux / macOS / WSL / Git Bash）：**

```bash
#!/usr/bin/env bash
# 4 人局冒烟测试：注册 4 个账号 → 建房 → 加入 → 准备 → 开局 → 查状态
set -e
BASE=http://localhost:8080

login() {  # $1=username $2=password
  curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}

# 1) 演示账号是 h2 profile 自动创建的，直接登录
T1=$(login demo    demo1234)
T2=$(login player1 demo1234)
T3=$(login player2 demo1234)
T4=$(login player3 demo1234)
echo "tokens ok"

# 2) 建房（4 人 / 1 狼 / 0 AI）
CODE=$(curl -s -X POST "$BASE/api/rooms" -H "Authorization: Bearer $T1" \
  -H "Content-Type: application/json" \
  -d '{"roomName":"冒烟测试","maxPlayers":4,"werewolfCount":1,"aiCount":0}' \
  | sed -n 's/.*"roomCode":"\([^"]*\)".*/\1/p')
echo "room=$CODE"

# 3) 加入
for T in $T2 $T3 $T4; do
  curl -s -X POST "$BASE/api/rooms/join" -H "Authorization: Bearer $T" \
    -H "Content-Type: application/json" -d "{\"roomCode\":\"$CODE\"}" > /dev/null
done

# 4) 全部准备
for T in $T1 $T2 $T3 $T4; do
  curl -s -X POST "$BASE/api/rooms/$CODE/ready" -H "Authorization: Bearer $T" \
    -H "Content-Type: application/json" -d '{"ready":true}' > /dev/null
done

# 5) 房主开局
curl -s -X POST "$BASE/api/game/start" -H "Authorization: Bearer $T1" \
  | sed -n 's/.*"phase":"\([^"]*\)".*/\1/p'
# 期望输出：STARTING

# 6) 健康检查
curl -s "$BASE/api/health"
echo
```

**Windows PowerShell：**

```powershell
$BASE = "http://localhost:8080"

function Login($u, $p) {
  (Invoke-RestMethod -Uri "$BASE/api/auth/login" -Method Post `
    -ContentType "application/json" `
    -Body (@{username=$u; password=$p} | ConvertTo-Json)).data.token
}

$T1 = Login "demo"    "demo1234"
$T2 = Login "player1" "demo1234"
$T3 = Login "player2" "demo1234"
$T4 = Login "player3" "demo1234"
Write-Host "tokens ok"

$H1 = @{ Authorization = "Bearer $T1" }
$CODE = (Invoke-RestMethod -Uri "$BASE/api/rooms" -Method Post -Headers $H1 `
  -ContentType "application/json" `
  -Body '{"roomName":"冒烟测试","maxPlayers":4,"werewolfCount":1,"aiCount":0}').data.roomCode
Write-Host "room=$CODE"

foreach ($t in @($T2, $T3, $T4)) {
  Invoke-RestMethod -Uri "$BASE/api/rooms/join" -Method Post `
    -Headers @{ Authorization = "Bearer $t" } -ContentType "application/json" `
    -Body (@{roomCode=$CODE} | ConvertTo-Json) | Out-Null
}

foreach ($t in @($T1, $T2, $T3, $T4)) {
  Invoke-RestMethod -Uri "$BASE/api/rooms/$CODE/ready" -Method Post `
    -Headers @{ Authorization = "Bearer $t" } -ContentType "application/json" `
    -Body '{"ready":true}' | Out-Null
}

$started = (Invoke-RestMethod -Uri "$BASE/api/game/start" -Method Post -Headers $H1).data
Write-Host "phase=$($started.phase)  myRole=$($started.me.role)"

Invoke-RestMethod -Uri "$BASE/api/health" | ConvertTo-Json -Depth 4
```

**期望结果**：最后一行的 `phase` 为 `STARTING`，`myRole` 为 `WEREWOLF` / `SEER` / `WITCH` / `VILLAGER` 之一。

> **4 人局的角色构成 = 1 狼 + 1 预言家 + 1 女巫 + 1 平民**。预言家与女巫由 `RoleAssigner.buildDeck()` **无条件各发 1 张**（常量 `REQUIRED_SPECIAL_ROLES = 2`），因此 4 人局同样会出现女巫。四个窗口的 `myRole` 合起来正好是这四种角色各一个。若想看到 `WITCH`，用 4 个窗口逐一 `GET /api/game/state` 比对即可。
