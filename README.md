# 啾啾 JiuJiu 社交桌游平台

当前版本（2026-10-29）：账号、好友、独立聊天/群聊/图片、动态墙、按配置匹配/邀请、13 人狼人杀、WebRTC 语音与规则/模型 AI。可用 `mvn -Pweb package` 构建前后端一体化 JAR（先构建前端）。

[当前交付与验证边界](docs/JIUJIU_CURRENT_STATUS.md) · [运行/发布/备份](docs/RELEASE_AND_OPERATIONS.md) · [当前游戏规则](docs/GAME_RULES.md) · [六人交接](docs/JIUJIU_TEAM_HANDOFF.md)

以下保留原工程说明，冲突处以以上当前文档为准。

# 基于 AI 增强的多人在线狼人杀系统

> 阶段 2 已完成，当前后端86项测试通过：好友管理、公开用户搜索、图片上传和头像设置。新平台契约见 [SOCIAL_API](docs/SOCIAL_API.md)，阶段记录见 [PHASE2_DELIVERY](docs/PHASE2_DELIVERY.md)。

> 2026-10-27 阶段 1 已完成：啾啾个人中心、资料签名、修改密码、旧登录失效、私人房间 HTTP/实时权限及增量迁移。当前 10 类 79 项后端测试通过；详见 [阶段 1 交付](docs/PHASE1_DELIVERY.md)。

> **2026-10-27 新需求基线**：项目扩展为“啾啾 JiuJiu”响应式社交桌游 Web 平台。已完整读取 docs 中三份 Word 需求，阶段 0 结论见 [啾啾审计与实施计划](docs/JIUJIU_PHASE0_AUDIT.md)。下文的交付边界及“舍弃功能”描述代表早期狼人杀版本；好友、群聊、图片消息、表白墙、语音已纳入新总体规划，尚未实现的功能不标记完成。当前实际验证为 8 类、73 项后端测试全通过，前端类型检查与构建通过。

> 本文档面向**课程项目的使用者（老师、助教、答辩评委、新加入的组员）**，解决一个问题：**不读源码也能在 5 分钟内把系统跑起来，用 4 个浏览器窗口完整玩完一局 4 人狼人杀**；需要深入某个模块时，本文档给出到 `docs/` 的索引。

---

## 一、项目简介

一句话定位：

> **核心是多人在线狼人杀，本轮交付基础功能闭环；AI 是可选增强，默认关闭，不影响基础功能运行。**

- **本轮交付（基础功能闭环）**：注册登录 → 创建房间 → 4 人加入 → 准备 → 开始游戏 → 夜晚（狼人/预言家/女巫行动）→ 白天公布死讯 → 讨论发言 → 投票放逐 → 胜负结算 → 局内聊天。全流程由后端的 `WerewolfGameEngine` 状态机驱动，**4 个真人玩家即可完整跑通，不需要任何 AI、不需要 API Key、不需要联网**。
- **可选增强（默认关闭，代码完整保留）**：房间里可以坐 AI NPC。打开开关后 AI 会像真人一样发言、投票、刀人、验人；也可接大模型（LLM）让发言更自然。**`werewolf.ai.enabled` 默认 `false`——系统默认不会向任何外部 LLM 发起请求**，开启方式见第十节。
- **视觉**：角色卡、技能特效、头像全部是**预生成的本地 SVG 素材**（原创，无外部依赖），由前端 `EffectManager.vue` 播放，运行时**不产生任何生成成本**。

技术形态：**单体 Spring Boot 应用**，同一进程内同时提供 REST 与 WebSocket(STOMP)，前端是 Vue3 单页应用，数据库用 MySQL 8（演示时用 H2 内存库）。

### 本轮交付边界一览

| 分类 | 内容 | 状态 |
| --- | --- | --- |
| 基础功能（P0） | 账号、房间、座位、准备、开局、夜晚/白天/讨论/投票/胜负、聊天、断线重连、房间管理 | ✅ **本轮交付，完整可用** |
| 特色功能（P1 中不依赖 AI 的部分） | 自定义房间配置、三频道聊天、WebSocket 实时同步、超时兜底、战绩落库 | ✅ **本轮交付，完整可用** |
| 展示类（P2 中不依赖 AI 的部分） | 原创 SVG 角色卡、8 个技能/结算特效、牌桌底纹、日志面板 | ✅ **本轮交付，完整可用** |
| AI 增强 | AI NPC（LLM 发言 + 规则 AI 兜底） | ⏸ **可选增强，默认关闭**（`werewolf.ai.enabled=false`、房间 `allowAi=false`、`aiCount=0`） |
| 明确舍弃 | 表白墙 / 好友社交 / 群组 / 剧本杀 / 语音视频 / WebRTC / 微服务 / Kafka / RabbitMQ / Redis 集群 / 推荐系统 | ❌ 未实现（符合方案） |

---

## 二、功能清单

### P0 必须完成（本轮交付，已全部完成）

| 功能 | 实现位置 | 验证方式 |
| --- | --- | --- |
| 注册、登录、退出 | `backend/.../auth/AuthController.java`、`auth/AuthService.java` | `POST /api/auth/register` / `login` / `logout` |
| JWT 无状态鉴权 | `backend/.../auth/security/`、`config/SecurityConfig.java` | 不带 Token 请求返回 1001 |
| 创建 / 加入 / 退出房间 | `backend/.../room/RoomController.java`、`RoomService.java` | `POST /api/rooms`、`/api/rooms/join`、`/api/rooms/{code}/leave` |
| 房间密码与最大人数 | `rooms.password`（BCrypt）、`rooms.max_players` | 错误密码返回 3001，满员返回 3002 |
| 玩家准备、房主开始 | `RoomService.setReady` / `RoomService.startGame` | 除房主外有真人未准备时，开局返回 3007 |
| 角色分配 | `backend/.../game/domain/RoleAssigner.java` | 4 人局 = 1 狼 + 1 预言家 + 1 女巫 + 1 平民（预言家与女巫**恒定各 1 名**，4 人局也保留） |
| 夜晚：狼人刀人 | `WerewolfGameEngine.wolfKill` | 狼人面板可选目标、狼刀多数决、不能刀同伴 |
| 夜晚：预言家查验 | `WerewolfGameEngine.seerCheck` | 结果仅本人可见；不能查自己、不能重复查同一人 |
| 夜晚：女巫用药 | `WerewolfGameEngine.witchHeal/witchPoison/witchPass` | 解药/毒药各一次；有 `WITCH_PASS` 可空过 |
| 夜晚结算 | `WerewolfGameEngine.resolveNight` | 解药优先于狼刀，毒药独立生效 |
| 白天公布死讯 | `GamePhase.DAY` + `DAWN`/`PEACEFUL_NIGHT` 事件 | 4 个窗口看到完全一致的死讯 |
| 讨论按序发言 | `WerewolfGameEngine.buildSpeakOrder/speak` | 从上一晚第一个死者下一位开始，只有当前发言人有输入框 |
| 投票放逐 | `WerewolfGameEngine.vote/abstain/resolveVoting` | 最高票出局；平票无人出局；支持弃票 |
| 胜负判定与复盘 | `WerewolfGameEngine.checkWinnerInternal`、`GameViewProjector` | `gameOver` 后公开全部身份 |
| 多人浏览器实时联机 | `config/WebSocketConfig.java`、`websocket/*` | 4 个窗口同房间状态实时一致 |
| 局内公共聊天 | `websocket/ChatService.java`、`ChatController.java` | `/topic/room/{code}/chat` 广播 |

### P1 主要特色（本轮交付，已全部完成）

| 功能 | 实现位置 | 说明 |
| --- | --- | --- |
| 自定义房间配置 | `room/dto/CreateRoomRequest.java`、`RoomService.validateConfig` | 人数 4~12，狼人数 1..min(⌊(n-1)/2⌋, n-2)，开局要求实际入座 ≥ 4 |
| 私聊与狼人频道 | `websocket/ChatService.java` | PUBLIC / PRIVATE / WOLF 三频道，可见性由服务端裁剪 |
| 断线重连与刷新恢复 | `RoomWsController.sync/me`、`LobbyController.resync` | `/app/room/{code}/sync`、`/app/game/resync` |
| 超时兜底 | `WerewolfGameEngine.autoCompleteCurrentPhase` | 保证对局永不卡死 |
| 房主管理 | `RoomService.kick/updateRoom/dissolve` | 踢人、改配置、解散房间 |
| 房间回收 | `RoomRuntimeScheduler.cleanupIdleRooms` | 每 5 分钟回收空闲房间，防内存泄漏 |
| 战绩落库 | `RoomService.saveGameRecord` | 每局结束写入 `game_records`，更新 `users` 战绩 |

