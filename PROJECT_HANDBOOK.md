# 项目落地方案 · 对照与分工（Project Handbook）

> **需求更新 2026-10-27**：本文保留早期狼人杀交付记录。当前产品范围与后续顺序以三份新 Word 需求及 [啾啾阶段 0 审计](docs/JIUJIU_PHASE0_AUDIT.md) 为准；旧范围中舍弃的社交、表白墙和语音已进入新规划。
>
> 本文档面向六人开发小组，解决一个问题：**把《基于 AI 增强的多人在线狼人杀系统_项目方案与六人开发实施方案》逐条落地，并说明每一条现在落在仓库的哪个位置、由谁负责、怎么验证。**
>
> 与 `docs/TEAM_PLAN.md` 的区别：`TEAM_PLAN.md` 讲"怎么协作"，本文档讲"方案条目 → 代码位置"的对照关系与验收口径。

---

## 一、总体结论

| 维度 | 方案要求 | 当前状态 |
| --- | --- | --- |
| 产品定位 | 多人在线狼人杀，AI 为增强而非必需依赖 | ✅ 已实现，AI 可通过 `werewolf.ai.enabled=false` 完全关闭，规则 AI 零外部依赖 |
| 架构 | 单体 Spring Boot，同进程提供 REST + WebSocket | ✅ 已实现 |
| 服务器权威 | 客户端只能发请求，不能改身份/死亡/胜负 | ✅ 由 `WerewolfGameEngine` 单点收口 + `GameViewProjector` 隐私裁剪保证 |
| 六周周期 | 第 4 周跑通多人完整对局 | ✅ 已具备（H2 零依赖模式下一个命令即可演示 4 人局） |
| 不引入 | 微服务 / Kafka / RabbitMQ / WebRTC | ✅ 全部未引入（已核对 `backend/pom.xml`） |

---

## 二、P0 / P1 / P2 功能对照表

### P0 必须完成

| 方案条目 | 实现位置 | 验证方式 |
| --- | --- | --- |
| 注册、登录、退出 | `auth/AuthController.java`、`auth/AuthService.java`、`config/SecurityConfig.java`、前端 `views/LoginView.vue` | `POST /api/auth/register`、`/api/auth/login`、`/api/auth/logout` |
| 创建 / 加入 / 退出房间 | `room/RoomController.java`、`room/RoomService.java` | `POST /api/rooms`、`POST /api/rooms/join`、`POST /api/rooms/{code}/leave` |
| 房间密码与最大人数 | `rooms.password`（BCrypt）、`rooms.max_players` | 创建房间时传 `password` / `maxPlayers`，错误密码返回 3001 |
| 玩家准备、房主开始 | `RoomService.setReady` / `RoomService.startGame` | 未准备时开局返回 3007 |
| 角色分配、夜晚、白天、讨论、投票、胜负 | `game/engine/WerewolfGameEngine.java`、`game/domain/RoleAssigner.java` | 单元测试 `WerewolfGameEngineTest` + 手工 4 浏览器对局 |
| 基础角色：狼人、预言家、女巫、平民 | `game/domain/RoleType.java` | `GET /api/game/meta` 返回 4 个角色 |
| 多人浏览器实时联机 | `config/WebSocketConfig.java`、`websocket/*` | 4 个浏览器窗口同房间实时同步 |

### P1 主要特色

| 方案条目 | 实现位置 | 说明 |
| --- | --- | --- |
| 自定义房间：人数、角色数量、AI 数量 | `room/dto/CreateRoomRequest.java`、`RoomService.validateConfig` | 人数 4~12，狼人数 1..min(⌊(n-1)/2⌋, n-2)，开局要求实际入座 ≥ 4，AI 数 0..n-1 |
| 局内公共聊天与私聊 | `websocket/ChatService.java`、`websocket/ChatController.java` | PUBLIC / PRIVATE / WOLF 三频道，私聊仅双方可见 |
| WebSocket 实时同步 | `websocket/GameEventMessenger.java`、`room/runtime/SimpGameBroadcaster.java` | 公开状态广播 + 私有状态按座位投递 |
| AI NPC：发言、分析、投票、角色行动 | `ai/AiService.java`、`ai/LlmAgent.java`、`ai/RuleAgent.java` | LLM 优先，任何失败自动降级规则 AI |