### P2 加分项（本轮交付，已全部完成）

| 功能 | 实现位置 | 说明 |
| --- | --- | --- |
| 原创游戏素材 | `frontend/public/assets/roles/*.svg`、`avatars/bot-01~12.svg`、`brand/logo.svg`、`table-felt.svg` | 全部原创 SVG，不依赖任何外链 |
| 技能与结算特效 | `frontend/src/components/EffectManager.vue` + `frontend/public/assets/effects/*.svg` + `frontend/src/styles/effects.css` | 「预生成素材 + 前端播放」，运行时零成本 |
| 游戏记录与复盘 | `game_records.detail_json` + 前端战绩页 | 含座位、身份、存活、全部发言 |
| 用户战绩统计 | `users.total_games` / `users.win_games` | 对局结束按阵营累加 |
| 观战接口 | `GameService.stateByCode` | 不在房间内时返回无身份公开视图 |

### 可选增强（默认关闭）

| 功能 | 实现位置 | 默认状态 | 开启方式 |
| --- | --- | --- | --- |
| AI NPC（规则 AI） | `backend/.../ai/RuleAgent.java`、`AiService.java` | ⏸ 关闭 | 见第十节 |
| AI NPC（LLM 驱动） | `backend/.../ai/LlmAgent.java`、`ChatCompletionClient.java` | ⏸ 关闭 | 见第十节 |
| AI 头像与特效素材 | `frontend/public/assets/avatars/bot-01~12.svg` | 素材已在仓库中 | 随 AI 开启自动生效 |

> `ai.enabled=false` 时 `AiService.decide()` 会走 `RuleAgent`（零外部请求），但**房间 `aiCount` 默认为 0，所以正常情况下根本不会有 AI 座位被创建**。

### 明确舍弃（符合方案，未实现）

独立剧本杀 / 语音视频 / WebRTC / 微服务 / Kafka / RabbitMQ / Redis 集群 / 推荐系统。

> 以上均**未出现在** `backend/pom.xml` 与 `frontend/package.json` 的依赖列表中，可逐条核对。

---

## 三、技术栈

| 模块 | 技术 | 版本 | 用途 |
| --- | --- | --- | --- |
| 后端语言 | Java | 17（`java.version=17`） | 后端全部代码 |
| 后端框架 | Spring Boot | 3.3.5 | 单体应用、REST、依赖注入 |
| Web | spring-boot-starter-web | 随 3.3.5 | REST 接口 |
| 实时通信 | spring-boot-starter-websocket | 随 3.3.5 | STOMP over WebSocket |
| 安全 | spring-boot-starter-security | 随 3.3.5 | 过滤器链、BCrypt 编码器 |
| JWT | jjwt-api / jjwt-impl / jjwt-jackson | 0.12.6 | HS256 签发与验签 |
| 参数校验 | spring-boot-starter-validation | 随 3.3.5 | `@Valid` + Jakarta Validation |
| ORM | mybatis-plus-spring-boot3-starter | 3.5.9 | 单表 CRUD，无手写 XML |
| 数据库（生产） | mysql-connector-j | 随 3.3.5（runtime） | MySQL 8 连接 |
| 数据库（演示） | h2 | 随 3.3.5（runtime） | 内存库，零依赖演示 |
| 测试 | spring-boot-starter-test + spring-security-test + webflux | 随 3.3.5（test） | 单元 / 集成测试 |
| 前端框架 | vue | ^3.5.13 | 组合式 API |
| 前端语言 | typescript | ~5.6.3 | 类型安全 |
| 构建 | vite | ^5.4.11 | 开发服务器 + 打包 |
| 类型检查 | vue-tsc | ^2.1.10 | `pnpm build` 前置检查 |
| UI 组件 | element-plus | ^2.8.8 | 表单、表格、弹窗、消息提示 |
| 状态管理 | pinia | ^2.2.6 | auth / room / game / chat 四个 store |
| HTTP | axios | ^1.7.7 | 请求封装与响应拦截 |
| STOMP 客户端 | @stomp/stompjs | ^7.0.0 | WebSocket 订阅/发布 |
| SockJS 兜底 | sockjs-client | ^1.6.1 | 代理环境原生 WS 不可用时降级 |
| 路由 | vue-router | ^4.4.5 | 前端路由与登录守卫 |
| 包管理 | pnpm | v10+（见 `pnpm-workspace.yaml`） | 依赖安装 |
| AI 供应商（可选） | OpenAI 兼容 `/chat/completions` | — | DeepSeek / OpenAI / Ollama 均可，**默认关闭** |

---

## 四、目录结构