### P2 加分项

| 方案条目 | 实现位置 | 说明 |
| --- | --- | --- |
| AI 生成的角色技能动画 / 视觉特效 | `frontend/src/components/EffectManager.vue` + `frontend/public/assets/effects/*.svg` + `frontend/src/styles/effects.css` | 「预生成素材 + 前端播放」，运行时不产生生成成本 |
| 更多狼人杀角色 | `RoleType` 枚举 + `RoleAssigner` + 前端图标表 | 当前 4 个角色，扩展路径见 `docs/ARCHITECTURE.md` |
| AI 生成简单游戏素材 | `frontend/public/assets/roles/*.svg`（4 张角色卡 + 图标 + 圆形标记）、`avatars/bot-01~12.svg`、`brand/logo.svg` | 全部原创 SVG，已本地化，**不依赖任何外链** |
| 游戏记录 / 排行榜 | `game_records` 表 + `RoomService.saveGameRecord` + 前端 `views/RecordsView.vue` | 每局结束落库，含 `detail_json` 复盘数据 |

### 明确舍弃（已确认未实现，符合方案）

表白墙 / 好友社交平台 / 群组系统 / 独立剧本杀 / 语音视频 / WebRTC / 微服务 / Kafka / RabbitMQ / 复杂 Redis 架构 / 推荐系统。

---

## 三、技术栈落地核对

| 模块 | 方案要求 | 实际使用 | 位置 |
| --- | --- | --- | --- |
| 前端 | Vue 3 + TypeScript + Vite | vue 3.5、typescript 5.6、vite 5.4 | `frontend/package.json` |
| UI | Element Plus | element-plus 2.8+ | 同上 |
| 状态 | Pinia | pinia 2.2 | `frontend/src/stores/` |
| HTTP | Axios | axios 1.7 | `frontend/src/api/request.ts` |
| 后端 | Java 17/21 + Spring Boot | Java 17 目标字节码、Spring Boot 3.3.5 | `backend/pom.xml` |
| 认证 | Spring Security + JWT | spring-security + jjwt 0.12.6，HS256 无状态 | `auth/security/` |
| 数据库 | MySQL 8 + MyBatis-Plus | mybatis-plus 3.5.9；开发期可用 H2（MySQL 兼容模式） | `application-mysql.yml` / `application-h2.yml` |
| 实时 | WebSocket + STOMP | spring-boot-starter-websocket + 内存 SimpleBroker | `config/WebSocketConfig.java` |
| AI | LLM API | OpenAI 兼容 `/chat/completions`（DeepSeek / OpenAI / Ollama 均可） | `ai/ChatCompletionClient.java` |
| 协作 / 部署 | Git + Docker + Nginx | `deploy/`、`backend/Dockerfile`、`frontend/nginx.conf` | `deploy/README.md` |

---

## 四、六人分工 → 仓库映射

| 成员 | 方案职责 | 本仓库负责的目录 / 文件 | 主要交付物 |
| --- | --- | --- | --- |
| **成员 A** | 项目架构、房间系统、最终整合 | `backend/.../room/`、`backend/.../config/`、`backend/pom.xml`、`docs/API.md`、`docs/WEBSOCKET.md`、根 `README.md` | 房间全生命周期接口、统一 API/WS/DB 规范、最终整合 |
| **成员 B** | 用户、登录、JWT、数据库基础 | `backend/.../auth/`、`backend/.../user/`、`backend/.../common/`、`database/`、`docs/DATABASE.md` | 注册登录、JWT 鉴权、五张核心表、用户身份接口 |
| **成员 C** | WebSocket + 局内聊天 | `backend/.../websocket/`、`frontend/src/websocket/stomp.ts`、`frontend/src/stores/chat.ts`、`frontend/src/components/ChatPanel.vue` | STOMP 通道、三频道聊天、实时玩家状态、聊天测试 |
| **成员 D** | 狼人杀 GameEngine | `backend/.../game/`、`frontend/src/stores/game.ts`、`docs/GAME_RULES.md` | 状态机、角色分配、夜晚/白天、投票、胜负、游戏记录 |
| **成员 E** | AI NPC + AI 视觉素材 | `backend/.../ai/`、`frontend/public/assets/`、`frontend/src/components/EffectManager.vue`、`frontend/src/styles/effects.css`、`docs/AI_DESIGN.md` | LLM 接入、AI 发言/投票/行动、JSON 校验、规则 AI 兜底、素材与特效 |
| **成员 F** | Vue 前端 + UI + 部署测试 | `frontend/src/views/`、`frontend/src/components/`（除 EffectManager）、`frontend/src/styles/main.css`、`frontend/nginx.conf`、`deploy/`、`docs/DEPLOY.md`、`docs/TEST_PLAN.md` | 登录/大厅/房间/游戏界面、动画播放、Docker/Nginx、测试 |

---

## 五、六周计划 → 每周可交付物

| 周次 | 方案目标 | 本周结束时应能演示 | 验收命令 / 操作 |
| --- | --- | --- | --- |
| 第 1 周 | 项目初始化 | 前端能登录，后端返回 JWT，H2/MySQL 建表成功 | `mvn spring-boot:run -Dspring-boot.run.profiles=h2` + `pnpm dev` → 用 `demo/demo1234` 登录成功 |
| 第 2 周 | 房间系统 | 创建自定义房间、带密码加入、准备、房主开局 | `POST /api/rooms` 后 `GET /api/rooms/current` 能看到 4 个座位 |
| 第 3 周 | 狼人杀核心 | 夜晚→白天→讨论→投票→胜负 全流程（可先单人 + AI） | 创建 `aiCount=3` 的房间直接开局，AI 自动完成夜晚行动 |
| 第 4 周 | 多人联机 MVP（生死线） | 至少 4 个浏览器完整玩完一局 | 见下方「第 4 周生死线检查清单」 |
| 第 5 周 | AI 增强 | AI NPC 发言/投票、私聊、狼人频道、至少一个技能特效 | 无 API Key 时规则 AI 也能跑通；配置 Key 后发言更自然 |
| 第 6 周 | 收尾 | Bug 清零、UI 打磨、部署、测试报告、答辩材料 | `deploy/docker-compose.yml` 一键起全栈 |

### 第 4 周生死线检查清单

按顺序执行，**每一条都必须通过**：

- [ ] 启动后端（H2 模式）与前端，打开 4 个浏览器窗口（其中 2 个用隐私模式避免共享 localStorage）
- [ ] 4 个窗口分别登录 4 个不同账号（`demo` / `player1` / `player2` / `player3`，密码均 `demo1234`）
- [ ] 窗口 1 创建房间：4 人、1 狼、0 个 AI、公开
- [ ] 窗口 2/3/4 通过房间码或大厅列表加入，座位号依次为 2/3/4
- [ ] 3 位非房主玩家点「准备」，房主的「开始游戏」按钮变为可用
- [ ] 点击开始 → 4 个窗口都看到发牌特效，且**各自只看到自己的身份**
- [ ] 夜晚：狼人窗口能看到队友与刀人面板；平民窗口只能看到「天黑请闭眼」；预言家看到查验面板
- [ ] 天亮：4 个窗口看到完全一致的死讯
- [ ] 讨论：按座位顺序依次发言，当前发言人窗口才有输入框
- [ ] 投票：4 个窗口看到实时票数；得票最高者出局，身份公开
- [ ] 进入下一轮夜晚，重复直到弹出「游戏结束」结算面板
- [ ] 结算面板中 4 个窗口的胜负结果一致