```
werewolf-game/
├── README.md                       ← 本文件：快速开始与全局索引
├── PROJECT_HANDBOOK.md             ← 方案条目 → 代码位置对照、六人分工、统一 AI 提示词
├── .gitignore                      忽略 target / node_modules / dist / .env.local / .tools / 日志 / OS 文件
├── .gitattributes                  统一 LF；*.sh / *.sql / *.java 等显式文本换行，*.bat/*.cmd/*.ps1 保留 CRLF
├── docs/                           ← 全套设计文档
│   ├── ARCHITECTURE.md             系统架构、服务器权威、并发模型、隐私裁剪链路
│   ├── API.md                      REST 接口契约、错误码全表、curl 示例
│   ├── WEBSOCKET.md                STOMP 地址表、报文结构、事件表、时序图、控制台测试片段
│   ├── GAME_RULES.md               角色表、状态机、夜晚结算顺序、投票与胜负、示例对局
│   ├── AI_DESIGN.md                可选增强：AI 决策链路、Prompt 全文、JSON 校验、成本控制
│   ├── DATABASE.md                 ER 图、五张表字段说明、索引、运维 SQL
│   ├── TEAM_PLAN.md                六人分工、文件归属映射、六周计划、分支与 PR 规范
│   ├── DEPLOY.md                   本地启动、Docker、裸机部署、环境变量、故障排查
│   └── TEST_PLAN.md                测试分层、自动化与手工用例、验收对照、答辩脚本
├── backend/                        ← Spring Boot 后端
│   ├── pom.xml                     Maven 依赖与版本（Spring Boot 3.3.5 / Java 17）
│   ├── Dockerfile                  多阶段构建：maven:3.9-eclipse-temurin-17 → eclipse-temurin:17-jre-jammy
│   └── src/
│       ├── main/java/com/werewolf/
│       │   ├── WerewolfApplication.java      启动类：@SpringBootApplication + @EnableScheduling
│       │   ├── common/                       统一响应体、错误码、业务异常、全局异常处理
│       │   ├── config/                       安全、WebSocket、配置属性、握手拦截、演示数据
│       │   ├── auth/                         注册登录、JWT 签发验签、当前用户工具
│       │   ├── user/                         用户实体、Mapper、资料与健康检查接口
│       │   ├── room/                         房间接口/服务/实体 + 运行时（Runtime/Manager/调度器）
│       │   ├── game/                         游戏接口/服务 + domain（状态与规则）+ engine（状态机）
│       │   ├── websocket/                    STOMP 目的地、控制器、消息出口、聊天服务
│       │   └── ai/                           可选增强：AI 服务、LLM/规则 Agent、Prompt、JSON 解析
│       ├── main/resources/
│       │   ├── application.yml               公共配置：端口、JWT、CORS、阶段时长、AI 开关(false)
│       │   ├── application-h2.yml            演示 profile：H2 内存库 + 自动建表（无 data.sql）
│       │   ├── application-mysql.yml         生产 profile：MySQL 8 + 连接池
│       │   └── db/h2/schema.sql              H2 建表脚本（MySQL 兼容模式），五张表 + 三个索引
│       └── test/java/com/werewolf/           7 个测试类 / 70 个用例（最近一次 mvn test 全通过）
│           ├── game/engine/WerewolfGameEngineTest.java     27 条：状态机与规则
│           ├── game/engine/NightResolveDebugTest.java       3 条：夜晚结算回归
│           ├── ai/RuleAgentTest.java                       11 条：规则 AI
│           ├── ai/AiJsonParserTest.java                    13 条：AI 输出校验闸门
│           ├── AuthFlowIntegrationTest.java                 9 条：认证/房间/元数据（H2 + MockMvc）
│           ├── RoomGameFlowIntegrationTest.java             4 条：开局流程与非法配置
│           └── websocket/WsAuthChannelInterceptorTest.java 3 条：STOMP 鉴权与订阅防护
├── frontend/                       ← Vue3 前端
│   ├── package.json                依赖与脚本（dev / build / build:only / typecheck / preview）
│   ├── vite.config.ts              别名 @、dev 代理 /api 与 /ws、分包策略
│   ├── tsconfig.json               严格模式 TS 配置
│   ├── env.d.ts                    Vite 环境变量与 *.vue 类型声明
│   ├── pnpm-workspace.yaml         允许 esbuild / vue-demi 运行安装脚本（pnpm v10 必需）
│   ├── .env.example                前端环境变量样例（VITE_API_BASE / VITE_WS_URL / VITE_USE_SOCKJS）
│   ├── index.html                  Vite 入口页（含首屏骨架，避免白屏）
│   ├── nginx.conf                  生产静态站点 + 反向代理（gzip / 缓存 / WebSocket 升级头）
│   ├── Dockerfile                  多阶段构建：node:20-alpine → nginx:1.27-alpine
│   ├── public/assets/              预生成 SVG 素材（角色卡 / 特效 / 头像 / 品牌）
│   │   ├── roles/                  4 角色 × (card + icon + token) + hidden-back 共 13 个
│   │   ├── effects/                8 个技能与结算特效
│   │   ├── avatars/                bot-01.svg ~ bot-12.svg，共 12 个
│   │   ├── brand/logo.svg          站点标识
│   │   └── table-felt.svg          牌桌底纹
│   └── src/
│       ├── main.ts                 应用入口：Pinia / Router / Element Plus / 断线跳登录
│       ├── App.vue                 全局骨架：背景、顶部导航、路由出口
│       ├── router/index.ts         路由表与登录守卫
│       ├── types/index.ts          与后端 DTO 严格对应的全部 TS 类型 + 错误码常量
│       ├── api/                    request（axios 封装）、auth、room、game、chat
│       ├── stores/                 auth、room、game、chat 四个 Pinia store
│       ├── websocket/stomp.ts      STOMP 客户端封装：订阅路由表、自动重连、离线队列
│       ├── components/             EffectManager（特效调度）、RoleCard、PlayerSeat、RoomSeatGrid、
│       │                           PhaseTimer、ChatPanel、GameLogPanel
│       ├── views/                  LoginView / LobbyView / RoomView / GameView / RecordsView / NotFoundView
│       └── styles/                 main.css（设计令牌）、effects.css（特效关键帧）
├── database/                       ← MySQL 8 脚本（生产 / 答辩演示）
│   ├── schema.sql                  建库 + 五张表 + 索引 + 字段注释（InnoDB / utf8mb4）
│   ├── seed.sql                    可选种子数据：2 条示例对局记录（不含账号）
│   └── README.md                   如何执行脚本、切换到 mysql profile 所需环境变量
└── deploy/                         ← 一键部署
    ├── docker-compose.yml          MySQL 8 + 后端 + 前端 编排（healthcheck + 自定义网络 + 数据卷）
    ├── .env.example                部署环境变量样例（WEREWOLF_AI_ENABLED=false 为默认）
    ├── README.md                   三种运行方式的完整步骤与验证方法
    └── nginx-standalone.conf       不用 Docker 的裸机 Nginx 示例（含 HTTPS 注释）
```

> **说明**：上表中所有路径**在当前仓库中都已存在**，`docs/TEST_PLAN.md` 给出了每个测试类与用例名，`docs/DEPLOY.md` 给出了完整的部署规格。

---

## 五、快速开始

### 前置条件

| 方式 | 需要安装 | 说明 |
| --- | --- | --- |
| ① 零依赖演示（推荐） | **JDK 17+**、**Node.js 18+**、**pnpm** | 不需要数据库、不需要 API Key、不需要联网 |
| ② 本地 MySQL | JDK 17+、Node 18+、pnpm、**MySQL 8** | 数据落盘，重启不丢 |
| ③ Docker Compose | **Docker Desktop / Docker Engine + Compose v2** | 一条命令起全栈 |

检查版本：

```bash
java -version        # 需要 17 或更高
node -v              # 需要 18 或更高
pnpm -v              # 没有的话：npm i -g pnpm
```

---

### 方式 ① 零依赖演示（推荐给答辩现场）

**特点**：H2 内存库 + 自动建表 + 自动创建 5 个演示账号，**全流程不需要数据库、不需要 API Key、不需要联网**。`application.yml` 里 `spring.profiles.active` 默认就是 `h2`。

**终端 1 — 启动后端：**

Windows PowerShell：

```powershell
cd werewolf-game\backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=h2"
```

如果没有 `mvnw.cmd`，用系统 Maven：

```powershell
cd werewolf-game\backend
mvn spring-boot:run "-Dspring-boot.run.profiles=h2"
```

Linux / macOS bash：

```bash
cd werewolf-game/backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
# 没有 mvnw 时：mvn spring-boot:run -Dspring-boot.run.profiles=h2
```

> 首次运行 Maven 需要下载依赖，视网络情况约 3~10 分钟；之后启动约 10 秒。看到 `Started WerewolfApplication` 即成功，并会打印「已创建 5 个演示账号（密码统一为 demo1234），可直接用 demo 登录」。

**终端 2 — 启动前端：**

```bash
cd werewolf-game/frontend
pnpm install          # 首次需要；Windows 与 Linux 命令相同
pnpm dev
```

浏览器打开 **http://localhost:5173**。

> `pnpm dev` 会把 `/api` 与 `/ws` 代理到 `http://127.0.0.1:8080`（见 `vite.config.ts`），因此前端不需要额外配置跨域。

**验证后端活着：**

```bash
curl http://localhost:8080/api/health
```

期望输出（注意 `aiProvider` 因为默认关闭，显示的是规则 AI）：

```json
{"code":0,"message":"ok","data":{"status":"UP","service":"werewolf-server",
 "activeRooms":0,"aiProvider":"规则 AI（AI 总开关关闭）","llmEnabled":false,
 "time":1733558400000},"timestamp":1733558400000}
```

**然后直接跳到第七节**，用 4 个浏览器窗口玩一局（这是本轮的验收主路径）。

> **想让室友 / 同学用他们自己的电脑一起玩？**
> 只需三件事：① 前端用 `pnpm dev --host` 启动；② 防火墙放行 8080 与 5173；
> ③ 把你这台机器的局域网 IP 告诉他们（`curl http://localhost:8080/api/health` 返回的
> `localAddresses` 里就有），他们浏览器打开 `http://<你的IP>:5173` 即可。
>
> 后端**已默认放行本机所有网卡地址**，不需要改任何配置。完整方案（局域网 / 内网穿透 /
> 云服务器 / 端口映射 四种）与排障手册见 **[docs/NETWORK_DEPLOY.md](docs/NETWORK_DEPLOY.md)**。
>
> 想让注册的账号重启后还在，用文件库启动后端：
> ```powershell
> $env:WEREWOLF_DB_URL="jdbc:h2:file:./data/werewolf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"
> java -jar target\werewolf-server.jar --spring.profiles.active=h2
> ```

---

### 方式 ② 本地 MySQL

**第 1 步：建库建表**（`database/schema.sql` 由成员 B 提供；结构以 `backend/src/main/resources/db/h2/schema.sql` 为准）

```bash
mysql -u root -p < database/schema.sql
```

**第 2 步：创建应用账号**（与 `application-mysql.yml` 的默认值一致）

```sql
CREATE USER 'werewolf'@'%' IDENTIFIED BY 'werewolf123';
GRANT ALL PRIVILEGES ON werewolf.* TO 'werewolf'@'%';
FLUSH PRIVILEGES;
```

**第 3 步：启动后端（mysql profile）**

Windows PowerShell：

```powershell
cd werewolf-game\backend
$env:SPRING_PROFILES_ACTIVE="mysql"
$env:MYSQL_HOST="127.0.0.1"
$env:MYSQL_PORT="3306"
$env:MYSQL_DATABASE="werewolf"
$env:MYSQL_USER="werewolf"
$env:MYSQL_PASSWORD="werewolf123"
mvn spring-boot:run
```