**如果这一条线跑不通**：立即冻结 AI 动画、更多角色等扩展（对应方案第十九节的砍功能顺序）。

---

## 六、统一 AI 编程提示词（可直接复制给 AI 助手）

> 这是方案第十六节的落地版本，补充了本仓库的真实路径，减少 AI 改错文件。

```text
这是一个 6 人小组、约 6 周完成的软件工程课程项目。
项目：基于 AI 增强的多人在线狼人杀系统。
仓库根目录：werewolf-game/

技术栈固定（不要替换、不要升级大版本）：
  前端：Vue3 + TypeScript + Vite + Element Plus + Pinia + Axios + @stomp/stompjs
  后端：Java17 + Spring Boot 3.3.x + MyBatis-Plus + MySQL8（开发期可用 H2）
  鉴权：Spring Security + JWT（HS256，无状态）
  实时：WebSocket + STOMP（内存 SimpleBroker，禁止引入 RabbitMQ/Kafka）
架构：单体。禁止微服务、禁止 WebRTC、禁止引入未在 pom.xml/package.json 中的依赖。

模块边界（严格遵守，不要跨模块改文件）：
  auth/ user/ common/        -> 成员 B
  room/ + config/            -> 成员 A
  websocket/                 -> 成员 C
  game/（domain + engine）    -> 成员 D
  ai/                        -> 成员 E
  frontend/src/views|components|styles -> 成员 F
  frontend/src/stores|websocket|api    -> 由对应后端模块负责人同步维护

我现在只负责【具体模块/功能】。要求：
1. 不重写整个项目，只做局部增量修改；
2. 不修改其他成员负责的模块（必要时在回答里提出建议，而不是直接改）；
3. 遵守已有 API / 数据库 / WebSocket 协议（见 docs/API.md、docs/WEBSOCKET.md）；
4. 先解释设计，再写代码；
5. 列出修改/新增的文件清单；
6. 给出运行与测试步骤；
7. 游戏规则必须由后端 WerewolfGameEngine 决定，前端与 AI 都只能"请求"；
8. AI 只能提供建议行动或发言，不能直接修改游戏状态；
9. 新增接口必须同步更新 docs/API.md 与 docs/WEBSOCKET.md；
10. 任何会下发到客户端的对象，都必须经过 GameViewProjector 或显式裁剪，禁止直接序列化 GameState。
```

---

## 七、AI NPC 提示词（运行时的真实 Prompt）

方案第十七节给出的结构已在 `backend/src/main/java/com/werewolf/ai/AiPrompt.java` 中实现，实际发送给模型的内容如下（节选，完整内容见 `docs/AI_DESIGN.md`）：

**System Prompt 要点**

- 你是狼人杀中的一名 AI 玩家；
- 只能根据【公开信息】发言、投票、执行自己角色允许的夜晚行动；
- 绝对不能修改身份、决定死亡、决定胜负、编造自己没有的信息；
- 只输出一个 JSON 对象，不要 markdown 代码块：`{ "speech", "action", "targetId", "reason" }`。

**User Prompt 提供的上下文**

你的座位 / 你的身份 / （仅狼人）狼人同伴 / （仅预言家）自己的查验结果 / （仅女巫）药水状态 / 当前轮次与阶段 / 存活与出局名单 / 公开事件记录 / 历史公开发言 / 上一轮投票 / 本轮任务 / 允许的动作枚举 / 可选目标座位。

**服务端校验（AI 无法绕过的三道闸门）**

1. `AiJsonParser`：action 必须在允许集合内，targetId 必须在可选目标内，否则整条决策作废；
2. `AiService`：解析失败 / 超时 / 无 Key → 无条件切换到 `RuleAgent`；
3. `WerewolfGameEngine.applyAction`：与真人完全相同的规则校验（身份、阶段、存活、目标合法性），非法决策被拒绝且状态不变。

---

## 八、素材说明（可追溯、可替换）

`frontend/public/assets/` 下的全部素材均为**本项目原创生成的 SVG**，没有引用任何第三方版权素材：

| 素材 | 数量 | 生成方式 | 用途 |
| --- | --- | --- | --- |
| `roles/*-card.svg` | 4 | 参数化 SVG 生成（渐变 + 星点 + 角色剪影 + 技能说明） | 角色身份卡（竖版 512×720） |
| `roles/*-icon.svg` | 4 | 同上（方形） | 列表 / 徽章图标 |
| `roles/*-token.svg` | 4 | 同上（圆形） | 牌桌座位上的身份标记 |
| `roles/hidden-back.svg` | 1 | 同上 | 未公开身份时的卡背 |
| `effects/*.svg` | 8 | 同上（爪痕 / 透镜 / 药瓶 / 投票 / 黎明 / 胜利） | 技能与结算特效，由 `EffectManager.vue` 播放 |
| `avatars/bot-01~12.svg` | 12 | 参数化几何头像 | AI NPC 与默认用户头像 |
| `brand/logo.svg` | 1 | 同上 | 站点标识 |
| `table-felt.svg` | 1 | 同上 | 牌桌底纹 |

替换成美术同学画的图时，**只要保持文件名和目录不变**即可，前后端无需改代码；若要新增特效，在 `EffectManager.vue` 的 `META` 表里加一条映射即可。

---

## 九、交付过程中的缺陷修复记录

以下 16 个问题都是**在实现、验收与外部对照过程中真实发现并修复**的，可作为答辩时"我们做过测试与调试"的证据。每条都补充了回归测试。