Linux / macOS bash：

```bash
cd werewolf-game/backend
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_DATABASE=werewolf
export MYSQL_USER=werewolf MYSQL_PASSWORD=werewolf123
mvn spring-boot:run
```

> **MySQL 模式下没有演示账号**（`DemoDataInitializer` 带 `@Profile("h2")` 注解）。请先注册 4 个账号，见第六节。

---

### 方式 ③ Docker Compose

```bash
cd werewolf-game
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml ps
```

访问 **http://localhost**（Nginx 80 端口）。详细步骤、环境变量与验证清单见 [docs/DEPLOY.md](docs/DEPLOY.md)。

> `deploy/docker-compose.yml`、`backend/Dockerfile`、`frontend/Dockerfile`、`frontend/nginx.conf` 由成员 F 按 [docs/DEPLOY.md](docs/DEPLOY.md) 提供。

---

## 六、演示账号

`h2` profile 下由 `DemoDataInitializer`（`@Profile("h2")`）在启动时自动创建，**密码统一为 `demo1234`**：

| 用户名 | 密码 | 昵称 | 头像 |
| --- | --- | --- | --- |
| `demo` | `demo1234` | 演示玩家 | `/assets/avatars/bot-01.svg` |
| `player1` | `demo1234` | 1号玩家 | `/assets/avatars/bot-02.svg` |
| `player2` | `demo1234` | 2号玩家 | `/assets/avatars/bot-03.svg` |
| `player3` | `demo1234` | 3号玩家 | `/assets/avatars/bot-04.svg` |
| `player4` | `demo1234` | 4号玩家 | `/assets/avatars/bot-05.svg` |

> H2 是内存库，**每次重启后端数据都会重置**，账号会被重新创建。`db/h2/data.sql` 已不再使用（演示账号必须由 `PasswordEncoder` 现场生成 BCrypt 哈希，写死在 SQL 里会失效）。

**MySQL / 生产模式没有预置账号**，注册即可：

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo1234","nickname":"演示玩家"}'
```

**MySQL 模式一次注册 4 个账号（bash）：**

```bash
for i in 1 2 3 4; do
  curl -s -X POST http://localhost:8080/api/auth/register \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"player$i\",\"password\":\"demo1234\",\"nickname\":\"$i 号玩家\"}"
  echo
done
```

**MySQL 模式一次注册 4 个账号（Windows PowerShell）：**

```powershell
1..4 | ForEach-Object {
  $b = @{ username = "player$_"; password = "demo1234"; nickname = "$_ 号玩家" } | ConvertTo-Json
  Invoke-RestMethod -Uri "http://localhost:8080/api/auth/register" -Method Post `
    -ContentType "application/json" -Body $b | Out-Null
}
Write-Host "4 个账号已注册"
```

---

## 七、4 个浏览器窗口完整演示（本轮验收主路径）

**这是本轮交付的核心验收动作**：4 个真人玩家，全程不使用 AI，完整玩完一局。

### 7.0 准备

| 项 | 要求 |
| --- | --- |
| 后端 | 已按方式 ① 启动，`curl http://localhost:8080/api/health` 返回 `status: UP` |
| 前端 | 已 `pnpm dev`，浏览器能打开 http://localhost:5173 |
| 窗口 | **4 个**。每个窗口用**不同的账号**（`demo` / `player1` / `player2` / `player3`），并且**至少 2 个必须用无痕/隐私模式** |
| 为什么要无痕 | 两个原因：① 同源窗口共享 `localStorage`，会变成同一个登录态；② 后端有**单会话强制**——同一账号在别处登录会让先登录的那端立刻下线 |
| 账号 | `demo`、`player1`、`player2`、`player3`（密码均 `demo1234`） |

> 推荐组合：Chrome 普通窗口 + Edge 普通窗口 + Chrome 无痕 + Edge 无痕（4 个完全独立的存储）。

### 7.1 第 1 步：4 个窗口分别登录

| 窗口 | 浏览器 | 账号 | 密码 |
| --- | --- | --- | --- |
| 窗口 1 | 普通窗口 A | `demo` | `demo1234` |
| 窗口 2 | 普通窗口 B | `player1` | `demo1234` |
| 窗口 3 | 无痕窗口 A | `player2` | `demo1234` |
| 窗口 4 | 无痕窗口 B | `player3` | `demo1234` |

**✅ 预期**：每个窗口右上角出现绿色的 **「实时已连接」** 标签。

**❌ 若显示「连接中…」**：WebSocket 没连上，见第十一节的 FAQ Q4。

### 7.2 第 2 步：窗口 1 创建房间

1. 进入「游戏大厅」，点击「创建房间」。
2. 填写：

| 字段 | 值 |
| --- | --- |
| 房间名 | `答辩演示` |
| 房间密码 | 留空（也可填 `secret123` 演示密码房） |
| 人数 | **4** |
| 狼人数 | **1** |
| AI 数量 | **0**（保持默认） |
| 公开房间 | ✅ |

3. 提交后记下 6 位房间码（例如 `K7M2QP`）。

**✅ 预期**：自动进入房间页，座位表里只有「1 号 · 演示玩家」一个座位。

### 7.3 第 3 步：窗口 2/3/4 加入房间

在「游戏大厅」的房间列表里找到 `答辩演示` 点加入，或直接输入房间码加入。

**✅ 预期**：

- 窗口 1 的座位面板**自动出现** 2/3/4 号座位 —— **不需要刷新页面**，这证明 WebSocket 实时同步生效；
- 各窗口看到的「我的座位」依次是 2 / 3 / 4 号；
- 4 个窗口显示的人数都是 `4 / 4`。

### 7.4 第 4 步：其他玩家准备，房主开局

1. 窗口 2/3/4 分别点「准备」；房主无需准备。
2. **✅ 预期**：房主看到「已准备 X/3」实时同步到 `3/3`，「开始游戏」按钮变为可用。
3. 窗口 1（房主）点「开始游戏」。

**❌ 若除房主外有人未准备**：后端返回 **3007 还有玩家未准备：XX**，前端提示未准备者姓名；房主本人不计入准备校验。

### 7.5 第 5 步：核对「只看得到自己的身份」

开局会播放「正在发牌」特效（`GAME_START` → `/assets/roles/hidden-back.svg`）。

**✅ 预期**：

| 检查项 | 预期结果 |
| --- | --- |
| 自己的身份 | 显示一张角色卡（狼人 / 预言家 / 女巫 / 平民），并带技能说明 |
| 其他 3 个座位 | 显示**卡背** `/assets/roles/hidden-back.svg`，看不到身份 |
| F12 → Network → WS → 找 `GAME_STATE` 报文 | `players[].role` 字段**完全不存在**；只有 `me.role` 是自己的 |

> 这是「服务器权威 + 隐私裁剪」的直观验证。原理见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 第七节。

### 7.6 第 6 步：夜晚行动（默认 30 秒）

**4 人局的角色构成**：**1 狼人 + 1 预言家 + 1 女巫 + 1 平民**。

> 预言家与女巫是 P0 必选角色，`RoleAssigner.buildDeck()` 会**无条件**各发 1 张（常量 `REQUIRED_SPECIAL_ROLES = 2`），因此 4 人局也能完整演示「刀人 / 查验 / 用药」三条技能链。多出来的人全部补成平民。

| 你的身份 | 应看到的界面 | 操作 |
| --- | --- | --- |
| **狼人** | 「狼人猎杀」面板，目标列表里**没有自己** | 点一个好人座位 → 确认。若有两个狼，同伴有列表可见 |
| **预言家** | 查验面板，可选目标里**不含自己、不含已查验过的人** | 点一个目标 → 查验，结果只有自己能看到 |
| **女巫** | 解药 / 毒药 / 不用药三个按钮 + 药水可用状态 | 按需选择。有解药时需等狼人先刀人，才会出现「使用解药」按钮 |
| **平民** | 只有「天黑请闭眼」遮罩，**没有任何操作按钮** | 等待 |

**验证平民夜晚越权被拒**（用平民窗口的 Token）：

```bash
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $平民TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"WOLF_KILL","targetSeatId":2}'
```

**✅ 预期**：返回 `{"code":4004,"message":"你的角色不能执行该操作",...}`，且**游戏状态完全不变**。

**⏱ 超时兜底**：如果什么都不做，30 秒后系统自动结算——狼人随机刀一个好人、预言家随机验一个没验过的人、女巫空过。**对局永远不会卡死**。

### 7.7 第 7 步：天亮与讨论

1. **天亮**：播放「天亮了」特效（`DAWN` → `/assets/effects/dawn.svg`），或者平安夜提示。
   **✅ 预期**：4 个窗口看到**完全一致的死讯**（例如都是「昨晚死亡：3 号」）。
2. **讨论**（默认 40 秒）：按座位顺序依次发言。
   **✅ 预期**：**只有当前发言人的窗口有输入框**；其他窗口提示「轮到 X 号（昵称）发言」。
   **❌ 非当前发言人提交发言**：返回 `4002 还没轮到你发言`。

**用 curl 提交一条发言（当前发言人窗口的 Token）：**

```bash
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"SPEAK","speech":"我是 1 号，昨晚 3 号倒了，我怀疑 2 号。"}'
```

### 7.8 第 8 步：投票与放逐（默认 30 秒）

1. **✅ 预期**：4 个窗口实时看到票数变化（`votedCount` 与 `voteCounts`）。
2. 得票最高者被放逐，播放「投票放逐」特效并公布身份。
3. **平票则本轮无人出局**，前端提示「投票平票，本轮无人出局」。
4. **弃票**：点「弃票」按钮（前端走 `/app/room/{code}/vote-skip`），或：

```bash
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"action":"ABSTAIN"}'
```

**投票规则速查**（详见 [docs/GAME_RULES.md](docs/GAME_RULES.md)）：

| 规则 | 说明 |
| --- | --- |
| 最高票放逐 | 得票最多者出局 |
| 平票 | **无人出局**，直接进入下一轮 |
| 不能投自己 | 返回 `4006 目标不合法` |
| 不能重复投票 | 返回 `4009 你已经投过票了` |
| 超时未投 | 自动视为**弃票** |

### 7.9 第 9 步：进入下一轮，直到分出胜负

未分出胜负 → 回到第 6 步进入下一夜。

**胜负条件**：

| 条件 | 结果 |
| --- | --- |
| 狼人全部出局 | **好人阵营胜**（`winner=VILLAGE`，播放 `win-good.svg`） |
| 存活狼人数 ≥ 存活好人数 | **狼人阵营胜**（`winner=WEREWOLF`，播放 `win-werewolf.svg`） |

**✅ 最终验收**：

- 4 个窗口的胜负结果**完全一致**；
- 结算面板显示**全部 4 个玩家的真实身份**（`gameOver=true` 时才公开）；
- 顶部导航出现「再来一局」（`POST /api/game/reset`），点击后回到等待状态、除房主外的真人需要重新准备。

### 7.10 第 10 步：局内聊天（验证三频道可见性）

在聊天面板依次测试：

| 频道 | 操作 | 预期 |
| --- | --- | --- |
| **公共** | 任一窗口发一条 | 4 个窗口**都能看到** |
| **私聊** | 1 号私聊 3 号 | **只有 1 号和 3 号能看到**，2 号和 4 号看不到 |
| **狼人** | 狼人窗口切到狼人频道发言 | **只有狼人能看到**；好人切到该频道发消息会返回 `4004` |

**用 curl 验证狼人频道权限**（用平民账号）：

```bash
curl -s -X POST http://localhost:8080/api/chat/K7M2QP/send \
  -H "Authorization: Bearer $平民TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"我想偷看狼人频道","type":"WOLF"}'
```

**✅ 预期**：`{"code":4004,"message":"你的角色不能执行该操作: 你不是狼人，无法使用狼人频道",...}`

### 7.11 第 11 步：断线重连验证

1. 在窗口 3 按 **F5 刷新**。
2. **✅ 预期**：自动回到对局页面，倒计时、阶段、座位状态、聊天记录**全部恢复**（`roomStore.restoreCurrent()` + `ws.publish(WsSend.sync(...))`）。
3. 更强验证：在窗口 3 的 F12 → Network 面板里把网络改成 **Offline**，等标签变成「连接中…」，再改回 **Online**。
   **✅ 预期**：约 3 秒内自动重连（`reconnectDelay: 3000`），标签变回「实时已连接」，状态自动补齐。

### 一局最短路径速查

| 人数 | 狼人 | 好人 | 最快结束方式 |
| --- | --- | --- | --- |
| 4 | 1 | 1 预言家 + 1 女巫 + 1 平民 | 白天放逐狼 → 好人胜（约 2 轮） |
| 4 | 1 | 同上 | 狼连续刀中好人，直到存活狼数 ≥ 存活好人数 → 狼人胜 |
| 5 | 1 | 1 预言家 + 1 女巫 + 2 平民 | 平衡性最好；狼人上限是 2，但 2 狼时狼人极易获胜 |
| 6 | 2 | 1 预言家 + 1 女巫 + 2 平民 | 推荐的中等规模配置 |

> 狼人数上限公式：`1 ≤ w ≤ min(⌊(n-1)/2⌋, n-2)`——既要**少于总人数的一半**，又要**为预言家和女巫各留 1 个座位**。详见 [docs/GAME_RULES.md](docs/GAME_RULES.md) 第六节。

---

## 八、常见问题 FAQ

### Q1 后端启动报 `Port 8080 was already in use`

**原因**：8080 被别的进程占用。

**排查**：

Windows PowerShell：

```powershell
netstat -ano | Select-String ":8080"
Get-Process -Id <上面查到的PID>
```

Linux / macOS：

```bash
lsof -i :8080
ss -lntp | grep 8080
```

**解决**：杀掉占用进程，或换端口：

```powershell
$env:SERVER_PORT="8081"; mvn spring-boot:run
```

```bash
SERVER_PORT=8081 mvn spring-boot:run
```

⚠️ 换端口后前端代理也要改 `frontend/vite.config.ts` 里 `proxy['/api'].target` 与 `proxy['/ws'].target`。

---

### Q2 前端 5173 被占用

`vite.config.ts` 里写了 `strictPort: false`，Vite 会自动换到 5174、5175……**看终端输出的实际地址**即可。若必须固定，把 `strictPort` 改成 `true` 并释放端口。

---

### Q3 浏览器控制台报 CORS 错误 / 别人用自己的电脑连不上

**症状**：页面能打开，但登录失败、接口全部报错，或右上角一直「连接中…」。

**现在的默认行为（已经是"开箱可用"）**：

后端**自动放行本机所有网卡地址**，无需任何配置。只要别人访问的是"你这台机器的 IP"，就能直接连上。

排障第一步 —— 让后端告诉你该用哪个地址：

```bash
curl http://localhost:8080/api/health
```

返回里的 `localAddresses` 就是别人应该使用的地址，例如：

```json
{ "data": { "status": "UP", "localAddresses": ["203.0.113.45"] } }
```

**如果还是连不上**，按顺序排查：

1. **别人用的是不是你的局域网 IP**（不是 `localhost`，也不是 `127.0.0.1`）；
2. **防火墙**是否放行了 8080 与 5173（见 [docs/NETWORK_DEPLOY.md](docs/NETWORK_DEPLOY.md) 第二节 A3）；
3. **前端是否用 `--host` 启动**（`pnpm dev --host`），否则 Vite 只监听回环地址；
4. 仍不行就显式指定来源：

```powershell
# 精确指定（推荐，多个用逗号分隔）
$env:WEREWOLF_CORS_ORIGINS="http://203.0.113.45:5173,http://localhost:5173"
# 或者临时放行全部来源（仅演示用，别长期开着）
$env:WEREWOLF_CORS_ORIGINS="*"
```

```bash
export WEREWOLF_CORS_ORIGINS="http://203.0.113.45:5173,http://localhost:5173"
```

> **为什么不能只写 `http://192.168.*.*`**：很多校园网 / 企业网的地址并不在 RFC1918 私网段内。
> 本项目开发机当时的局域网地址就是 `203.0.113.45` 这种**公网段地址**（示例地址，实际请填你自己的），
> 早期只用私网通配的白名单直接导致 403。这也是现在改为"自动识别本机网卡"的原因。
>
> 完整的四种联机方式（局域网 / 内网穿透 / 云服务器 / 端口映射）见 **[docs/NETWORK_DEPLOY.md](docs/NETWORK_DEPLOY.md)**。

---

### Q4 WebSocket 连不上（右上角一直「连接中…」）

按顺序检查：

1. **Token 是否有效**：退出登录重新登录。Token 存在 `localStorage` 的 `werewolf_token` 键里。
2. **握手地址是否正确**：F12 → Network → WS，应看到 `ws://localhost:5173/ws?token=...`（开发期经 Vite 代理）。
3. **后端 `/ws/**` 是否放行**：`SecurityConfig` 里已 `permitAll()`，正常不会拦。
4. **代理是否生效**：确认 `pnpm dev` 是**在 `frontend/` 目录**启动的，`vite.config.ts` 里 `/ws` 的 `ws: true` 才起作用。
5. **换 SockJS 兜底**：在 `frontend/.env.local` 里写 `VITE_USE_SOCKJS=true`，重启 `pnpm dev`。此时走 `http://host/ws` 的 SockJS 传输（后端注册了 `withSockJS()`）。
6. **企业代理 / VPN**：部分公司代理会拦截 WebSocket 升级请求，换网络或用 SockJS。

调试用控制台片段见 [docs/WEBSOCKET.md](docs/WEBSOCKET.md) 最后一节。

---

### Q5 前端白屏 / `Failed to resolve import "@/views/GameView.vue"`

> **当前仓库的页面已经全部交付**（`src/views/` 下 6 个页面、`src/components/` 下 7 个组件、`index.html`、`nginx.conf` 都存在）。如果你看到这个报错，通常是**本地依赖没装好**或**拉了别人的分支**，按下面的顺序排查。

**排查顺序**：

1. **依赖是否装全**：

   ```bash
   cd werewolf-game/frontend
   pnpm install
   pnpm typecheck        # 会明确指出缺失的文件或类型错误
   ```

2. **是否漏拉了文件**：`git status` 看 `frontend/src/views/` 是否为空；`frontend/index.html` 必须存在（Vite 的入口 HTML，缺了会直接白屏且终端报 `Could not resolve entry module "index.html"`）。
3. **浏览器控制台**：F12 看是 `404`（文件不在）还是 `SyntaxError`（构建/编译问题）。
4. **硬刷新**：`Ctrl + Shift + R` 清掉旧的 hash 资源缓存。

**解决**：

- 依赖问题 → `rm -rf node_modules && pnpm install`；
- 文件缺失 → 按 [docs/TEAM_PLAN.md](docs/TEAM_PLAN.md) 第 3.2 节的文件归属表找对应负责人补齐；
- 如果只是答辩现场应急，可以先用 `pnpm preview`（`http://localhost:4173`，跑已构建的 `dist/`）绕过开发服务器。

后端的接口契约（[docs/API.md](docs/API.md)）与数据形状（`src/types/index.ts` 已全部定义）是固定的，前端只需按类型渲染。

---

### Q6 Maven 首次下载依赖特别慢 / 卡住

**原因**：默认从 Maven Central 拉取，国内网络慢。

**解决**：在 `~/.m2/settings.xml` 里加阿里云镜像：

```xml
<mirrors>
  <mirror>
    <id>aliyun</id>
    <name>aliyun maven</name>
    <url>https://maven.aliyun.com/repository/public</url>
    <mirrorOf>central</mirrorOf>
  </mirror>
</mirrors>
```

Windows 路径：`C:\Users\<你的用户名>\.m2\settings.xml`。

**离线演示兜底**：提前在有网环境跑一次

```bash
cd werewolf-game/backend
mvn -q dependency:go-offline
mvn -q clean package -DskipTests
```

之后可用 `java -jar target/werewolf-server.jar --spring.profiles.active=h2` 直接启动，**完全不依赖 Maven 与网络**。

---

### Q7 `mvn spring-boot:run` 报 `ClassNotFoundException: com.werewolf.WerewolfApplication`

**原因**：项目路径含中文（例如 `E:\ds工作1-软工\werewolf-game`）。`spring-boot:run` 在启动子进程时会把 classpath 拼成一长串参数，中文路径在部分 Windows 环境下会被错误解码 / 截断，于是 Spring Boot 找不到启动类。**这不是代码问题，编译其实是成功的**（`target/classes` 里有正确的 `.class`）。

**本机实测可靠的解决方式**：绕开 `spring-boot:run`，先打包再用 `java -jar` 启动（`java -jar` 只传一个文件路径，不受这个坑影响）：

```powershell
cd werewolf-game\backend
mvn -DskipTests package
java -jar target\werewolf-server.jar --spring.profiles.active=h2
```

```bash
cd werewolf-game/backend
mvn -DskipTests package
java -jar target/werewolf-server.jar --spring.profiles.active=h2
```

看到 `Started WerewolfApplication` 即成功。`target/werewolf-server.jar` 是**可执行 fat jar**（`pom.xml` 里 `<finalName>werewolf-server</finalName>` + `spring-boot-maven-plugin`），可以拷到任意目录、任意机器上离线运行。

**其它可选做法**（按可靠性排序）：

1. 把工程移到**纯英文路径**（例如 `D:\projects\werewolf-game`）后再用 `mvn spring-boot:run`；
2. 用 `mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8"`（对部分环境有效，不保证）；
3. 直接用 IDE（IDEA / VS Code）运行 `WerewolfApplication` 的 `main` 方法。

> ⚠️ 部署到 Docker / Linux 时不存在这个问题（镜像内路径是 `/build`、`/app`）。见 [docs/DEPLOY.md](docs/DEPLOY.md)。

---

### Q8 `pnpm install` 报 `esbuild binary not found`

**原因**：pnpm v10 起默认不执行依赖的安装脚本，esbuild 的原生可执行文件不会落地。

**解决**：仓库里已有 `frontend/pnpm-workspace.yaml`：

```yaml
allowBuilds:
  esbuild: true
  vue-demi: true
```

若仍报错，执行：

```bash
cd werewolf-game/frontend
pnpm rebuild esbuild
```

---

### Q9 `werewolf.jwt.secret 至少需要 32 字节`

**原因**：`JwtTokenService` 构造时校验密钥长度，过短直接启动失败（这是有意的安全约束）。

**解决**：换一个 ≥ 32 字节的密钥：

```powershell
$env:WEREWOLF_JWT_SECRET="werewolf-prod-secret-key-change-me-2024-abcdefgh"
```

```bash
export WEREWOLF_JWT_SECRET="werewolf-prod-secret-key-change-me-2024-abcdefgh"
```

> ⚠️ 生产环境**必须**覆盖默认密钥，否则任何人拿到仓库里的默认值都能伪造 Token。

---

### Q10 多个窗口/设备共用一个账号，结果所有人变成同一个玩家

**现象**：4 个窗口都登录 `demo`，进入房间后只看到 **1 个座位**被占用，或者所有人看到的是同一份身份、操作互相覆盖。

**根本原因**（两层，缺一不可）：

1. **前端层**：Token 存在 `localStorage`，同源窗口共享 → 多个标签页其实是同一个登录态；
2. **后端层**（更本质）：**玩家的身份与座位是按 `userId` 绑定的**，不是按设备绑定。
   同一个账号在世界里只对应"一个玩家"，所以无论多少台设备登录，
   都只会占用**同一个座位**。

**本项目已经做了单会话强制（F-09 修复）**：

- 同一账号在第二台设备登录后，**第一台设备的登录立即失效**，会被踢回登录页并提示
  "该账号已在其他设备登录，你已下线。多人游戏请各自注册账号。"
- 因此现在**不可能**再出现"多人共用一个玩家"的情况——被顶下线的一端无法继续操作。

**正确做法**：

| 场景 | 做法 |
| --- | --- |
| 4 个人用 4 台电脑 | 每人**各自注册一个账号**（这才是正常玩法） |
| 1 台电脑开 4 个窗口测试 | 用 4 个**互相独立**的浏览器存储：Chrome 普通 + Chrome 无痕 + Edge 普通 + Edge 无痕；或 4 个不同的浏览器 |
| 想快速演示 | 直接用 4 个演示账号 `demo` / `player1` / `player2` / `player3`（密码 `demo1234`），一窗一个 |

> 这也是第七节强调「至少 2 个用无痕」的原因，登录页上也做了同样的提示。

---

### Q11 游戏卡住不动了

**排查顺序**：

1. 看后端日志有没有 `房间 tick 异常`（`RoomRuntimeScheduler` 会捕获并打印）；
2. `curl http://localhost:8080/api/health` 看 `activeRooms` 是否正常；
3. 检查阶段倒计时：每个阶段都有默认时长（夜 30s / 天 10s / 讨论 40s / 投票 25s），到 0 会自动 `autoCompleteCurrentPhase()` + `nextPhase()`，**不会永久卡住**；
4. 若确实卡住，用 `GET /api/game/state` 看当前 `phase`，再对照 [docs/GAME_RULES.md](docs/GAME_RULES.md) 的超时兜底表；
5. 房主可用「再来一局」（`POST /api/game/reset`）重置到等待状态。

---

### Q12 开局时提示「还有玩家未准备」（3007）

**原因**：这是**设计行为**。AI 创建时自带 `ready=true`，房主作为开局发起人无需准备；其余真人必须准备。

**解决**：请提示中列出的玩家点「准备」。4 真人局中，另外 3 人全部准备后，房主看到「已准备 3/3」，即可点「开始游戏」。

---

### Q13 H2 模式下重启后端，账号和战绩都没了

**原因**：H2 是**内存库**（`jdbc:h2:mem:werewolf`），进程结束数据即释放。这是「零依赖演示」模式的预期行为。

**解决**：需要持久化就用方式 ② 的 MySQL，或把 H2 改成文件模式（把 `application-h2.yml` 的 url 改成 `jdbc:h2:file:./data/werewolf;MODE=MySQL`）。

---

### Q14 端口/地址速查表

| 服务 | 地址 | 配置位置 |
| --- | --- | --- |
| 后端 REST + WS | `http://127.0.0.1:8080` | `server.port` / `SERVER_PORT` |
| 原生 WebSocket | `ws://127.0.0.1:8080/ws?token=<JWT>` | `WsDestinations.ENDPOINT` |
| 前端开发服务器 | `http://localhost:5173` | `vite.config.ts` → `server.port` |
| 前端预览（生产构建） | `http://localhost:4173` | `package.json` → `preview` |
| 健康检查 | `GET /api/health` | `UserController.health()` |

---

## 九、验收标准对照表

对照方案第十八节的 13 条验收标准：

| # | 验收标准 | 状态 | 实现位置（文件 / 类 / 方法） | 如何验证 |
| --- | --- | --- | --- | --- |
| 1 | 用户可以注册、登录、退出 | **本轮交付** | `auth/AuthController.register/login/logout`、`auth/AuthService`、前端 `LoginView.vue` | `POST /api/auth/register` 返回 `code=0` 与 `token`；重复用户名返回 `2000`；错误密码返回 `2003` |
| 2 | 登录状态在刷新后保持 | **本轮交付** | `frontend/src/stores/auth.ts`（`localStorage` 存 `werewolf_token`）、`router/index.ts` 全局守卫 | 登录后按 F5，仍在原页面而不是跳回登录页 |
| 3 | 可以创建房间并自定义人数与角色数量 | **本轮交付** | `room/RoomController.create`、`RoomService.createRoom`、`RoomService.validateConfig` | `POST /api/rooms` 传 `maxPlayers=4, werewolfCount=1` 成功；传 `werewolfCount=3, maxPlayers=4` 返回 `3008` |
| 4 | 可以通过房间码加入房间，支持房间密码 | **本轮交付** | `RoomService.joinRoom`（BCrypt `passwordEncoder.matches`） | `POST /api/rooms/join` 正确密码成功；错误密码返回 `3001` |
| 5 | 房间满员 / 已开局时不能加入 | **本轮交付** | `RoomService.joinRoom`（`runtime.isFull()`、`Room.Status.PLAYING` 判断） | 满员返回 `3002`；游戏进行中返回 `3003` |
| 6 | 玩家准备状态实时同步，除房主外全部准备后可开局 | **本轮交付** | `RoomService.setReady`、`RoomWsController.ready`、`RoomView.vue`（`readyAmongPlayers` / `needReadyCount`） | 4 真人局中另外 3 人点准备，房主看到「已准备 X/3」实时变化；有未准备者时开局返回 `3007` |
| 7 | 夜晚狼人刀人、预言家查验、女巫用药正确结算 | **本轮交付** | `WerewolfGameEngine.wolfKill/seerCheck/witchHeal/witchPoison`、`resolveNight` | 4 人局走一遍夜晚，天亮死讯与女巫是否用药一致；平民窗口在夜晚无操作按钮，且提交 `WOLF_KILL` 返回 `4004` |
| 8 | 白天按顺序发言，投票放逐最高票 | **本轮交付** | `WerewolfGameEngine.buildSpeakOrder/speak/vote/resolveVoting` | 讨论阶段只有当前发言人能发言（其他人得 `4002`）；投票最高票者出局，平票无人出局（`tie=true`） |
| 9 | 胜负判定正确并公布全员身份 | **本轮交付** | `WerewolfGameEngine.checkWinnerInternal`、`GameViewProjector.project`（`over` 时才填 `role`） | 狼人全灭 → `winner=VILLAGE`；狼数 ≥ 好人数 → `winner=WEREWOLF`；结束后 `players[].role` 全部非空 |
| 10 | 多个浏览器实时联机，状态一致 | **本轮交付** | `config/WebSocketConfig`、`websocket/GameEventMessenger`、`room/runtime/SimpGameBroadcaster` | 4 窗口同一时刻的 `phase`、死讯、票数完全一致 |
| 11 | 局内聊天：公共、私聊、狼人频道 | **本轮交付** | `websocket/ChatService.send`、`ChatService.broadcast`、`ChatService.history` | 公共消息 4 窗口可见；私聊第 3 个窗口看不到；好人发 WOLF 频道返回 `4004` |
| 12 | AI NPC 能发言、投票、执行角色技能；AI 不可用时自动降级 | ⏸ **可选增强，默认关闭，开启后可用** | `ai/AiService.decide`、`ai/LlmAgent`、`ai/RuleAgent`、`ai/AiJsonParser` | 开启后建 `aiCount=3` 的房间可直接开局；默认关闭时 `GET /api/health` 的 `aiProvider` 显示「规则 AI（AI 总开关关闭）」 |
| 13 | 至少一个 AI 视觉特效正常播放 | ⏸ **可选增强，默认关闭，开启后可用** | `frontend/src/components/EffectManager.vue`、`frontend/src/styles/effects.css`、`frontend/public/assets/effects/*.svg` | **特效播放本身不依赖 AI 开关**：任意一局都会播放发牌/天亮/投票/结算特效（素材为本地预生成 SVG）；「AI 生成素材」这一环节属可选增强 |

**本轮交付的 11 条**：第 1~11 条，全部通过。
**可选增强的 2 条**：第 12、13 条，代码完整保留，默认关闭，开启后可用（开启方式见第十节）。

**补充的非功能性验收**：

| 项 | 目标 | 验证方法 |
| --- | --- | --- |
| 服务器权威 | 客户端无法伪造身份或跳过规则 | 用平民账号提交 `{"action":"WOLF_KILL","targetSeatId":2}`，必须返回 `4004` |
| 隐私不泄露 | 对局中任何报文不含他人身份 | F12 → Network → WS，检索 `"role"`，只应出现在自己的 `me.role` 中 |
| 对局不卡死 | 任何阶段超时都会自动推进 | 4 人局全程不操作，观察是否自动从 `NIGHT` 走到 `END` |
| 零外部依赖可运行 | 断网 + 无 API Key 仍可完整对局 | 断网后按方式 ① 启动，4 个真人窗口跑完整一局 |
| 默认零外部请求 | 默认配置不发起任何 LLM 请求 | `werewolf.ai.enabled=false` 时 `GET /api/health` 返回 `llmEnabled:false`；后端日志中没有出站 HTTP 调用 |

---

## 十、可选增强：AI NPC（默认关闭）

> ⚠️ **本节描述的能力在本轮交付中默认关闭**。`werewolf.ai.enabled` 默认 `false`，房间创建时 `allowAi` 默认 `false`、`aiCount` 默认 `0`。**系统默认不会向任何外部 LLM 发起请求，也不需要 API Key。** 下面的内容是给「需要演示 AI 时」准备的操作说明，实现细节见 [docs/AI_DESIGN.md](docs/AI_DESIGN.md)。

### 10.1 当前默认状态

| 配置项 | 位置 | 默认值 | 效果 |
| --- | --- | --- | --- |
| `werewolf.ai.enabled` | `application.yml` | **`false`** | AI 总开关关闭；`AiService.providerDescription()` 返回「规则 AI（AI 总开关关闭）」 |
| `CreateRoomRequest.allowAi` | `room/dto/CreateRoomRequest.java` | **`false`** | 房间不允许 AI 加入 |
| `CreateRoomRequest.aiCount` | `room/dto/CreateRoomRequest.java` | **`0`** | 建房时不创建任何 AI 座位 |

**验证默认状态：**

```bash
curl -s http://localhost:8080/api/health
# data.aiProvider == "规则 AI（AI 总开关关闭）"
# data.llmEnabled == false
```

### 10.2 三种运行模式

| 模式 | 触发条件 | 行为 | 适用场景 |
| --- | --- | --- | --- |
| **完全关闭**（默认） | `werewolf.ai.enabled=false` | 不会创建 AI 座位；即使创建了，AI 也不行动（由超时兜底顶替） | **本轮交付状态** |
| **规则 AI** | `enabled=true`，`api-key` 留空 | 完全本地决策，零外部请求、零成本 | 想演示 AI 但**断网/不想花钱** |
| **LLM + 规则兜底** | `enabled=true`，配置了 `api-key` | 优先调用大模型，超时/格式错/异常自动降级到规则 AI | 展示 AI 发言质量 |

### 10.3 开启规则 AI（零成本、无需联网）

只需打开总开关：

Windows PowerShell：

```powershell
$env:WEREWOLF_AI_ENABLED="true"
cd werewolf-game\backend
mvn spring-boot:run
```

Linux / macOS bash：

```bash
export WEREWOLF_AI_ENABLED=true
cd werewolf-game/backend && mvn spring-boot:run
```

然后在**创建房间时**把 `allowAi` 设为 `true`、`aiCount` 设为想要的 AI 数量（例如 3），一个人也能开局：

```bash
curl -s -X POST http://localhost:8080/api/rooms \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"roomName":"AI 练习房","maxPlayers":4,"werewolfCount":1,"aiCount":3,"allowAi":true,"publicRoom":false}'
```

`GET /api/health` 此时返回 `aiProvider: "规则 AI（未配置 API Key，零外部依赖）"`。

规则 AI 的行为（`ai/RuleAgent.java`）：

- **狼人**：给公开发言多的座位打威胁分，选最高分者击杀，避开狼同伴；
- **预言家**：随机查验没查过的座位；
- **女巫**：第 1 晚必救；后续有查验信息时 50% 概率救；只在自己查到狼或在残局（存活 ≤ 4）时才用毒；
- **投票**：好人投「被公开怀疑最多」的座位，狼人不投同伴；
- **发言**：按身份模板拼一段带理由的发言（预言家会报查验结果，狼人会假装好人）。

### 10.4 接入真实大模型

任何 **OpenAI 兼容 `/chat/completions`** 的服务都能直接用：

| 供应商 | `WEREWOLF_AI_BASE_URL` | `WEREWOLF_AI_MODEL` | 备注 |
| --- | --- | --- | --- |
| DeepSeek（默认） | `https://api.deepseek.com` | `deepseek-chat` | 默认值，最省事 |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` | 需 `/v1` 后缀 |
| 本地 Ollama | `http://localhost:11434/v1` | `qwen2.5:7b` | 需 `/v1` 后缀，无需 Key |

Windows PowerShell：

```powershell
$env:WEREWOLF_AI_ENABLED="true"
$env:WEREWOLF_AI_API_KEY="sk-你的密钥"
$env:WEREWOLF_AI_BASE_URL="https://api.deepseek.com"
$env:WEREWOLF_AI_MODEL="deepseek-chat"
cd werewolf-game\backend
mvn spring-boot:run
```

Linux / macOS bash：

```bash
export WEREWOLF_AI_ENABLED=true
export WEREWOLF_AI_API_KEY=sk-你的密钥
export WEREWOLF_AI_BASE_URL=https://api.deepseek.com
export WEREWOLF_AI_MODEL=deepseek-chat
cd werewolf-game/backend && mvn spring-boot:run
```

配置成功后 `GET /api/health` 的 `aiProvider` 会变成：

```
LLM(deepseek-chat @ https://api.deepseek.com) + 规则 AI 兜底
```

### 10.5 成本控制配置项

| 配置项 | 默认值 | 作用 |
| --- | --- | --- |
| `werewolf.ai.max-calls-per-game` | `120` | 单局 LLM 调用上限，超出后整局强制走规则 AI |
| `werewolf.ai.max-tokens` | `400` | 单次回复上限 |
| `werewolf.ai.timeout-seconds` | `12` | 单次请求超时，超时立即降级 |
| `werewolf.ai.temperature` | `0.9` | 发言多样性 |
| `werewolf.ai.min-think-millis` / `max-think-millis` | `900` / `2600` | 拟人化思考延迟区间，避免 AI 秒回 |

**省钱建议**：演示时房间 AI 数量控制在 2~3 个；`max-calls-per-game` 调到 `40` 也足够跑完一局。

### 10.6 关闭 AI 后系统是否受影响？

**完全不受影响。** 理由：

1. 房间默认 `aiCount=0`，没有 AI 座位需要行动；
2. 即使有 AI 座位，`RoomRuntime.runDueAiActions()` 会走 `AiService.decide()` → `RuleAgent`（纯本地计算），再交给 `WerewolfGameEngine.applyAction()` 校验——**AI 与真人走完全相同的规则路径**；
3. AI 从头到尾只产出「建议行动 + 发言」，**不能修改任何游戏状态**（详见 [docs/AI_DESIGN.md](docs/AI_DESIGN.md) 第一节的红线）；
4. 视觉特效（`EffectManager.vue`）播放的是**本地预生成 SVG 素材**，与 AI 开关无关，任何一局都会正常播放。

---

## 十一、文档索引

| 文档 | 面向谁 | 解决什么问题 |
| --- | --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 开发者、答辩评委 | 系统怎么分层、服务器权威怎么落地、为什么选 STOMP、状态存哪里、并发模型上限、隐私裁剪链路 |
| [docs/API.md](docs/API.md) | 前后端开发者、测试 | 每个 REST 接口的入参出参、全部错误码、可直接执行的 curl |
| [docs/WEBSOCKET.md](docs/WEBSOCKET.md) | 前后端开发者 | STOMP 订阅/发送地址全表、报文结构、GAME_EVENT 事件表、断线重连、控制台测试片段 |
| [docs/GAME_RULES.md](docs/GAME_RULES.md) | 全员、答辩评委 | 角色技能、状态机、夜晚结算顺序、投票规则、胜负条件、超时兜底、4 真人示例对局推演 |
| [docs/AI_DESIGN.md](docs/AI_DESIGN.md) | 成员 E、答辩评委 | **可选增强**：AI 的红线、决策链路、Prompt 全文、JSON 校验规则、规则 AI 策略、成本控制、换模型方法 |
| [docs/DATABASE.md](docs/DATABASE.md) | 成员 B、运维 | ER 图、五张表字段说明、索引设计、内存态与持久态边界、运维 SQL |
| [docs/TEAM_PLAN.md](docs/TEAM_PLAN.md) | 六人小组 | 分工与文件归属、六周计划（第 5 周标注为可选/可裁剪）、生死线清单、Git 分支与 PR 规范、风险登记 |
| [docs/DEPLOY.md](docs/DEPLOY.md) | 成员 F、运维 | 本地/生产部署、服务器规格与成本、环境变量清单、Nginx 配置、上线检查、排障手册 |
| [docs/TEST_PLAN.md](docs/TEST_PLAN.md) | 全员、答辩评委 | 测试分层、自动化与手工用例、13 条验收对照、性能测试、10 分钟纯基础功能答辩演示脚本；当前后端 `mvn test` 为 7 类 / 70 例全通过 |
| [PROJECT_HANDBOOK.md](PROJECT_HANDBOOK.md) | 六人小组 | 方案条目 → 代码位置对照表、统一 AI 编程提示词 |

---

## 十二、许可与致谢

课程项目，仅供学习交流。`frontend/public/assets/` 下的全部 SVG 素材均为本项目原创生成，不含任何第三方版权素材。