| 编号 | 严重度 | 问题现象 | 根因 | 修复 |
| --- | --- | --- | --- | --- |
| **F-01** | 🔴 严重 | **夜晚永远不结算**：狼刀、查验、女巫用药全部返回成功，但阶段一直停在 NIGHT，直到 30 秒超时才推进 | `WerewolfGameEngine` 用一个布尔值 `nightResolved` 同时表示"行动是否收集完"和"夜晚是否已结算"。狼人行动后把它置为 true，女巫行动触发 `resolveNight()` 时被误判为"已结算"直接 return | 拆成两个字段：`nightActorsDone`（行动收齐）与 `nightSettled`（已结算）。回归用例：`NightResolveDebugTest#nightResolvesAfterAllRolesAct` |
| **F-02** | 🔴 严重 | **4 人局没有女巫**，解药/毒药机制完全无法演示（女巫是 P0 角色） | `RoleAssigner.buildDeck` 只在人数 `>= 5` 时才发女巫 | 预言家与女巫改为**恒定各 1 名**；狼人上限公式变为 `1..min(⌊(n-1)/2⌋, n-2)`；并强制**开局实际入座 ≥ 4**（否则 3 人局会出现"1 狼 + 2 神职、没有平民"的退化配置） |
| **F-03** | 🟠 中 | 前端读不到 `iAmHost` / `iAmReady`，房主按钮、准备状态判断失效 | Jackson 会把 `isIAmHost()` 序列化成 `hostViewer`（剥掉 is 前缀） | 改用 `isHostViewer()` / `isReadyViewer()` 并加 `@JsonProperty("iAmHost")` 显式固定字段名 |
| **F-04** | 🟠 中 | **H2 演示模式启动失败**：`'script' must not be null or empty` | `db/h2/data.sql` 只剩注释（空脚本），Spring 的 SQL 初始化器拒绝空脚本 | 删除该文件与 `data-locations` 配置，演示账号由 `DemoDataInitializer` 用 `PasswordEncoder` 现场生成 |
| **F-05** | 🟠 中 | `game_records.result` 文案**恒为"好人阵营胜利"**，狼人赢时也写错 | 代码拿中文字面量 `"狼人阵营"` 去比较，而 `state.winner` 实际存的是英文枚举名 `WEREWOLF`/`VILLAGE`，永不相等 | 改为比较 `RoleType.Camp.WEREWOLF.name()`，并抽出 `winnerText()` 统一文案 |
| **F-06** | 🟡 低 | 女巫出局后，夜晚必须白等满 30 秒超时才结算 | `maybeResolveNight()` 只在女巫的三个行动里被调用，狼刀/查验不调用 | 狼刀与查验也调用 `maybeResolveNight()`。回归用例：`NightResolveDebugTest#nightResolvesWithoutWitch`（7 人局，女巫出局后狼刀 + 查验立即结算） |
| **F-07** | 🟡 低 | 夜晚倒计时被意外重置，女巫已出局时夜晚可能被拉长到 60~90 秒 | `wolfKill/seerCheck` 返回 `success(nightActorsDone)`，上层把"行动收齐"误当成"阶段已切换"，于是调用 `syncPhaseSeconds()` 重置倒计时 | `GameActionResult` 拆出 `phaseAdvanced` 与 `actionProgressed` 两个语义，新增 `progressed(boolean)` 工厂方法。回归用例：`NightResolveDebugTest#wolfKillDoesNotAdvancePhase` |
| **F-08** | 🟡 低 | 讨论超时的"（发言超时，跳过）"会作为**真实发言**写入发言记录与 `game_records.detail_json`，污染复盘 | `autoCompleteCurrentPhase` 伪造了一条 AI 发言来推进发言顺序 | 新增专用事件 `SPEAK_SKIP`（前端只记日志、不入发言列表），直接推进 `speakCursor` |
| **F-09** | 🔴 严重 | **多台设备登录后，所有玩家共用一个账号**：4 个浏览器窗口各登录一次，4 个窗口全部落到**同一个座位**，看起来像"只有一个玩家" | 玩家的身份与座位是按 **`userId`** 绑定的。后端完全没有"同一账号只能单点在线"的概念；`RoomService.joinRoom` 里 `byUser(userId)` 命中后直接把重复加入当成"重连"返回，因此多个连接共享同一个 `PlayerState` | 引入**单会话强制**：`users.auth_version` 每次登录 +1，JWT 携带签发时的版本，服务端逐请求比对（`JwtAuthenticationFilter.isAuthVersionValid`）。旧 Token 立即失效（错误码 `2005 SESSION_REPLACED`）；WebSocket 侧由 `WsSessionRegistry` + `WsSessionCloser` 以关闭码 **4001** 主动断开旧连接，并把旧登录踢回登录页 |
| **F-10** | 🟠 中 | 运行中切换 WiFi 后，`/api/health` 一直返回**旧 IP**，CORS 也仍按旧地址判定，导致"换网络后别人拿到的地址是错的" | `OriginMatcher` 把本机网卡地址放在 `static final` 字段里，**类加载时只算一次**，IP 变化后不更新 | 改成 **5 秒 TTL 的短缓存**：既避免每次跨域判定都枚举网卡，又能在换网后 5 秒内自动跟上 |
| **F-11** | 🟡 低 | `RoomService.saveGameRecord` 的结算文案判断用了中文字面量 `"狼人阵营"` 去比较英文枚举名 | 类型不匹配，永不相等 | 抽出 `winnerText()`，比较 `RoleType.Camp.WEREWOLF.name()` |
| **F-12** | 🔴 严重 | **房主在自己的设备上看到「我」标记落在别人的座位上**（qiqi 建房、1111 加入后，qiqi 的界面把 1111 的座位标成"我"）。同时发现加入房间时**其他人根本收不到任何通知** | 两个缺陷叠加：<br/>① `RoomWsController.broadcastRoom()` 把"每个人各自的私有视图"循环广播到**同一个公共主题** `/topic/room/{code}`，客户端依次收到 `mySeatId=null/1/2/...`，**最后一条覆盖前面的**，于是每个人都显示最后加入者的座位。这是显示 bug，也是隐私泄露；<br/>② `RoomService.joinRoom()` **完全没有广播**，房间里已有玩家看不到新进来的人 | ① 新增 `RoomEventPublisher` 统一收口房间广播：**公共主题只发不含个人视角的公开快照**（`mySeatId=null`），每个人自己的视图走 `/user/queue/room`；删除 `GameBroadcaster.broadcastRoom` 这个会串视角的接口；<br/>② 在 join / leave / ready / kick / 改配置 / 加 AI 全部补上广播；<br/>③ 前端 `room` store 拆成 `publicRoom` + `privateRoom` 两个来源再合并，并用「座位 userId == 我的 userId」兜底推导座位 |
| **F-13** | 🔴 严重 | **前端从未收到过任何私有消息**：自己的身份牌、女巫药水、可选行动、预言家查验结果全部为空。用项目真实使用的 `@stomp/stompjs` 实测：订阅 `/user/queue/game` 与 `/user/queue/room` 收到 **0 条**，而订阅 `/user/{userId}/queue/...` 收到全部 | 直觉写法 `/user/queue/xxx` 依赖 Spring 按会话 Principal 把目的地翻译成 `/user/{userId}/queue/xxx`。但本项目 `MessageBrokerRegistry` 把 `/user` 同时注册成了 simple broker 的前缀，**这类订阅不会被翻译**，服务端消息全部落空。早期只用手工 HTTP 脚本验收，没有任何 WebSocket 客户端测试，因此这个缺陷一直没暴露 | 历史临时修复曾显式拼接 `/user/{userId}/queue/*`，并观察到私有消息恢复；该写法允许客户端猜测其他用户 ID，因此本轮改为标准 `/user/queue/*` 订阅。SimpleBroker 只处理 `/topic`、`/queue`，让 Spring 按已认证 Principal 转换用户目的地；同时拒绝匿名 STOMP `CONNECT` 和非白名单 `SUBSCRIBE`。本轮修复的安全与回归验证结果以重新执行的测试为准，不沿用旧实测结论 |
| **F-14** | 🔴 严重 | **房主无法开始游戏**：界面上显示「4 真人 / 4 座位，3/4 已准备」，房主点「开始游戏」只弹「还有玩家未准备」，而房主自己**没有任何办法准备** | 两个缺陷叠加：<br/>① 后端 `RoomService.startGame` 要求**所有真人（含房主）**都已准备；<br/>② 前端 `RoomView.vue` 的底部操作条用 `v-if="isHost"` / `v-else` 分支——房主只渲染「开始游戏 / 解散房间」，**根本没有「准备」按钮**。于是房主把自己永久卡在"未准备"状态，房间再也开不了局 | ① 后端豁免房主：`startGame` 跳过 `userId == room.createdBy` 的座位，并把**所有**未准备者的名字一次性返回（`还有玩家未准备：A、B`），而不是只说一个人；<br/>② 前端把「需要准备的人」定义为除房主外的所有座位，据此计算 `readyAmongPlayers / needReadyCount` 与 `canStart`；<br/>③ 房主的提示语改为「你是房主，**不需要准备**；还等 X、Y 准备 / 可以开始了」，并给「开始游戏」按钮加上 `:disabled="!canStart"`，避免点了才知道不行 |
| **F-15** | 🔴 严重 | **私聊与狼人频道的实时消息前端完全收不到**：能发出去、能落库、历史记录里也查得到，但对方界面上永远不会出现新消息（必须刷新页面才会从历史里加载出来） | 前端 `stores/chat.ts` **只订阅了公共主题** `/topic/room/{code}/chat`。而私聊与狼人频道走的是**用户私有队列**（`GameEventMessenger.privateChat` → `sendToUser`），是完全不同的两条通道，因此这两类消息永远投递不到。更隐蔽的是 `stores/game.ts` 与 `stores/room.ts` 都订阅了自己的私有队列，**唯独 `chat.ts` 漏了**，属于典型的"看起来对称、实际漏一处" | ① 前端 `chat.ts` 进房时**同时订阅**公共主题与当前会话的 `/user/queue/chat` 私有聊天队列；<br/>② 顺带修掉一个会误导后人的隐患：后端 `privateChat` 原本把**公共主题的字符串**（`roomChat(roomCode)`）交给 `sendToUser`，靠 Spring 运行时重写成 `/user/{userId}/topic/...`。实测行为碰巧正确，但"公共主题名 + 私密投递"混用极易在后续重构中被改成真广播而泄露私聊。现改为独立的 `USER_QUEUE_CHAT = /queue/chat`，两条通道彻底分开 |
| **F-21** | 🔴 严重 | **狼刀是"先到先得"，不是"少数服从多数"**：两只狼各选一个目标时，**先点的那只狼说了算**，后点的狼等于没投票 | `tallyWolfTarget()` 用 `if (e.getValue() > bestCount)` 取最大值，**没有平票分支**。票数相同时保留"先遍历到的"那个目标，而 `LinkedHashMap` 的遍历顺序就是提交顺序 → 先投票的狼锁定刀口。<br/>这个缺陷是**对照参考实现**（`D:\软工` 的 `NightAction.getFinalWolfKillTargetId()`，它显式判"所有狼是否选了同一个目标"）后才确认属于规则性缺陷，而不是设计选择 | 改为显式多数决 + **平票空刀**：新增 `tied` 标记，最高票不唯一时返回 `null`（本晚无人被刀）；新增 `wolvesAgreedOnTarget()`；`GameView` 增加 `wolfVoteCounts` / `wolvesAgreed`（**只对狼人下发**，好人拿到 `null`），前端狼人面板显示实时刀口票数与「意见不一致将空刀」警告。回归：`wolfKillUsesMajorityAndTiesMeanNoKill`、`wolfKillTakesEffectWhenWolvesAgree`、`wolfKillMajorityWinsOverFirstVote` |

**此前验证状态（本轮用户队列安全修复前）**：后端自动化测试 **6 个测试类 / 66 个用例全绿**（`mvn test` → `BUILD SUCCESS`）；前端 `vue-tsc --noEmit` 0 错误、`vite build` 成功；真实 HTTP 端到端脚本 **177 项断言全部通过**（含完整 4 人局跑到胜负结算）；WebSocket 视角隔离脚本 **13 项断言全部通过**；私聊与狼人频道专项脚本 **28 项断言全部通过**；单会话强制脚本 **16 项断言全部通过**；联机可用性脚本 **11 项断言全部通过**。

**外部参考实现对照**：`D:\软工`（同题材 Java 教学版狼人杀，包名同为 `com.werewolf`）作为规则参照做了逐条比对，覆盖狼刀计票、女巫药水的"本晚 / 整局"两级状态、投票平票处理三处。比对结论：**狼刀计票确有缺陷（已修为 F-21）**；女巫跨夜用药与投票平票处理经实测**本项目的实现是正确的**（分别由 `compare_d_ruangong.py` 的用例 B 与 `voteRulesAreEnforced` 覆盖）。

---

## 十、答辩口径建议（三句话讲清项目）

1. **核心闭环**：创建房间 → 多人加入 → 实时对局（夜晚/白天/讨论/投票/胜负）→ 聊天，全流程由服务器权威的 `WerewolfGameEngine` 状态机驱动；
2. **AI 是增强不是依赖**：默认关闭。打开后 AI NPC 只产出"建议行动 + 发言"，经 `AiJsonParser` 校验后交给引擎执行，接口不可用时自动降级为规则 AI，断网也能完整跑完一局；
3. **AI 素材零成本**：角色立绘与技能特效采用"预生成素材 + 前端播放"，运行时没有任何生成开销，也不影响核心对局稳定性。
