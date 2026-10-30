> 2026-10-29 版本提示：本文含早期狼人杀设计与操作示例。当前交付范围、规则和生产运行方式以 [当前状态](JIUJIU_CURRENT_STATUS.md)、[运行与部署](RELEASE_AND_OPERATIONS.md) 及对应阶段报告为准；旧的角色/人数/多数决、禁止 WebRTC 或删除卷升级等说法已被新需求覆盖。

# 测试计划与测试报告

> 阶段 2最新验证：11类86例，0失败/错误/跳过；包含好友权限/并发、私图隔离、WebP实际解码以及认证版本回退拒绝。真实网络与浏览器验证见[PHASE2_DELIVERY](PHASE2_DELIVERY.md)。

> 2026-10-27 阶段 1 最新结果：10 类 79 例全通过，新增用户资料/密码/并发更新/房间访问测试 4 例和 H2 新旧库迁移测试 2 例。真实 WebSocket 验证改密关闭码 4002、成员订阅和退出后停止投递；桌面与手机断点已检查。见 [交付记录](PHASE1_DELIVERY.md)。

> **最新基线 2026-10-27**：当前包含 `RoomMatchIntegrationTest` 的 3 项集成测试，共 8 类、73 例，0 失败、0 错误、0 跳过；前端类型检查与构建通过。临时 H2 真实链路验证了四账号匹配、独立座位、开局及私有身份推送。下文 70 例清单保留前一版本记录；新增匹配用例和新平台尚缺功能见 [阶段 0 审计](JIUJIU_PHASE0_AUDIT.md)。这些结果不代表社交、13 人玩法或语音已实现。

> 本文档面向**课程项目的开发者、测试执行人与答辩评委**，解决一个问题：**怎么证明这套狼人杀系统真的能跑、跑得对、跑得稳**——先给出自动化测试的真实清单与执行方式，再给出 43 条可逐条勾选的手工用例、13 条验收标准对照、性能测试建议，最后是一份可以直接照读的 10 分钟答辩演示脚本。
>
> **本轮测试范围**：基础功能闭环（P0）。AI NPC 默认关闭（`werewolf.ai.enabled=false`、`allowAi=false`、`aiCount=0`），因此**主验收路径是 4 个真人浏览器窗口打完一局**；AI 相关用例单独标注为「可选增强」。
>
> 相关文档：[docs/GAME_RULES.md](GAME_RULES.md)（规则细节）、[docs/API.md](API.md)（接口与错误码）、[docs/WEBSOCKET.md](WEBSOCKET.md)（STOMP 地址与事件）、[docs/DEPLOY.md](DEPLOY.md)（环境搭建）。

---

## 一、测试策略分层

| 层 | 范围 | 工具 | 执行频率 | 谁负责 | 本轮状态 |
| --- | --- | --- | --- | --- | --- |
| **L1 单元测试** | 游戏引擎、规则 AI、AI 输出解析、WebSocket 消息授权拦截；不启动 Spring 上下文 | JUnit 5（`spring-boot-starter-test`） | 每次提交 | D（引擎）、E（AI）、C（WebSocket） | ✅ 5 类、57 例 |
| **L2 集成测试** | 真实 Spring 上下文：Security 过滤器链 + MyBatis-Plus + H2 建表脚本 + MockMvc 打完整 HTTP 链路 | `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("h2")` | 每次提交 / 每日 | B（认证）、A（房间）、D（游戏） | ✅ 2 类、13 例 |
| **L3 手工功能测试** | 4 个真人浏览器窗口的完整对局、越权、边界、断线重连、聊天可见性 | Chrome / Edge（含无痕窗口）、curl、浏览器控制台 | 每周联调 + 交付前 | 全员 | 📝 43 条用例已列出，执行记录待回填 |
| **L4 性能 / 稳定性** | 房间并发、WebSocket 连接数、tick 开销、长连接保持 | JMeter / autocannon / 自写脚本 | 第 6 周 | F | ⚠️ 建议方案见第七节（本轮未压测） |
| **L5 验收** | 方案第十八节的 13 条验收标准逐条对照 | 人工评审 + L1~L3 证据 | 交付前一次 | A（主）、全员 | ✅ 见本文第六节 |

### 为什么单元测试是主战场

`WerewolfGameEngine` 被刻意设计成**纯内存、零框架依赖**（只 import `com.werewolf.game.domain.*`），因此一局完整对局可以在毫秒级内跑完，不需要启动 Spring、不需要数据库、不需要 WebSocket。这让规则类 bug 的回归成本极低——**规则改动后先跑 L1，跑绿了再谈别的**。

反过来，**L1 覆盖不到的部分必须靠真实联机验收**：WebSocket 广播、隐私裁剪的实际下发行、前端渲染、浏览器实时一致性。第五节列出了 4 浏览器窗口的手工用例；执行记录仍待回填。

---

## 二、已实现的自动化测试清单

测试源码位置：`backend/src/test/java/com/werewolf/`。当前共 **7 个测试类、70 个用例**（57 个 L1、13 个 L2；其中 `NightResolveDebugTest` 保留了 3 个回归用例）。第四节单独保留旧版 53 例的历史记录，避免与当前结果混淆。

### 2.1 `WerewolfGameEngineTest`（27 个用例，L1）

路径：`backend/src/test/java/com/werewolf/game/engine/WerewolfGameEngineTest.java`

固定随机种子 `SEED = 20240501L`，保证可重复。所有非法行动用例都会做**状态指纹比对**（`snapshot()`），要求「拒绝 + 状态一个字段都不变 + 不产生任何事件」。

| 场景 | 当前用例方法 | 数量 | 主要验证点 |
| --- | --- | ---: | --- |
| 发牌与完整对局 | `startGame_assignsRolesForSixPlayers`、`startGame_assignsRolesForFourPlayers`、`fullRoundFlow_villageWins` | 3 | 人数与角色配置、4 人局保留女巫、从开局到胜负结算 |
| 夜晚角色与隐私 | `nonWolfCannotWolfKill`、`wolfCannotKillTeammate`、`seerCannotCheckSelf`、`repeatedNightActionsAreRejected`、`nightTimeoutAutoCompletesEveryone`、`witchCanUseBothPotionsInSameNight`、`witchCannotReuseSamePotion`、`witchPassEndsHerNight`、`seerRevealGatesNightResolution`、`seerRevealAutoConfirmsOnTimeout`、`nightTimeoutClosesSeerReveal`、`seerRevealIsPrivateToSeer`、`wolfKillUsesMajorityAndTiesMeanNoKill`、`wolfKillTakesEffectWhenWolvesAgree`、`wolfKillMajorityWinsOverFirstVote` | 15 | 非法行动不改状态；女巫同夜双药及空过；预言家翻牌确认窗口与隐私；狼刀多数决、平票空刀 |
| 讨论与发言 | `speakCanBeEndedEarly`、`speakTimeoutReasonIsTimeout`、`lastSpeakerPassEntersVoting` | 3 | 主动结束发言与超时跳过分开记录，最后发言人结束后进入投票 |
| 投票、阶段与胜负 | `voteRulesAreEnforced`、`actionsAreRejectedInWrongPhase`、`autoCompleteCurrentPhase_resolvesPhaseOnTimeout`、`evaluateWinner_reportsVillageWhenAllWolvesAreDead`、`evaluateWinner_reportsWerewolfWhenWolvesNotFewerThanVillagers`、`availableActionsAndPendingActorsFollowPhase` | 6 | 投票规则、越权与阶段限制、超时兜底、双方胜负判定 |
| **合计** |  | **27** |  |

### 2.2 `RuleAgentTest`（11 个用例，L1，AI 可选增强）

路径：`backend/src/test/java/com/werewolf/ai/RuleAgentTest.java`

| # | 用例方法 | `@DisplayName` | 验证点 |
| --- | --- | --- | --- |
| 1 | `wolfKill_neverTargetsTeammate` | 狼人夜里不会把同伴当成击杀目标 | 目标落在可选集合内、`source()==RULE_ONLY`、`usedLlm()==false` |
| 2 | `seerCheck_returnsTargetFromSelectable` | 预言家夜里从可选集合中挑一个目标 | 理由文案包含「查验」 |
| 3 | `witchHealsOnFirstNight` | 女巫首夜有解药且有人被刀 → 用解药救人 | 目标 = 当晚被刀者 |
| 4 | `witchWithoutPotions_neverReturnsHealOrPoison` | 女巫两瓶药都用完时只会选择 `WITCH_PASS` | 不返回 `WITCH_HEAL` / `WITCH_POISON`，目标为 `null` |
| 5 | `witchPoisonsCheckedWolf` | 女巫拿到「查验为狼人」的信息时会用毒 | 目标座位号解析正确（用「文本里第一个数字」） |
| 6 | `vote_avoidsTeammatesForWolf` | 狼人投票不会投同伴；好人投票目标合法 | 狼/好人两条分支 |
| 7 | `speak_returnsNonEmptySpeechWithOwnSeat` | 发言内容非空，并且包含自己的座位号 | 断言 `我是 N 号` |
| 8 | `wolfSpeech_isValidAndSelfIdentified` | 狼人发言同样是非空且自报座位号的合法决策 | — |
| 9 | `wolfSpeech_doesNotRevealTeammates` | 狼人规则 AI 的公开发言不能泄露私有的狼同伴名单 | 非空同伴名单下，发言不含内部提示与名单 |
| 10 | `noAllowedActions_returnsNonBlockingDecision` | 没有任何可用行动时返回空过决策，而不是抛异常 | 兜底返回 `WITCH_PASS`、目标 `null` |
| 11 | `abstainPreferredWhenVoteNotAllowed` | 只有弃票可用时返回 `ABSTAIN` 而不是 `VOTE` | — |

### 2.3 `AiJsonParserTest`（13 个用例，L1，AI 可选增强）

路径：`backend/src/test/java/com/werewolf/ai/AiJsonParserTest.java`

这是「AI 不能绕过 `GameEngine`」的**第一道闸门**。

| # | 用例方法 | `@DisplayName` |
| --- | --- | --- |
| 1 | `parsesPlainJson` | 标准 JSON 能解析（`usedLlm()==true`、`source()==LLM`） |
| 2 | `parsesJsonInsideMarkdownFence` | 被 ` ```json ` 代码块包裹的 JSON 能解析 |
| 3 | `parsesJsonWithSurroundingNoise` | 前有解释、后有废话的 JSON 能解析 |
| 4 | `toleratesBracesInsideStrings` | 字符串内部的花括号不会破坏对象提取 |
| 5 | `extractJson_behavesAsDocumented` | `extractJson` 能抽出第一个完整对象，也能识别「没有 JSON」 |
| 6 | `rejectsActionOutsideAllowedSet` | `action` 不在允许集合内 → `null` |
| 7 | `rejectsUnknownAction` | 未知 / 空 `action` 枚举值 → `null` |
| 8 | `rejectsTargetOutsideSelectableSet` | 需要目标的动作：`targetId` 不在可选集合内或缺失 → `null` |
| 9 | `acceptsMissingOrZeroTargetForTargetlessActions` | 不需要目标的动作：`targetId=0` 或缺失都能成功，并归一化为 `null` |
| 10 | `speakRequiresNonBlankSpeech` | `SPEAK` 必须带非空发言，否则 → `null` |
| 11 | `witchHealRequiresExplicitTargetId` | `WITCH_HEAL` 在当前解析器里属于「需要目标」的动作 |
| 12 | `rejectsBrokenJson` | 非法 JSON / 非对象 / `null` / 空串 / 缺右括号 → `null` |
| 13 | `fillsDefaultReasonWhenMissing` | `reason` 缺省时填充默认理由 |

> ⚠️ 第 11 条记录了一处**已知语义差异**：`AiJsonParser.requiresTarget(WITCH_HEAL)` 为 `true`（LLM 必须显式给 `targetId`），而引擎侧 `WerewolfGameEngine.witchHeal()` 会**忽略** `action.getTargetSeatId()`，直接用 `state.getWolfTargetSeatId()` 作为救治目标。也就是说 LLM 传的 `targetId` 只用于「合法性校验」，不参与实际结算。详见第四节缺陷清单 **D-03**。

### 2.4 `AuthFlowIntegrationTest`（9 个用例，L2）

路径：`backend/src/test/java/com/werewolf/AuthFlowIntegrationTest.java`（`@SpringBootTest` + `@ActiveProfiles("h2")` + MockMvc）

| # | 用例方法 | `@DisplayName` | 覆盖接口 |
| --- | --- | --- | --- |
| 1 | `registerMeCreateRoomAndMeta` | 注册 → 取资料 → 建房 → 当前房间 → 元数据 全流程 | `POST /api/auth/register`、`GET /api/users/me`、`POST /api/rooms`、`GET /api/rooms/current`、`GET /api/game/meta`、`GET /api/health` |
| 2 | `protectedEndpointsWithoutTokenReturn1001` | 不带 token 访问受保护接口返回业务码 1001 | `/api/users/me`、`/api/rooms/current`、`/api/game/state`、`POST /api/game/start` |
| 3 | `invalidTokenReturns1001` | 伪造 token 访问受保护接口同样返回 1001 | `GET /api/users/me` |
| 4 | `duplicateUsernameReturns2000` | 用户名重复注册返回 2000 | `POST /api/auth/register` |
| 5 | `invalidRegisterRequestReturns1000` | 注册参数不合法返回 1000（`BAD_REQUEST`） | 用户名 <3、密码 <6、昵称为空 |
| 6 | `loginSucceedsWithCorrectPasswordAndFailsWithWrongOne` | 登录：密码正确返回 token，密码错误返回 2003 | `POST /api/auth/login` |
| 7 | `secondLoginInvalidatesPreviousToken` | 同账号重新登录后旧 Token 立即失效 | 单会话强制、旧 Token 被拒 |
| 8 | `onlyLatestTokenRemainsValid` | 第三个设备登录后前两个 Token 均失效 | 只有最新 Token 有效 |
| 9 | `reloginOfOneUserDoesNotAffectOthers` | 账号 A 重新登录不影响账号 B | 跨账号会话隔离 |

> 该测试显式按 UTF-8 读取响应体（`getContentAsString(StandardCharsets.UTF_8)`）再校验中文昵称，避免 MockMvc 的字符集解码差异造成假失败。

### 2.5 `RoomGameFlowIntegrationTest`（4 个用例，L2）

路径：`backend/src/test/java/com/werewolf/RoomGameFlowIntegrationTest.java`

| # | 用例方法 | `@DisplayName` | 验证点 |
| --- | --- | --- | --- |
| 1 | `hostStartsGameWithThreeBots` | 房主 + 3 AI：建房 → 准备 → 开局 → 状态与元数据结构正确 | `seats.length()==4`、`aiCount==3`、`humanCount==1`、`allReady==true`、`players.length()==4`、`me.role` 非空、`phases.length()==8`、房间外用户提交行动 → `3004` |
| 2 | `humanOnlyRoomCannotStartWithOnePlayer` | 纯真人房：只有房主 1 人时不能开局，且房间状态不被破坏 | `allowAi==false`、`aiCount==0`、开局被拒（`3008` 或 `3007`）、失败后房间仍是 `WAITING` 且座位数不变 |
| 3 | `invalidAiCountIsRejected` | AI 数量非法（超过人数-1）时建房返回 3008 | `maxPlayers=4, aiCount=4` → `3008 ROOM_CONFIG_INVALID` |
| 4 | `eachPlayerSeesOwnSeatAsMySeat` | 房主和客人各自看到正确的 `mySeatId` 与准备状态 | 新玩家加入后房主视角不被覆盖；两人的私有准备状态不串号 |

> **注意**：用例 1 刻意显式传 `allowAi=true, aiCount=3`（因为默认是关闭的），并且**只断言状态结构、不断言 AI 是否已经行动**——AI 由 `@Scheduled` 每秒 tick 驱动，属异步行为，断言它会引入不确定性。

### 2.6 `NightResolveDebugTest`（3 个用例，L1，调试与回归用）

路径：`backend/src/test/java/com/werewolf/game/engine/NightResolveDebugTest.java`

| # | 用例方法 | `@DisplayName` | 验证点 |
| --- | --- | --- | --- |
| 1 | `nightResolvesAfterAllRolesAct` | 4 人局：狼刀 + 查验 + 女巫 PASS 之后夜晚应立刻结算 | 三个夜间角色行动完毕后阶段变 `DAY`、`lastNightDeaths == [villager]`、产出 `DAWN` |
| 2 | `wolfKillDoesNotAdvancePhase` | 狼人行动未收齐夜间行动时不应报告阶段推进 | `phaseAdvanced=false`，避免倒计时被重置 |
| 3 | `nightResolvesWithoutWitch` | 女巫出局后狼刀 + 查验即可结算 | 不必等夜晚超时 |

> 这个类起初用于复现「夜晚不结算」缺陷（F-01），现保留 3 条回归：4 人局夜晚结算、女巫出局后结算、狼人行动不误报阶段推进。首条用例按 `4 人局 = 1 狼 + 1 预言家 + 1 女巫 + 1 平民` 构造，与 `RoleAssigner` 的当前实现一致。

### 2.7 `WsAuthChannelInterceptorTest`（3 个用例，L1）

路径：`backend/src/test/java/com/werewolf/websocket/WsAuthChannelInterceptorTest.java`。直接构造 STOMP 帧测试拦截器，不启动 Spring 上下文；真实连接鉴权仍需联机回归验证。

| # | 用例方法 | 验证点 |
| --- | --- | --- |
| 1 | `privateSubscriptionsOnlyUseOwnSessionDestination` | 允许 `/user/queue/...`，拒绝其他用户或其他会话的私有目的地 |
| 2 | `anonymousConnectionsAndSubscriptionsAreRejected` | 匿名 `CONNECT` / `SUBSCRIBE` 被拒 |
| 3 | `clientsCannotPublishDirectlyToBroker` | 客户端可发 `/app/**`，不能直接向 `/topic/**` 或 `/queue/**` 发布 |

---

## 三、如何执行自动化测试

### 3.1 前置条件

| 项 | 要求 |
| --- | --- |
| JDK | 17 或更高（`pom.xml` 里 `<java.version>17</java.version>`） |
| Maven | 3.8+（或用 IDE 内置 Maven） |
| 数据库 | **不需要**：集成测试用 `@ActiveProfiles("h2")`，H2 建表脚本 `db/h2/schema.sql` 自动执行 |
| 网络 | 首次运行需要下载依赖（见 [README 第十一节 Q6](../README.md)） |
| 外部服务 | **不需要**：`RuleAgentTest` / `AiJsonParserTest` 都是 `new` 出来的纯单元测试，不会发起任何 LLM 请求 |

### 3.2 执行命令

```bash
# 全量测试（在 werewolf-game/backend 目录下）
cd werewolf-game/backend
mvn test

# 只跑引擎单元测试（规则改动后的快速回归，秒级）
mvn test -Dtest=WerewolfGameEngineTest

# 只跑集成测试
mvn test -Dtest='*IntegrationTest'

# 打包并跳过测试（演示前构建 jar）
mvn clean package -DskipTests
```

Windows PowerShell（指定 JDK，避免多版本干扰）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"     # 换成你的 JDK 路径
mvn -B -f werewolf-game\backend\pom.xml test
```

### 3.3 报告位置

| 产物 | 路径 |
| --- | --- |
| 每个测试类的纯文本摘要 | `backend/target/surefire-reports/com.werewolf.*.txt` |
| 每个测试类的 XML 明细 | `backend/target/surefire-reports/TEST-com.werewolf.*.xml` |
| 汇总 | `mvn test` 控制台输出的 `Tests run: X, Failures: Y, Errors: Z, Skipped: W` |

---

## 四、`mvn test` 执行结果

### 4.1 当前自动化测试结果（2026-10-26）

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-25.0.4"
& "E:\ds工作1-软工\.tools\apache-maven-3.9.9\bin\mvn.cmd" -B -f "E:\ds工作1-软工\werewolf-game\backend\pom.xml" test
```

```bash
# 通用写法（在 werewolf-game/backend 目录下）
mvn test
```

以最新一轮 `backend/target/surefire-reports/TEST-*.xml` 核对：**7 类、70 例，Failures / Errors / Skipped 均为 0**。其中新增了狼人规则 AI 发言隐私回归与 3 条 WebSocket 目的地授权拦截测试。下表按报告逐类列出，避免把早期测试数当作当前结果。

| 测试类 | 用例数 | Failures | Errors | Skipped | 结论 |
| --- | --- | --- | --- | --- | --- |
| `com.werewolf.game.engine.WerewolfGameEngineTest` | 27 | 0 | 0 | 0 | ✅ 通过 |
| `com.werewolf.ai.RuleAgentTest` | 11 | 0 | 0 | 0 | ✅ 通过 |
| `com.werewolf.ai.AiJsonParserTest` | 13 | 0 | 0 | 0 | ✅ 通过 |
| `com.werewolf.AuthFlowIntegrationTest` | 9 | 0 | 0 | 0 | ✅ 通过 |
| `com.werewolf.RoomGameFlowIntegrationTest` | 4 | 0 | 0 | 0 | ✅ 通过 |
| `com.werewolf.game.engine.NightResolveDebugTest` | 3 | 0 | 0 | 0 | ✅ 通过 |
| `com.werewolf.websocket.WsAuthChannelInterceptorTest` | 3 | 0 | 0 | 0 | ✅ 通过 |
| **合计** | **70** | **0** | **0** | **0** | ✅ **全绿** |

**按层统计：**

| 层 | 测试类数 | 用例数 | 结果 |
| --- | --- | --- | --- |
| L1 单元测试（不启动 Spring 上下文） | 5（引擎、规则 AI、AI JSON、夜晚回归、WebSocket 授权拦截） | 57 | ✅ 全绿 |
| L2 集成测试（`@SpringBootTest` + H2 + MockMvc） | 2（`AuthFlowIntegrationTest`、`RoomGameFlowIntegrationTest`） | 13 | ✅ 全绿 |
| **合计** | **7** | **70** | ✅ 全绿 |

**证据文件**：`backend/target/surefire-reports/com.werewolf.*.txt` 与 `TEST-com.werewolf.*.xml`（7 个类各一对）。

### 4.2 历史记录与补充验收

旧版报告曾记录「6 类、53 例全绿」，当时还没有本节列出的后续回归用例。旧报告的逐类数字与其 53 例汇总不一致，因此**不作为当前结果的证据**；当前统计以上述 7 份 XML 报告为准。

### 4.2.1 旧版端到端验收脚本记录（真实 HTTP 链路）

旧版曾用**真实 HTTP 接口**（非 MockMvc，起真实服务 + 脚本）跑端到端验收。该段原始记录的正文写「133 项」，表格却写「91 项」，两者不一致；保留覆盖链路作为历史说明，**不把这两个数字当作当前实测结论**。

| 项 | 内容 |
| --- | --- |
| 断言总数 | 旧记录为 **91**（原正文另写 133，存在口径冲突） |
| 通过 | 旧记录写 **91** |
| 未通过 | 0 |
| 覆盖链路 | 登录 → 建房 → 加入 → 未准备开局被拒（`3007`）→ 准备 → 开局 → 身份隐私（每人只看到自己的身份）→ 夜晚越权（平民刀人 `4004` / 狼人查验 `4004` / 夜晚投票 `4003` / 重复行动 `4007` / 查验自己 `4006`）→ 夜晚结算 → 讨论发言（非当前发言人 `4002` / 死者 `4005`）→ 聊天三频道隔离 → 投票 → **胜负结算并公开全部身份** → 鉴权（无 token `1001`）→ 房间配置边界（4 人 3 狼被拒 `3008`、5 人 2 狼合法、重复建房 `3006`） |

**历史结论**：当时的脚本跑通了「完整一局直到 `gameOver=true`、四个人的身份全部公开」。当前端到端验收应以重新执行的脚本结果和手工记录为准。

### 4.2.2 运行时缺陷修复记录（本轮已修复）

以下是早期联调报告收录的 14 个已修复问题，保留供答辩与复盘；后续新增的联机与狼刀规则缺陷修复见 `PROJECT_HANDBOOK.md` 第九节。

| # | 缺陷 | 根因 | 修复 | 回归证据 |
| --- | --- | --- | --- | --- |
| **F-01** | **夜晚永远不结算（🔴 严重）** | `WerewolfGameEngine` 里用**一个**布尔值同时表达「本晚行动是否收集完」和「本晚是否已结算」两种语义。狼人行动后该标志被置为 `true`，导致女巫行动触发 `resolveNight()` 时被误判为「已结算」直接 `return`。表现为：狼刀 / 查验 / 女巫用药**全部返回成功**，但阶段**永远停在 `NIGHT`** | 拆成两个字段：`nightActorsDone`（行动收集完）+ `nightSettled`（结算幂等标志），并在字段注释里写明「必须分开，早期版本合并会导致夜晚永不结算」 | 新增单元测试 `NightResolveDebugTest#nightResolvesAfterAllRolesAct`（4 人局三神职行动后必须**立刻**进入 `DAY`）→ ✅ 通过 |
| **F-02** | **4 人局没有女巫（🔴 严重）** | 原 `RoleAssigner` 只在人数 `>= 5` 时发放女巫，导致 **P0 必选角色在 4 人局缺失**，女巫技能与解药/毒药路径完全无法演示 | 改为预言家与女巫**恒定各 1 名**：`buildDeck` 无条件 `deck.add(SEER); deck.add(WITCH);`，并引入常量 `REQUIRED_SPECIAL_ROLES = 2`。连带影响：<br/>① 狼人上限公式变为 `1..min(⌊(n-1)/2⌋, n-2)`（同时满足「少于一半」和「放得下两个神职」）；<br/>② `buildDeck` 增加 `totalPlayers < 4` 的兜底校验（否则 3 人局会出现「1 狼 + 2 神职、没有平民」的退化配置）；<br/>③ **开局强制要求至少 4 个已入座玩家**（`RoomService.startGame` 里 `if (total < 4) throw ROOM_CONFIG_INVALID`）<br/>**4 人局 = 1 狼 + 1 预言家 + 1 女巫 + 1 平民** | `WerewolfGameEngineTest#startGame_assignsRolesForFourPlayers`（断言已同步修正为 `WITCH==1`、`VILLAGER==1`）、`#startGame_assignsRolesForSixPlayers`、`NightResolveDebugTest` → ✅ 全部通过 |
| **F-03** | **JSON 字段名被 Jackson 改写（🟡 一般）** | `RoomView` 的 `isIAmHost()` / `isIAmReady()` 会被 Jackson 按 JavaBean 规则剥掉 `is` 前缀，序列化成 `hostViewer` / `readyViewer`，与前端 `types/index.ts` 里声明的 `iAmHost` / `iAmReady` **不一致** | 在两个 getter 上固定命名：`@JsonProperty("iAmHost")`、`@JsonProperty("iAmReady")` | `AuthFlowIntegrationTest#registerMeCreateRoomAndMeta`（`$.data.iAmHost == true`）、`RoomGameFlowIntegrationTest` → ✅ 通过 |
| **F-04** | **H2 启动失败（🟡 一般）** | 空的 `db/h2/data.sql` 触发 H2 报错 `'script' must not be null or empty`，应用无法启动 | 删除 `db/h2/data.sql` 与 `application-h2.yml` 里的 `data-locations` 配置；演示账号改由 `com.werewolf.config.DemoDataInitializer`（`@Profile("h2")`）在启动时用 `PasswordEncoder` 现场生成 BCrypt 哈希创建 | `AuthFlowIntegrationTest` / `RoomGameFlowIntegrationTest` 用 `@ActiveProfiles("h2")` 起真实 Spring 上下文 → ✅ 通过 |
| **F-05** | **结算文案恒错（🟡 一般）** | `RoomService.saveGameRecord` 里 `"狼人阵营".equals(label(state.getWinner()))`：`label()` 只是原样返回 `"WEREWOLF"`/`"VILLAGE"`，**中文串与英文枚举永不相等** → `game_records.result` 无论谁赢都写「好人阵营胜利」（即 4.4 的 D-10） | 抽出 `winnerText(String winner)`，改为比较 `RoleType.Camp.WEREWOLF.name()` / `RoleType.Camp.VILLAGE.name()`；未识别的值原样回显而不是硬套「好人胜利」 | 端到端脚本步骤 13（`winner=WEREWOLF` 时结算链路正常）+ 代码审查 → ✅ 通过 |
| **F-06** | **女巫出局后夜晚白等超时（🟢 轻微）** | `maybeResolveNight()` 只被女巫的三个行动调用，`wolfKill`/`seerCheck` 不调用 → 女巫已出局时夜晚没有任何提前结算的触发点，必然走满 30 秒倒计时（即 D-11） | `wolfKill` 与 `seerCheck` 也调用 `maybeResolveNight()`，让"最后一步行动"无论由谁完成都能立即结算 | 新增回归用例 `NightResolveDebugTest#nightResolvesWithoutWitch`（7 人局、女巫出局、狼刀 + 查验后立即进入 `DAY`）→ ✅ 通过 |
| **F-07** | **夜晚倒计时被重置（🟢 轻微）** | `wolfKill/seerCheck` 返回 `success(nightActorsDone)`，上层 `afterAction` 把"行动收齐"误当成"阶段已切换"，于是调用 `syncPhaseSeconds()` 把夜晚倒计时重置为完整 30s，女巫已死时夜晚可能被拉长到 60~90 秒（即 D-12） | `GameActionResult` 拆出两个语义：`phaseAdvanced`（真的换阶段了）与 `actionProgressed`（本回合行动收齐了），新增工厂方法 `progressed(boolean)`；狼刀/查验改用 `progressed` | 新增回归用例 `NightResolveDebugTest#wolfKillDoesNotAdvancePhase`（行动未收齐时 `isPhaseAdvanced()` 必须为 `false`）→ ✅ 通过 |
| **F-08** | **超时提示污染复盘数据（🟢 轻微）** | 讨论超时通过伪造一条内容为「（发言超时，跳过）」的 `SPEAK` 事件推进，这条记录会进入 `state.speeches` → `game_records.detail_json`，复盘时看起来像玩家真的说了这句话（即 D-09） | 新增专用事件类型 `GameEvent.EventType.SPEAK_SKIP`，直接推进 `speakCursor` 而不写入发言记录；前端 `types/index.ts` 与 `stores/game.ts` 同步处理该事件（只在事件日志里提示"发言超时，已跳过"） | 前端 `vue-tsc --noEmit` 0 错误 + `vite build` 通过 → ✅ 通过 |
| **F-09** | **多设备共用一个账号（🔴 严重）** | 玩家身份与座位按 `userId` 绑定；`RoomService.joinRoom` 中 `byUser(userId)` 命中后把重复加入当成「重连」直接返回，后端也不存在「单账号单点在线」的概念。结果：多台设备登录同一账号时全部映射到**同一个 `PlayerState` / 同一个座位**，表现为「多个玩家共用了一个账号」 | 引入**单会话强制**：<br/>① `users` 新增 `auth_version`，每次登录 +1；<br/>② JWT 增加 `ver` claim；<br/>③ `JwtAuthenticationFilter.isAuthVersionValid` 逐请求比对，旧 Token 立即失效并返回 `2005 SESSION_REPLACED`；<br/>④ WebSocket 侧新增 `WsSessionRegistry` + `WsSessionCloser`，新连接建立时以关闭码 **4001** 主动断开旧连接，并先推一条 `KICKED` 提示；<br/>⑤ 前端识别 2005 / 4001，停止自动重连并弹窗引导重新登录 | 新增 3 个集成用例 `AuthFlowIntegrationTest#secondLoginInvalidatesPreviousToken / #onlyLatestTokenRemainsValid / #reloginOfOneUserDoesNotAffectOthers`；另有专项脚本 `session_check.py` **16 项断言** → ✅ 全部通过 |
| **F-10** | **换 WiFi 后 IP 报告错误（🟠 一般）** | `OriginMatcher` 把本机网卡地址放在 `static final` 字段，**类加载时只算一次**。运行中切换网络后 `/api/health` 仍返回旧 IP、CORS 仍按旧地址判定，用户会把**过期的地址**发给队友 | 改为 **5 秒 TTL 短缓存**：既避免每次跨域判定都枚举网卡，又能在换网后 5 秒内自动跟上 | 实测：切换 WiFi 后 5 秒内 `localAddresses` 自动更新为新 IP → ✅ 通过 |
| **F-11** | **结算文案恒错（🟡 一般）** | `RoomService.saveGameRecord` 用中文字面量 `"狼人阵营"` 比较 `GameState.winner`（实际是英文枚举名 `WEREWOLF`/`VILLAGE`），永不相等 → `game_records.result` 无论谁赢都写「好人阵营胜利」 | 抽出 `winnerText(String)`，改为比较 `RoleType.Camp.WEREWOLF.name()` / `VILLAGE.name()` | 端到端脚本步骤 13（`winner=WEREWOLF`）→ ✅ 通过 |

### 4.3 D-01：曾经的「4 人局没有女巫」断言（已修复）

> 这是本轮交付过程中**唯一一次 `mvn test` 不是全绿**的原因，修复过程本身就是一个值得写进报告的案例。

| 项 | 内容 |
| --- | --- |
| 曾经失败的用例 | `com.werewolf.game.engine.WerewolfGameEngineTest#startGame_assignsRolesForFourPlayers` |
| 曾经的 `@DisplayName` | 4 人局 1 狼：**没有女巫**，其余为平民 |
| 曾经失败的断言 | `assertEquals(0, countRole(state, RoleType.WITCH), "人数 < 5 时没有女巫")` 与 `assertEquals(2, countRole(state, RoleType.VILLAGER))` |
| 冲突根源 | 用例写的是**旧规则**（女巫只在 ≥5 人时发放），而 `RoleAssigner` 已经改成**恒定发放**（F-02）：`if (werewolfCount + REQUIRED_SPECIAL_ROLES > totalPlayers) throw ...`，随后无条件 `deck.add(RoleType.SEER); deck.add(RoleType.WITCH);`。因此 `buildDeck(4, 1)` 得到 `[WEREWOLF, SEER, WITCH, VILLAGER]`——**4 人局实际是 1 狼 + 1 预言家 + 1 女巫 + 1 平民** |
| 谁是对的 | **实现是对的**。`RoleAssigner` 类注释写明「女巫：固定 1 名（与预言家同为 P0 必选角色，**4 人局也保留**）」；`RoomService.validateConfig` 注释同样写明「两者都是 P0 角色，4 人局也必须存在」；同目录的 `NightResolveDebugTest` 就是按 4 人局含女巫构造的 |
| 处理结果 | ✅ **已修复**：用例的 `@DisplayName` 改为「4 人局 1 狼：1 狼 + 1 预言家 + 1 女巫 + 1 平民」，断言改为 `WITCH==1`、`VILLAGER==1`（对应 F-02 的规则变更） |
| 连带修正 | 同一处历史错误还散落在 4 份文档里（[README.md](../README.md)、[GAME_RULES.md](GAME_RULES.md)、[API.md](API.md)、[TEAM_PLAN.md](TEAM_PLAN.md)），`GAME_RULES.md` 甚至给出了并不存在的 `if (totalPlayers >= 5) deck.add(RoleType.WITCH);` 伪代码 —— **本轮已全部一并修正** |
| 修复当时的历史验证 | 旧版报告记录 `Tests run: 53, Failures: 0, Errors: 0, Skipped: 0`；当前结果见 4.1 的 70 例 |

### 4.4 缺陷 / 不一致清单（交付时状态）

| 编号 | 类型 | 位置 | 描述 | 严重度 | 状态 |
| --- | --- | --- | --- | --- | --- |
| **D-01** | 测试与实现不一致 | `WerewolfGameEngineTest#startGame_assignsRolesForFourPlayers` | 断言「4 人局没有女巫」，与 `RoleAssigner` 的恒定发放策略相反 → 用例必然失败 | 🟡 中（阻塞 CI 全绿） | ✅ **已修复**：断言改为 `WITCH==1`/`VILLAGER==1`（详见 4.3） |
| **D-02** | 文档与实现不一致 | [README.md](../README.md)、[GAME_RULES.md](GAME_RULES.md)、[API.md](API.md)、[TEAM_PLAN.md](TEAM_PLAN.md) | 同样把 4 人局写成「1 狼 + 1 预言家 + 2 平民」，`GAME_RULES.md` 甚至给出了并不存在的 `if (totalPlayers >= 5) deck.add(RoleType.WITCH);` 伪代码 | 🟡 中 | ✅ **本轮已修正**为「1 狼 + 1 预言家 + 1 女巫 + 1 平民」 |
| **D-03** | 语义差异（非 bug，但需注意） | `AiJsonParser.requiresTarget(WITCH_HEAL)` vs `WerewolfGameEngine.witchHeal()` | 解析器要求 LLM 显式给 `targetId` 并通过「可选目标集合」校验，但引擎**忽略**该参数、直接用 `state.getWolfTargetSeatId()`。结果是：LLM 传的 `targetId` 只影响合法性判断，不影响结算目标。已在 `AiJsonParserTest#witchHealRequiresExplicitTargetId` 中显式记录 | 🟢 低（默认关闭 AI，不影响本轮交付） | 记录在案 |
| **D-04** | 配置与实现不一致 | `application-h2.yml` / `db/h2/schema.sql` | `rooms.allow_ai` 的 **H2 建表默认值是 `TRUE`**，而应用默认（`CreateRoomRequest.allowAi=false`、`RoomService.createRoom` 显式写入）是 `FALSE`。由于 `RoomService` 始终显式写入该列，实际不会触发；但默认值本身与「AI 默认关闭」的交付口径相反，容易误导阅读者。`database/schema.sql` 已改为 `DEFAULT 0` | 🟢 低（无行为影响） | ✅ **已修复**：H2 与 MySQL 建表脚本的 `allow_ai` 默认值统一改为 `FALSE`，与 `CreateRoomRequest.allowAi` 默认值一致 |
| **D-05** | 注释与实现不一致 | `WerewolfGameEngine` 的 `nightSettled` 字段 | [GAME_RULES.md](GAME_RULES.md) 第 7.3 节把幂等标志写成 `nightResolved`，实际字段名是 `nightSettled`（F-01 拆分后的两个字段之一）。另外 `GameState.pendingKilledSeatId` 字段在引擎里**从未被写入**（只被 `enterNight` 置 `null`），属预留字段 | 🟢 低 | ✅ 本轮已修正文档 |
| **D-06** | 注释与实现不一致 | `websocket/WsDestinations.java` 类注释 | 注释里写的发送地址是 `/app/room/{roomCode}/chat.send`，**实际映射是 `/app/chat/{roomCode}/send`**（`RoomWsController.chat`）；注释也未列出 `/vote-skip`、`/heartbeat`、`/me`、`/app/lobby/rooms`、`/app/game/resync`。前端 `stomp.ts` 用的是正确地址 | 🟢 低（仅误导读者） | ✅ **已修复**：类注释更新为完整的 10 条发送地址 + 9 条推送地址 |
| **D-07** | 注释与实现不一致 | `config/DemoDataInitializer` 类注释 | 注释提到「可在需要时用 `werewolf.demo.enabled=false` 关闭」，但 `application.yml` 与 `AppProperties` 里**都没有** `werewolf.demo.enabled` 这个配置项，写了也不生效（真正的开关是 `@Profile("h2")`） | 🟢 低 | ✅ **已修复**：注释改为说明"本类通过 `@Profile("h2")` 生效，mysql profile 不写入演示数据" |
| **D-08** | 前端类型不完整 | `frontend/src/types/index.ts` | ① `GameMeta` 缺少后端实际返回的 `rules` 字段（`GameController.meta()` 会返回 `minPlayers/maxPlayers/maxWerewolvesOf/requiredSpecialRoles/werewolfRule/winnerRule`）；② 错误码常量缺少 `RATE_LIMITED(1005)` 与 `ACCOUNT_DISABLED(2004)` | 🟢 低（不影响运行） | ✅ **已修复**：`GameMeta` 补 `GameMatchRules` 类型与 `rules?` 字段；错误码常量补 `RATE_LIMITED(1005)` 与 `ACCOUNT_DISABLED(2004)` |
| **D-09** | 行为差异（设计如此） | `WerewolfGameEngine.autoCompleteCurrentPhase(DISCUSSION)` | 讨论超时会把当前发言人补一条内容为「（发言超时，跳过）」的 `SPEAK` 事件，**这条发言会进入 `state.speeches` 并最终落进 `game_records.detail_json`**，复盘时看起来像玩家真的说了这句话。属于有意设计（保证对局不卡死），但需要在复盘页的文案上说明 | 🟢 低 | ✅ **已修复**：改为新增专用事件 `SPEAK_SKIP`（前端只记日志、不进发言列表），不再污染复盘数据 |
| **D-13** | 事件类型新增 | `GameEvent.EventType.SPEAK_SKIP` | 为修复 D-09 新增的事件类型，前端 `types/index.ts` 的 `GameEventType` 与 `stores/game.ts` 的事件分发已同步 | 🟢 低 | ✅ 已同步（前后端一致） |
| **D-10** | **业务逻辑缺陷（文案）** | `RoomService.saveGameRecord` | `record.setResult("狼人阵营".equals(label(state.getWinner())) ? "狼人阵营胜利" : "好人阵营胜利")`，而 `label(winner)` 只是把 `winner` 原样返回（`"WEREWOLF"` / `"VILLAGE"`），**永远不等于中文串 `"狼人阵营"`** → `game_records.result` **无论谁赢都被写成「好人阵营胜利」**。`winner` 列（`"VILLAGE"`/`"WEREWOLF"`）与前端展示（读 `winner`/`winnerLabel`）都是正确的，**只影响这一列的文案**。修复方式：比较对象改成 `RoleType.Camp.WEREWOLF.name()` 或 `RoleType.Camp.WEREWOLF.getLabel()` | 🟡 中（数据文案错误，不影响对局） | ✅ **已修复**：抽出 `RoomService.winnerText(String winner)`，改为比较 `RoleType.Camp.WEREWOLF.name()` / `VILLAGE.name()`；文末 F-05 有记录 |
| **D-11** | 行为特性（需注意） | `WerewolfGameEngine.maybeResolveNight` 的调用方 | 该私有方法**只被女巫的三个行动调用**（`witchHeal`/`witchPoison`/`witchPass`），`wolfKill`/`seerCheck` 不调用。因此：① 女巫存活时，夜晚的「提前结算」总是由女巫提交用药决定的那一刻触发；② **女巫出局后，夜晚没有任何提前结算的触发点**，必然走满 `night-seconds` 倒计时后由 `RoomRuntime.onPhaseTimeout()` 收尾。不影响正确性（超时兜底一定推进），但会让"女巫已死的夜晚"多等 30 秒 | 🟢 低 | ✅ **已修复**：`wolfKill` / `seerCheck` 也调用 `maybeResolveNight()`；回归用例 `NightResolveDebugTest#nightResolvesWithoutWitch` |
| **D-12** | 行为特性（需注意） | `RoomRuntime.afterAction` / `GameActionResult.phaseAdvanced` | `wolfKill`/`seerCheck` 返回 `GameActionResult.success(nightActorsDone)`，即把「本晚行动收集齐」当成 `phaseAdvanced=true` 返回。上层 `afterAction` 看到 `phaseAdvanced` 就会调 `syncPhaseSeconds()`，**把夜晚倒计时重置为完整时长**（30s）。当女巫已出局、狼人与预言家先后行动时，夜晚计时会被重置若干次，实际夜晚可能长达 60~90 秒 | 🟢 低（不影响正确性） | ✅ **已修复**：`GameActionResult` 拆出 `phaseAdvanced` 与 `actionProgressed`，新增 `progressed(boolean)` 工厂方法；回归用例 `NightResolveDebugTest#wolfKillDoesNotAdvancePhase` |

**汇总**：本轮交付时**不存在未修复的缺陷**。14 个运行时缺陷（F-01 ~ F-14，见 4.2.2）已全部修复并各自带回归用例，其中 D-10/D-11/D-12 后来也一并修复（对应 F-05/F-06/F-07），D-04（H2 `allow_ai` 建表默认值）与 D-08（前端类型补全）已同步修正代码；其余 D-03（AI 提示词与引擎目标语义差异）为语义说明，已记录在案。

---

## 五、手工测试用例（43 条）

### 5.0 通用前置

| 项 | 要求 |
| --- | --- |
| 后端 | 方式 ①（H2）启动：`mvn spring-boot:run`，`curl http://localhost:8080/api/health` 返回 `status: UP` |
| 前端 | `pnpm dev`，浏览器打开 `http://localhost:5173` |
| 账号 | `demo` / `player1` / `player2` / `player3` / `player4`，密码统一 `demo1234`（由 `DemoDataInitializer` 在 h2 profile 创建） |
| 多窗口 | **至少 2 个窗口必须用无痕/隐私模式**，否则同源 `localStorage` 共享 Token，会互相顶掉登录态 |
| curl 变量 | 后文用 `$T1`~`$T4` 表示 4 个账号的 Token，`$CODE` 表示房间码。获取方式：<br/>`T1=$(curl -s -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"demo","password":"demo1234"}' \| sed -E 's/.*"token":"([^"]+)".*/\1/')` |

**判定符号**：✅ 通过 ｜ ❌ 失败（记缺陷） ｜ ⏸ 不适用/可选增强

---

### 5.1 注册 / 登录 / Token（8 条）

| 编号 | 用例 | 前置 | 步骤 | 预期结果 | 关联错误码 |
| --- | --- | --- | --- | --- | --- |
| **TC-AUTH-01** | 正常注册 | 清空浏览器存储 | 打开 `/login` → 切「注册」→ 用户名 `t_user1`、密码 `test1234`、昵称 `测试一号` → 提交 | ✅ 返回 `code=0`，自动登录并跳转 `/lobby`；`localStorage.werewolf_token` 有值 | — |
| **TC-AUTH-02** | 用户名重复 | TC-AUTH-01 已完成 | 退出登录 → 再次注册用户名 `t_user1` | ✅ 提示「用户名已被注册」，停留在注册页 | `2000 USERNAME_TAKEN` |
| **TC-AUTH-03** | 昵称重复 | TC-AUTH-01 已完成 | 注册新用户名，昵称填 `测试一号` | ✅ 提示「昵称已被占用」 | `2001 NICKNAME_TAKEN` |
| **TC-AUTH-04** | 注册参数非法 | — | 用户名填 `ab`（<3）、密码填 `123`（<6）、昵称留空 → 提交 | ✅ 提示「请求参数不合法: 房间名称不能为空;…」之类的字段级提示；不创建账号 | `1000 BAD_REQUEST` |
| **TC-AUTH-05** | 正常登录 | 已注册 | 退出后用正确的用户名密码登录 | ✅ 跳转 `/lobby`，右上角显示昵称与头像 | — |
| **TC-AUTH-06** | 密码错误 | 已注册 | 用错误密码登录 | ✅ 提示「用户名或密码错误」，不跳转、不发 Token | `2003 PASSWORD_WRONG` |
| **TC-AUTH-07** | 无 / 伪造 Token 访问受保护接口 | — | `curl -s localhost:8080/api/users/me`；再 `curl -s localhost:8080/api/users/me -H "Authorization: Bearer fake.token.here"` | ✅ 两次都返回 `{"code":1001,...}`，HTTP 状态码仍是 **200**（业务码在 body 里） | `1001 UNAUTHORIZED` |
| **TC-AUTH-08** | 过期 / 篡改 Token 后刷新页面 | 已登录 | F12 → Application → Local Storage → 把 `werewolf_token` 末尾改几个字符 → F5 | ✅ 全局守卫把页面重定向到 `/login`；控制台无未捕获异常 | `1001` |

---

### 5.2 房间：创建 / 加入 / 准备 / 管理（14 条）

| 编号 | 用例 | 前置 | 步骤 | 预期结果 | 关联错误码 |
| --- | --- | --- | --- | --- | --- |
| **TC-ROOM-01** | 创建房间（默认配置） | 登录 `demo` | 大厅 → 创建房间 → 房间名 `默认房`、人数 8、狼人 2、AI 0、公开 ✅ → 提交 | ✅ 返回 6 位房间码；自动进入房间页；座位表只有「1 号 · 演示玩家」；`iAmHost=true` | — |
| **TC-ROOM-02** | 创建房间（4 人 1 狼，本轮主路径） | 登录 `demo` | 房间名 `答辩演示`、人数 **4**、狼人 **1**、AI **0**、公开 ✅ | ✅ 创建成功；`maxPlayers=4`、`werewolfCount=1` | — |
| **TC-ROOM-03** | 房间名为空 | 登录 | 房间名留空 → 提交 | ✅ 表单/接口拒绝，提示房间名称不能为空；不创建房间 | `1000` |
| **TC-ROOM-04** | 人数越界 | 登录 | 人数填 **3** 提交；再填 **13** 提交 | ✅ 两次都被拒（`@Min(4)` / `@Max(12)`，前端下拉也限制了） | `1000` |
| **TC-ROOM-05** | 狼人数超上限 | 登录 | 人数 4、狼人 **3** → 提交 | ✅ 提示「狼人数量必须在 1~1 之间（需为预言家和女巫留出座位，且少于人数的一半）」 | `3008 ROOM_CONFIG_INVALID` |
| **TC-ROOM-06** | 狼人数为 0 | 登录 | 狼人 **0** → 提交 | ✅ 被拒（`@Min(1)` + `validateConfig`） | `1000` / `3008` |
| **TC-ROOM-07** | AI 数量越界 | 登录 | 人数 4、AI 数量 **4** → 提交（需先允许 AI） | ✅ 提示「AI 数量必须在 0~3 之间（至少留 1 个真人座位）」 | `3008` |
| **TC-ROOM-08** | 已在其它房间时再建房 | 窗口 1 已在某房间 | 窗口 1 再点「创建房间」提交 | ✅ 提示「你已在房间 XXXXXX 中」 | `3006 ALREADY_IN_ROOM` |
| **TC-ROOM-09** | 通过房间码加入（无密码） | 窗口 1 已建公开房 | 窗口 2（`player1`）大厅 → 输入房间码 → 加入 | ✅ 窗口 1 的座位面板**无需刷新**自动出现 2 号座位（证明 STOMP 实时同步）；窗口 2 的「我的座位」= 2 号 | — |
| **TC-ROOM-10** | 密码房：密码正确 | 窗口 1 建房时填密码 `secret123` | 窗口 2 用正确密码加入 | ✅ 加入成功 | — |
| **TC-ROOM-11** | 密码房：密码错误 | 同上 | 窗口 2 用 `wrong` 加入；再用空密码加入 | ✅ 两次都提示「房间密码错误」，不占座位 | `3001 ROOM_PASSWORD_WRONG` |
| **TC-ROOM-12** | 房间满员 | 4 人房已满（1~4 号） | 窗口 5（`player4`）尝试加入 | ✅ 提示「房间人数已满」 | `3002 ROOM_FULL` |
| **TC-ROOM-13** | 房间不存在 | 登录 | 输入不存在的房间码 `ZZZZZZ` 加入；再 `curl -s "localhost:8080/api/rooms/ZZZZZZ" -H "Authorization: Bearer $T1"` | ✅ 提示「房间不存在或已解散」 | `3000 ROOM_NOT_FOUND` |
| **TC-ROOM-14** | 准备状态实时同步 + 踢人 + 解散 | 4 人房 | ① 3 名非房主玩家依次点「准备」→ 观察所有窗口的「已准备 X/3」，应实时变为 `3/3`；房主不需准备，「开始游戏」按钮变为可用；<br/>② 房主对 4 号点「踢出」→ 4 号窗口收到「你已被移出房间」，其余窗口座位表减少 1 人；<br/>③ 房主点「解散房间」→ 所有窗口回到大厅 | ✅ 三步都符合预期；`room_players` 表对应行被删除 | `3005`（非房主踢人）/`3004` |

> **补充校验（curl）**：非房主踢人 → `3005 NOT_HOST`；房主踢自己 → `1000`（「房主不能踢出自己」）；踢不存在的座位 → `4006`。

---

### 5.3 对局：开局与夜晚（6 条）

| 编号 | 用例 | 前置 | 步骤 | 预期结果 | 关联错误码 |
| --- | --- | --- | --- | --- | --- |
| **TC-NIGHT-01** | 非房主玩家未全部准备时开局被拒 | 4 人房，3 名非房主中只有 2 人准备 | 房主界面「开始游戏」应禁用；用房主 Token 直接 `curl -X POST localhost:8080/api/game/start -H "Authorization: Bearer $T1"` | ✅ 接口返回「还有玩家未准备：XX」及 `3007`；房间仍是 `WAITING`，座位不变 | `3007 PLAYER_NOT_READY` |
| **TC-NIGHT-02** | 人数不足时开局被拒 | 只有房主 1 人（4 人房） | 房主界面「开始游戏」应禁用；用房主 Token 直接请求 `POST /api/game/start` | ✅ 提示「至少需要 4 名玩家（含 AI）才能开始，当前 1 人」 | `3008` |
| **TC-NIGHT-03** | 非房主开局被拒 | 4 人房，房主是 `demo` | `curl -X POST localhost:8080/api/game/start -H "Authorization: Bearer $T2"`（`player1`） | ✅ 返回「只有房主可以执行该操作」 | `3005 NOT_HOST` |
| **TC-NIGHT-04** | 开局后只看得到自己的身份 | 3 名非房主玩家全部准备，房主开局 | ① 观察发牌特效（`GAME_START` → `/assets/roles/hidden-back.svg`）；<br/>② 每个窗口核对「我的身份」卡；<br/>③ F12 → Network → WS → 找 `GAME_STATE` 报文 | ✅ **4 人局角色 = 1 狼 + 1 预言家 + 1 女巫 + 1 平民**（4 个窗口合起来正好各一个）；<br/>✅ 其他人的座位显示**卡背**；<br/>✅ `players[].role` **完全不存在**，只有 `me.role` 是自己 | — |
| **TC-NIGHT-05** | 夜晚越权：平民刀人 / 预言家查自己 / 狼人刀同伴 / 重复行动 | 对局进入 `NIGHT` | ① 用平民窗口 Token：`-d '{"action":"WOLF_KILL","targetSeatId":2}'`；<br/>② 用预言家窗口 Token：`-d '{"action":"SEER_CHECK","targetSeatId":<自己座位>}'`；<br/>③ 用狼人窗口 Token：`-d '{"action":"WOLF_KILL","targetSeatId":<狼同伴座位>}'`；<br/>④ 狼人再提交一次 `WOLF_KILL` | ✅ ① → `4004 你的角色不能执行该操作`；<br/>✅ ② → `4006 目标不合法`（不能查验自己）；<br/>✅ ③ → `4006 不能击杀狼人同伴`；<br/>✅ ④ → `4007 本回合你已经行动过了`；<br/>✅ **四次的状态与事件流完全不变**（可用 `GET /api/game/state` 前后对比 `phase`/`wolfVotes`/`me.checkRecords`） | `4004`/`4006`/`4007` |
| **TC-NIGHT-06** | 夜晚结算：解药优先于狼刀 | 进入 `NIGHT` | ① 狼人刀 3 号；② 预言家查验任意人；③ 女巫选「使用解药」 | ✅ 三个夜间角色行动完毕后**夜晚立即结算**（不等满 30 秒）；<br/>✅ 天亮显示**平安夜**（`PEACEFUL_NIGHT` 事件 + `/assets/effects/dawn.svg`）；<br/>✅ 3 号仍然存活；女巫的 `healPotionAvailable=false` | — |

> **⏱ 超时兜底验证（可并入 TC-NIGHT-06）**：夜晚什么都不做，30 秒后系统自动结算——狼人随机刀一个好人、预言家随机验一个没验过的人、女巫空过。**对局永远不会卡死**。

---

### 5.4 对局：白天 / 讨论 / 投票 / 结算（5 条）

| 编号 | 用例 | 前置 | 步骤 | 预期结果 | 关联错误码 |
| --- | --- | --- | --- | --- | --- |
| **TC-DAY-01** | 死讯 4 窗口完全一致 | 夜晚有人出局 | 观察 4 个窗口的「昨晚死亡：X 号」 | ✅ 4 个窗口文字完全一致；`lastNightDeaths` 相同 | — |
| **TC-DAY-02** | 讨论按序发言，非当前发言人被拒 | 进入 `DISCUSSION` | ① 观察「轮到 X 号（昵称）发言」（`SPEAK_TURN`）；<br/>② **非**当前发言人窗口 `curl -X POST /api/game/action -d '{"action":"SPEAK","speech":"抢麦"}'` | ✅ 只有当前发言人的窗口出现输入框；<br/>✅ 抢麦返回 `4002`，提示「当前应由 N 号发言」 | `4002 GAME_NOT_YOUR_TURN` |
| **TC-DAY-03** | 投票：最高票放逐 | 进入 `VOTING` | 3 个人投 1 号、1 个人投 2 号 | ✅ 实时看到 `votedCount` 与 `voteCounts` 变化（**看不到谁投了谁**，`VOTE_CAST` 不含 `targetSeatId`）；<br/>✅ 全员投完后立即结算；1 号被放逐，公布身份，播放 `/assets/effects/vote.svg` | — |
| **TC-DAY-04** | 投票：平票无人出局 + 弃票 | 进入 `VOTING` | ① 4 人局：2 票投 1 号、2 票投 2 号 → 观察；<br/>② 下一轮用 `ABSTAIN`（前端「弃票」按钮或 `-d '{"action":"ABSTAIN"}'`） | ✅ 平票 → `VOTE_RESULT.tie=true`，前端提示「投票平票，本轮无人出局」，**没有人出局**；<br/>✅ 弃票被计入 `abstainCount`；重复弃票返回 `4009`；投自己被拒 `4006` | `4006`/`4009` |
| **TC-DAY-05** | 胜负判定与复盘公开身份 | 走到最后一轮 | ① 狼人被放逐 → 观察结算；<br/>② 另一局让狼人杀到「存活狼数 ≥ 存活好人数」；<br/>③ 点「再来一局」 | ✅ 狼全灭 → `winner=VILLAGE` + `win-good.svg`；<br/>✅ 狼数 ≥ 好人数 → `winner=WEREWOLF` + `win-werewolf.svg`；<br/>✅ **`gameOver=true` 时 `players[].role` 全部非空**（4 个座位都公开）；<br/>✅ `game_records` 新增 1 条，`users.total_games` 全部 +1、胜方 `win_games` +1；<br/>✅ 「再来一局」后回到 `WAITING`，真人需重新准备 | — |

---

### 5.5 聊天三频道可见性（4 条）

| 编号 | 用例 | 前置 | 步骤 | 预期结果 | 关联错误码 |
| --- | --- | --- | --- | --- | --- |
| **TC-CHAT-01** | 公共频道全员可见 | 4 人房内 | 任一窗口在「公共」频道发一条 | ✅ **4 个窗口都能看到**；`/topic/room/{code}/chat` 广播 | — |
| **TC-CHAT-02** | 私聊只有双方可见 | 4 人房内 | 1 号私聊 3 号发一条 | ✅ **只有 1 号和 3 号能看到**；2 号、4 号看不到（消息走 `/user/queue` 的 `privateChat`） | — |
| **TC-CHAT-03** | 狼人频道只有狼人可见 | 对局中 | ① 狼人窗口切到「狼人」频道发言；<br/>② 平民窗口 `curl -X POST /api/chat/$CODE/send -H "Authorization: Bearer $平民T" -d '{"content":"偷看","type":"WOLF"}'` | ✅ ① 只有狼人窗口能看到；<br/>✅ ② 返回 `4004`，提示「你不是狼人，无法使用狼人频道」 | `4004` |
| **TC-CHAT-04** | 历史记录按可见性过滤 + 非成员被拒 | 有若干历史消息 | ① 4 个窗口刷新页面，观察历史聊天恢复；<br/>② 非房间成员 `curl "localhost:8080/api/chat/$CODE/history" -H "Authorization: Bearer $路人T"` | ✅ ① 每个窗口只恢复到**自己可见**的消息（`ChatService.history` 逐条按 `PUBLIC/SYSTEM`、`WOLF`、`PRIVATE` 过滤）；<br/>✅ ② 返回 `3004 你不在该房间中` | `3004` |

---

### 5.6 WebSocket / 断线重连 / 刷新恢复（4 条）

| 编号 | 用例 | 前置 | 步骤 | 预期结果 |
| --- | --- | --- | --- | --- |
| **TC-WS-01** | 连接状态指示 | 已登录 | 观察顶部导航的连接标签 | ✅ 显示绿色「实时已连接」；F12 → Network → WS 能看到 `ws://localhost:5173/ws?token=...` |
| **TC-WS-02** | 刷新页面后状态全恢复 | 对局进行中 | 在窗口 3 按 **F5** | ✅ 自动回到对局页；阶段、倒计时、座位、`me.role`、聊天记录**全部恢复**（路由守卫读 Token + `roomStore.restoreCurrent()` + `ws.publish(WsSend.sync(...))`） |
| **TC-WS-03** | 断网 → 恢复自动重连 | 对局进行中 | F12 → Network → 改成 **Offline**，等标签变「连接中…」→ 改回 **Online** | ✅ 约 `reconnectDelay: 3000`（3 秒）内自动重连；标签变回「实时已连接」；重连后**重新订阅所有目的地并补发离线期间积压的发送**（`onConnect` 里的 `pendingSends` 队列），状态自动补齐 |
| **TC-WS-04** | 越权行动走 `/user/queue/error` | 对局中 | 在用平民窗口的浏览器控制台粘贴 [docs/WEBSOCKET.md](WEBSOCKET.md) 最后一节的越权测试片段 | ✅ 控制台弹出错误提示；**其他 3 个窗口完全不受影响**；`/user/queue/error` 收到 `{type:"ERROR", data:{code:4004,...}}` |

---

### 5.7 数据落库与非功能（2 条）

| 编号 | 用例 | 前置 | 步骤 | 预期结果 |
| --- | --- | --- | --- | --- |
| **TC-DATA-01** | 一局结束后落库 | mysql profile + 完整打完一局 | `SELECT id, room_code, winner, result, rounds, LEFT(detail_json,120) FROM game_records ORDER BY id DESC LIMIT 1;`<br/>`SELECT seat_id, nickname, role, is_alive, is_ai FROM room_players ORDER BY seat_id;`<br/>`SELECT username, total_games, win_games FROM users;` | ✅ `game_records` 新增 1 条，`detail_json` 含 `players` + `speeches`；<br/>✅ `room_players.role` 在 `phase==END` 时被回写（`revealRoles`）；<br/>✅ 4 个真人 `total_games` 各 +1，胜方 `win_games` +1 |
| **TC-DATA-02** | 默认零外部请求 + 房间回收 | 全默认配置 | ① `curl /api/health` 看 `aiProvider` 与 `llmEnabled`；<br/>② 观察后端日志有无出站 HTTP；<br/>③ 房主解散房间后 `curl /api/health` 看 `activeRooms` | ✅ `aiProvider == "规则 AI（AI 总开关关闭）"`、`llmEnabled == false`；<br/>✅ 日志中**没有任何 LLM 调用**；<br/>✅ `activeRooms` 减 1（`RoomManager.destroy`）；空闲房间由 `RoomRuntimeScheduler.cleanupIdleRooms` 每 5 分钟回收（`idle-timeout-minutes: 120`） |

---

### 5.8 用例覆盖度对照

| 需求点 | 覆盖用例 |
| --- | --- |
| 注册 / 登录 / 重复用户名 / 错误密码 / 过期 Token | TC-AUTH-01 ~ 08 |
| 创建房间各种非法参数 | TC-ROOM-03 ~ 07 |
| 密码加入与密码错误 | TC-ROOM-10、TC-ROOM-11 |
| 房间满 / 房间不存在 | TC-ROOM-12、TC-ROOM-13 |
| 准备与未准备开局被拒 | TC-ROOM-14、TC-NIGHT-01 |
| 踢人与解散 | TC-ROOM-14 |
| **4 浏览器完整对局** | TC-NIGHT-04、05、06 + TC-DAY-01 ~ 05 |
| 夜晚越权（平民刀人 / 狼刀同伴 / 预言家查自己 / 重复行动） | TC-NIGHT-05 |
| 聊天三频道隔离 | TC-CHAT-01 ~ 04 |
| 断线重连与刷新恢复 | TC-WS-02、TC-WS-03 |
| 结算公开身份 | TC-DAY-05 + TC-DATA-01 |
| 服务器权威 / 隐私不泄露 | TC-NIGHT-04、TC-NIGHT-05、TC-WS-04 |
| 对局不卡死 | TC-NIGHT-06（超时兜底） |

---

## 六、验收标准对照表（13 条）

对照方案第十八节。**本轮交付范围**：第 1~11 条属基础功能闭环（**必须通过**）；第 12、13 条属 AI 可选增强（**默认关闭，代码保留**）。

| # | 验收标准 | 状态 | 实现位置（文件 / 类 / 方法） | 验证方法（自动化 / 手工） |
| --- | --- | --- | --- | --- |
| 1 | 用户可以注册、登录、退出 | ✅ 本轮交付 | `auth/AuthController.register/login/logout`、`auth/AuthService`、前端 `views/LoginView.vue` | 自动：`AuthFlowIntegrationTest#registerMeCreateRoomAndMeta`、`#loginSucceedsWithCorrectPasswordAndFailsWithWrongOne`；手工：TC-AUTH-01、05 |
| 2 | 登录状态在刷新后保持 | ✅ 本轮交付 | `stores/auth.ts`（`localStorage.werewolf_token`）、`router/index.ts` 全局守卫 | 手工：TC-AUTH-08、TC-WS-02 |
| 3 | 可以创建房间并自定义人数与角色数量 | ✅ 本轮交付 | `room/RoomController.create`、`RoomService.createRoom/validateConfig`、`room/dto/CreateRoomRequest` | 自动：`AuthFlowIntegrationTest#registerMeCreateRoomAndMeta`、`RoomGameFlowIntegrationTest#invalidAiCountIsRejected`；手工：TC-ROOM-01~07 |
| 4 | 可以通过房间码加入房间，支持房间密码 | ✅ 本轮交付 | `RoomService.joinRoom`（BCrypt `passwordEncoder.matches`） | 手工：TC-ROOM-09、10、11 |
| 5 | 房间满员 / 已开局时不能加入 | ✅ 本轮交付 | `RoomService.joinRoom`（`runtime.isFull()`、`Room.Status.PLAYING` 判断） | 手工：TC-ROOM-12（`3002`）+ 对局中尝试加入（`3003`） |
| 6 | 非房主玩家准备状态实时同步；全部非房主准备后房主可开局 | ✅ 本轮交付 | `RoomService.setReady/startGame`、`RoomWsController.ready`（`/app/room/{code}/ready`）、`RoomView.vue` 的 `canStart` | 手工：TC-ROOM-14、TC-NIGHT-01；房主豁免准备 |
| 7 | 夜晚狼人刀人、预言家查验、女巫用药正确结算 | ✅ 本轮交付 | `WerewolfGameEngine.wolfKill/seerCheck/witchHeal/witchPoison/witchPass`、`resolveNight`、`maybeResolveNight` | 自动：`WerewolfGameEngineTest#fullRoundFlow_villageWins`、`#repeatedNightActionsAreRejected`、`#witchCannotUsePotionTwiceInSameNight`、`NightResolveDebugTest#nightResolvesAfterAllRolesAct`；手工：TC-NIGHT-04~06 |
| 8 | 白天按顺序发言，投票放逐最高票 | ✅ 本轮交付 | `WerewolfGameEngine.buildSpeakOrder/speak/vote/abstain/resolveVoting` | 自动：`WerewolfGameEngineTest#voteRulesAreEnforced`、`#fullRoundFlow_villageWins`；手工：TC-DAY-02~04 |
| 9 | 胜负判定正确并公布全员身份 | ✅ 本轮交付 | `WerewolfGameEngine.checkWinnerInternal/evaluateWinner`、`GameViewProjector.project`（仅 `over` 时填 `role`） | 自动：`WerewolfGameEngineTest#evaluateWinner_reportsVillageWhenAllWolvesAreDead`、`#evaluateWinner_reportsWerewolfWhenWolvesNotFewerThanVillagers`；手工：TC-DAY-05 |
| 10 | 多个浏览器实时联机，状态一致 | ✅ 本轮交付 | `config/WebSocketConfig`、`websocket/GameEventMessenger`、`room/runtime/SimpGameBroadcaster` | 手工：4 窗口全流程 + TC-WS-01~03、TC-DAY-01 |
| 11 | 局内聊天：公共、私聊、狼人频道 | ✅ 本轮交付 | `websocket/ChatService.send/broadcast/history`、`ChatController`、`RoomWsController.chat` | 手工：TC-CHAT-01~04 |
| 12 | AI NPC 能发言、投票、执行角色技能；AI 不可用时自动降级 | ⏸ 可选增强（默认关闭，开启后可用） | `ai/AiService.decide`、`ai/LlmAgent`、`ai/RuleAgent`、`ai/AiJsonParser` | 自动：`RuleAgentTest`（11 条，含狼人发言隐私）、`AiJsonParserTest`（13 条）；手工：把 `werewolf.ai.enabled=true` + `aiCount=3` 建房（见 [README 第十节](../README.md)） |
| 13 | 至少一个 AI 视觉特效正常播放 | ⏸ 可选增强（默认关闭，开启后可用） | `components/EffectManager.vue`、`styles/effects.css`、`public/assets/effects/*.svg` | **特效播放本身不依赖 AI 开关**：任意一局都会播放发牌 / 天亮 / 投票 / 结算特效（本地预生成 SVG）；素材清单见 [docs/AI_DESIGN.md](AI_DESIGN.md) |

### 6.1 本轮交付的 11 条结论

| 结论 | 说明 |
| --- | --- |
| **第 1~11 条：通过** | 每条都有对应的实现位置与可执行的验证方法（自动化或手工用例），已在本文档逐条给出 |
| **自动化测试：全绿** | 最新 7 份 Surefire 报告合计 **70 例、0 失败、0 错误、0 跳过**（见 4.1） |
| **端到端验收** | 旧版真实 HTTP 脚本记录覆盖完整 4 人局；旧数字口径冲突，当前执行结果需另行登记（见 4.2.1） |
| **早期缺陷：已修复** | 夜晚不结算、4 人局缺女巫、JSON 字段名被改写、H2 启动失败等早期缺陷已有回归；后续联机缺陷见 `PROJECT_HANDBOOK.md` 第九节 |
| **手工用例执行记录** | 43 条用例的执行结果请在下表登记，作为验收证据 |

### 6.2 手工用例执行记录表

| 编号 | 执行人 | 日期 | 环境（H2 / MySQL / Docker） | 结论 | 缺陷编号 |
| --- | --- | --- | --- | --- | --- |
| TC-AUTH-01 | 待填 | 待填 | 待填 | 待填 | — |
| … | … | … | … | … | — |

### 6.3 补充的非功能性验收

| 项 | 目标 | 验证方法 | 本轮状态 |
| --- | --- | --- | --- |
| **服务器权威** | 客户端无法伪造身份或跳过规则 | 用平民账号提交 `{"action":"WOLF_KILL","targetSeatId":2}`，必须返回 `4004`；用 `GET /api/game/state` 做前后状态指纹比对 | ✅ 代码级拦截点见 [docs/ARCHITECTURE.md](ARCHITECTURE.md)；手工 TC-NIGHT-05 |
| **隐私不泄露** | 对局中任何报文不含他人身份 | F12 → Network → WS，检索 `"role"`，只应出现在自己的 `me.role` 里；`players[].role` 不存在 | ✅ 由 `GameViewProjector.project` + `SimpGameBroadcaster.publicCopy` 双重保证；手工 TC-NIGHT-04 |
| **对局不卡死** | 任何阶段超时都会自动推进 | 4 人局全程不操作，观察是否自动从 `NIGHT` 走到 `END`（4 人局一轮约 `6+30+10+40×4+25+4 = 235` 秒，约 4 分钟） | ✅ 自动：`WerewolfGameEngineTest#autoCompleteCurrentPhase_resolvesPhaseOnTimeout`、`NightResolveDebugTest`；手工 TC-NIGHT-06 |
| **零外部依赖可运行** | 断网 + 无 API Key 仍可完整对局 | 断网后按方式 ① 启动，4 个真人窗口跑完整一局 | ✅ 手工 TC-DATA-02 |
| **默认零外部请求** | 默认配置不发起任何 LLM 请求 | `werewolf.ai.enabled=false` → `GET /api/health` 返回 `llmEnabled:false`；后端日志无出站 HTTP | ✅ 手工 TC-DATA-02 |

---

## 七、性能测试建议

> 本轮的瓶颈不在 CPU，而在**长连接数量**与**同一房间的广播扇出**。因此压测要分两个维度做。

### 7.1 房间并发（REST 层）

目标：确认能同时承载多少「等待中的房间」。

```bash
# 前置：准备 200 个账号（每个账号只能在一个房间里，所以账号数 = 房间数）
for i in $(seq 1 200); do
  curl -s -X POST http://localhost:8080/api/auth/register \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"load$i\",\"password\":\"load1234\",\"nickname\":\"压测$i\"}" > /dev/null
done

# 用 autocannon 压 /api/health（无需 Token，纯探活）
npx autocannon -c 50 -d 30 http://localhost:8080/api/health

# 用 autocannon 压「带 Token 的房间列表」（把 TOKEN 换成真实值）
npx autocannon -c 20 -d 30 \
  -H "Authorization=Bearer $TOKEN" \
  http://localhost:8080/api/lobby/rooms
```

关注指标：

| 指标 | 参考阈值 | 说明 |
| --- | --- | --- |
| p99 延迟 | < 200 ms | `/api/lobby/rooms` 会遍历所有房间并逐个投影 `RoomView`，房间数上千时要注意 |
| 错误率 | 0% | 出现 5xx 说明 `GlobalExceptionHandler` 兜住了异常，需要看后端日志定位 |
| 内存 | 稳定不增长 | `RoomManager` 是 `ConcurrentHashMap`，房间不销毁就会泄漏（已有 `cleanupIdleRooms` 兜底） |

### 7.2 WebSocket / STOMP 长连接与广播扇出（关键）

目标：确认单机能挂住多少 STOMP 连接，以及一个房间广播一次状态的耗时。

**方案 A：JMeter（图形化，适合答辩展示）**

1. 线程组：`200` 线程，`Ramp-up 20s`，循环 `1` 次。
2. 采样器：`WebSocket Open Connection` → 地址 `ws://127.0.0.1:8080/ws?token=${TOKEN}`。
3. 采样器：`WebSocket request-response Sampler` → 发送 STOMP `CONNECT` 帧（带 `Authorization: Bearer ${TOKEN}`）。
4. 采样器：`WebSocket Single Write Sampler` → 订阅 `/user/queue/game`、`/topic/room/${CODE}/event`。
5. 采样器：`WebSocket Single Write Sampler` → 发送 `/app/room/${CODE}/sync`。
6. 断言：在 `30000 ms` 内收到 `GAME_STATE` 报文。
7. 监听器：`聚合报告` + `响应时间图`。

**方案 B：Node 脚本（更贴近真实扇出压力，推荐）**

```bash
# 在 frontend 目录下（已有 @stomp/stompjs 依赖）
node -e "
const { Client } = require('@stomp/stompjs');
const N = 100, CODE = process.argv[1], TOKEN = process.argv[2];
let ok = 0, t0 = Date.now();
for (let i = 0; i < N; i++) {
  const c = new Client({ brokerURL: 'ws://127.0.0.1:8080/ws?token=' + TOKEN, reconnectDelay: 0 });
  c.onConnect = () => {
    c.subscribe('/user/queue/game', () => { if (++ok === N) console.log('全部收到', Date.now() - t0, 'ms'); });
    c.publish({ destination: '/app/game/resync', body: '{}' });
  };
  c.activate();
}
" $CODE $TOKEN
```

**关键观察点**：

| 现象 | 含义 | 处理 |
| --- | --- | --- |
| 广播耗时随连接数线性上升 | `broadcastState()` 对每个座位各投影一次 `GameView` 并单独下发（N 个座位 → N+1 条消息） | 12 人房上限下扇出很小，可接受；若要多房间大屏观战，把公共视图改为「按需拉取 + 事件增量」 |
| `tick()` 出现堆积 | 单线程 `@Scheduled(fixedDelay = 1000)` 串行处理所有房间 | 先用 `/api/health` 的 `activeRooms` 观察房间数；房间数上千时按 roomId 分片成多个调度线程 |
| 长连接被中间层掐断 | Nginx 默认 `proxy_read_timeout 60s` | 已在 `frontend/nginx.conf` 与 `deploy/nginx-standalone.conf` 里设为 `3600s`；同时前端 `heartbeatIncoming/Outgoing = 10000` |

### 7.3 稳定性（长时间挂机）

| 项 | 方法 | 预期 |
| --- | --- | --- |
| 长连接保持 | 开一局，4 个窗口挂 60 分钟不动 | 连接标签始终「实时已连接」；`phase` 由超时兜底持续推进 |
| 内存泄漏 | 反复建房 → 解散 200 次，观察 `/api/health` 的 `activeRooms` 与 JVM 堆 | `activeRooms` 回到 0；堆不持续增长 |
| 房间回收 | 建一个空房后关闭所有窗口，等待 `idle-timeout-minutes=120`（或临时改成 1 分钟） | 日志出现「回收空闲房间 roomCode=XXXXXX」，`activeRooms` 减 1 |

---

## 八、10 分钟答辩演示脚本

> **总原则**：**只演示基础功能**（4 个真人窗口 + 零依赖 H2），AI 只作为「可选增强」在最后一分钟提一句。全程**不依赖网络、不依赖数据库、不依赖 API Key**。
>
> **演示前一天必做**：`cd backend && mvn -q clean package -DskipTests`，确认 `target/werewolf-server.jar` 存在；`cd frontend && pnpm install && pnpm build` 跑通；把 4 个浏览器窗口的账号密码写在便签上。

### 8.0 开演前 5 分钟准备（不计入 10 分钟）

| 步骤 | 命令 / 操作 | 检查点 |
| --- | --- | --- |
| 1 | `java -jar backend/target/werewolf-server.jar --spring.profiles.active=h2` | 日志出现 `Started WerewolfApplication` 与「已创建 5 个演示账号」 |
| 2 | `curl http://localhost:8080/api/health` | `status: UP`、`aiProvider: 规则 AI（AI 总开关关闭）`、`llmEnabled: false` |
| 3 | `cd frontend && pnpm dev` | 终端输出 `http://localhost:5173` |
| 4 | 打开 4 个窗口：Chrome 普通、Edge 普通、Chrome 无痕、Edge 无痕 | 4 个独立的 `localStorage` |
| 5 | 4 个窗口分别登录 `demo` / `player1` / `player2` / `player3` | 每窗口右上角出现绿色「实时已连接」 |
| 6 | **提前把音量关掉、通知关掉、投屏分辨率调到 1920×1080** | — |

### 8.1 时间轴

| 时间 | 环节 | 操作 | 要讲的一句话 |
| --- | --- | --- | --- |
| **0:00 – 0:40** | 开场与定位 | 展示 README 的「本轮交付边界一览」表格 | 「核心是多人在线狼人杀，本轮交付基础功能闭环；AI 是可选增强，默认关闭，不影响基础功能运行。」 |
| **0:40 – 1:20** | 架构一句话 | 切到 [docs/ARCHITECTURE.md](ARCHITECTURE.md) 的架构图 | 「单体 Spring Boot，同一进程同时提供 REST 与 WebSocket；**服务器权威**——所有规则校验都在 `WerewolfGameEngine` 里，客户端改不了任何状态。」 |
| **1:20 – 2:00** | 登录与刷新保持 | 4 个窗口登录；在窗口 3 按 F5 | 「Token 存 `localStorage`，刷新后路由守卫直接放行，不用重新登录。4 个窗口必须用无痕避免 Token 互相顶掉。」 |
| **2:00 – 3:00** | 建房与加入（**高光 1：实时同步**） | 窗口 1 建房：`答辩演示`、4 人、1 狼、AI 0、公开；窗口 2/3/4 用房间码加入 | 「注意窗口 1 的座位表**没有刷新**就自动出现了 2/3/4 号——这是 STOMP 推送 `/topic/room/{code}` 的效果。」 |
| **3:00 – 3:40** | 准备与开局（**验证 3007**） | 先让 2 名非房主玩家准备；房主界面显示剩余未准备者，按钮禁用；用房主 Token 请求 `POST /api/game/start` 验证 **3007**；第 3 名非房主准备后，房主点「开始游戏」 | 「房主不需要准备；其余 3 人都准备后才可开局。未准备时，后端返回 **3007**，前端直接禁用开局按钮。」 |
| **3:40 – 4:30** | 身份与隐私（**高光 2：隐私裁剪**） | 4 个窗口各看自己的角色卡；F12 → Network → WS → 找 `GAME_STATE` 报文，`Ctrl+F` 搜 `"role"` | 「4 人局是 **1 狼 + 1 预言家 + 1 女巫 + 1 平民**。报文里 `players[].role` **完全不存在**——身份只在各自的 `/user/queue/game` 里下发，这是 `GameViewProjector` 的裁剪。」 |
| **4:30 – 5:30** | 夜晚行动（**高光 3：越权被拒**） | 狼人刀人、预言家查验、女巫用药；同时用平民窗口的 Token 执行一次 curl 越权 | 「平民提交 `WOLF_KILL` 返回 **4004**，而且游戏状态**一个字段都没变**——`applyAction` 的校验在改状态之前。狼刀的 `ROLE_SKILL` 事件在公共频道**不带 `targetSeatId`**，只有狼人能在自己的私有队列里看到目标。」 |
| **5:30 – 6:30** | 天亮与讨论（**高光 4：按序发言**） | 4 窗口看到一致死讯；轮流发言；用非发言人窗口 curl 抢麦 | 「死讯 4 个窗口完全一致。只有当前发言人的窗口有输入框，抢麦返回 **4002**。发言顺序从上一晚第一个死者的下一位开始。」 |
| **6:30 – 7:30** | 投票与结算 | 投票 → 最高票放逐 → 播放放逐特效 → 结算面板公开全部身份 | 「投票时只能看到票数和 `votedCount`，**看不到谁投了谁**（`VOTE_CAST` 不含目标）。`gameOver=true` 之后 `players[].role` 才被填上，用于复盘。」 |
| **7:30 – 8:20** | 聊天三频道 | 公共发一条 → 私聊 3 号 → 狼人频道发言 → 平民 curl 试狼人频道 | 「公共 4 个窗口可见；私聊只有双方可见；狼人频道平民发就返回 **4004**。可见性全部由服务端裁剪，前端只是渲染。」 |
| **8:20 – 9:00** | 断线重连（**高光 5**） | 窗口 3 断网 → 标签变「连接中…」→ 恢复 → 3 秒内自动重连并补齐状态 | 「`reconnectDelay` 是 3 秒，重连后重新订阅所有目的地、补发离线期间积压的发送，并主动 resync 一次全量状态。」 |
| **9:00 – 9:40** | 自动化测试与文档 | 切到 [docs/TEST_PLAN.md](TEST_PLAN.md) 第二节与第四节 | 「当前是 **7 个测试类、70 个用例，全绿**；其中既有游戏规则回归，也有 WebSocket 目的地授权测试。引擎是纯内存状态机，规则用例无需启动服务。『夜晚永远不结算』曾由一个布尔值表达两种语义引起，现已拆成 `nightActorsDone` 与 `nightSettled`。」 |
| **9:40 – 10:00** | 收尾：AI 与部署 | 切到 [README 第十节](../README.md) 与 [docs/DEPLOY.md](DEPLOY.md) | 「AI NPC 代码完整保留，默认关闭：`werewolf.ai.enabled=false` + `allowAi=false` + `aiCount=0`，所以**系统默认不会向任何外部 LLM 发起请求**。部署有 Docker Compose 一键起全栈，也可以 `java -jar` 零依赖演示。」 |

### 8.2 演示风险与预案

| 风险 | 征兆 | 预案 |
| --- | --- | --- |
| 端口 8080 被占用 | 启动日志 `Port 8080 was already in use` | `$env:SERVER_PORT=8081` + 同步改 `vite.config.ts` 的代理目标；或提前杀掉占用进程 |
| 4 个窗口 Token 互相顶掉 | 某个窗口莫名跳回登录页 | 立刻改用无痕窗口；这是 `localStorage` 同源共享导致的 |
| WebSocket 连不上 | 标签一直「连接中…」 | 检查 `pnpm dev` 是否在 `frontend/` 目录启动；兜底方案：`frontend/.env.local` 写 `VITE_USE_SOCKJS=true` 后重启 |
| 前端白屏 | 控制台报模块找不到 | 提前 `pnpm build` 验证过；现场可改用 `pnpm preview`（`http://localhost:4173`） |
| 网络不通 | 投屏电脑没网 | 方式 ① 本身不需要网络；Maven 依赖提前 `mvn dependency:go-offline`，用 `java -jar` 启动 |
| 中文路径导致 `mvn spring-boot:run` 报 `ClassNotFoundException` | 启动即失败 | **改用 `java -jar target/werewolf-server.jar`**（见 [README 第十一节 Q6](../README.md)） |
| 演示中途有人掉线 | 某窗口卡住 | 刷新即可（TC-WS-02 已覆盖）；实在不行房主点「再来一局」（`POST /api/game/reset`） |
| 时间超了 | 讲到 9:00 还没到投票 | 直接跳到「9:00 自动化测试」环节，把未演示的环节用 TC 记录代替 |

### 8.3 评委最可能追问的 6 个问题（附答案要点）

| 问题 | 答案要点 | 代码位置 |
| --- | --- | --- |
| 「客户端能不能改身份？」 | 不能。`GameViewProjector.project` 是唯一出站投影，`players[].role` 只在 `state.isFinished()` 时填充；私密信息只走 `/user/queue` | `game/dto/GameViewProjector.java` |
| 「两个人同时点同一个按钮会怎样？」 | 第二次会被 `actedThisRound` / `voteCast` 拦下，返回 `4007` 或 `4009`；两次操作都串行经过同一个 `RoomRuntime` | `WerewolfGameEngine.actedThisRound/voteCast` |
| 「如果所有人都挂机会卡死吗？」 | 不会。`RoomRuntime.tick()` 每秒倒计时，到 0 调 `autoCompleteCurrentPhase()` 补合法默认行动再 `nextPhase()`；讨论阶段只跳过当前发言人并重置计时 | `room/runtime/RoomRuntime.onPhaseTimeout` |
| 「为什么不用 Redis / MQ？」 | 方案明确要求单体、不要微服务。内存态游戏状态 + `SimpMessagingTemplate` 的 `SimpleBroker` 已足够；`GameEventMessenger` 是所有出站消息的唯一出口，将来换 Redis 只需改这一个类 | `websocket/GameEventMessenger.java` |
| 「刷新页面状态怎么恢复的？」 | 路由守卫读 `localStorage` 的 Token 放行 → `roomStore.restoreCurrent()` 拉 `GET /api/rooms/current` → WebSocket 连上后 `publish('/app/game/resync')` 再补一次全量状态 | `stores/room.ts`、`websocket/stomp.ts` |
| 「AI 关了会不会影响系统？」 | 完全不影响。房间默认 `aiCount=0`，没有 AI 座位需要行动；即使有 AI 座位，它也走 `AiService.decide()` → `RuleAgent`（纯本地）→ `applyAction()`，**与真人完全相同的校验路径**，AI 从头到尾只能产出「建议行动 + 发言」，不能改任何状态 | `ai/AiService.java`、`room/runtime/RoomRuntime.runDueAiActions` |

---

## 九、缺陷记录模板与结论

### 9.1 缺陷记录模板

```markdown
### BUG-<编号> <一句话标题>

- **发现人 / 日期**：
- **环境**：profile（h2/mysql/docker）、浏览器版本、后端 commit
- **前置条件**：
- **复现步骤**：1. … 2. … 3. …
- **实际结果**：（附截图 / 报文 / 后端日志片段）
- **期望结果**：
- **错误码**：（若有）
- **严重度**：🔴 阻塞 / 🟡 一般 / 🟢 轻微
- **归属**：A / B / C / D / E / F
- **状态**：待修 / 修复中 / 已修复（附 PR 链接）/ 不修复（附理由）
```

### 9.2 缺陷与不一致清单汇总

| 分类 | 数量 | 状态 |
| --- | --- | --- |
| **运行时缺陷（F-01 ~ F-04）** | 4 条 | ✅ 全部已修复，均有回归用例（见 4.2.2） |
| 🟡 中（测试断言 / 文档与实现不一致 / 数据文案错误） | 3 条（D-01、D-02、D-10） | ✅ **全部已修复**（D-10 对应 F-05） |
| 🟢 低（语义差异、注释错误、前端类型不完整、行为特性） | 8 条（D-03 ~ D-09、D-11、D-12） | ✅ D-04/D-06/D-07/D-08/D-11/D-12 已修复；D-03/D-09 为语义说明，已记录 |
| 🔴 阻塞级未修复项 | **0 条** | — |

### 9.3 交付结论

| 项 | 结论 |
| --- | --- |
| 基础功能闭环（P0） | ✅ **实现完整**：70 条自动化用例（57 L1 + 13 L2）；另列 43 条手工用例覆盖第 1~11 条验收标准，执行记录待回填 |
| 自动化测试全绿 | ✅ **已达成**：最新 7 份 Surefire 报告合计 70 例、0 失败、0 错误、0 跳过 |
| 全量 `mvn test` 实测数据 | ✅ **已回填**（第四节 4.1 为当前报告；4.2 为历史记录与补充说明） |
| 端到端验收 | 📝 旧版真实 HTTP 脚本覆盖完整 4 人局；旧数字口径冲突，当前执行记录需单独登记 |
| AI 可选增强（P2） | ✅ 代码与素材完整保留，默认关闭，不影响基础功能 |
| 手工用例执行记录 | ⚠️ **待执行并回填** 6.2 的记录表（用例本身已写全，缺的是执行签字） |

---

> **文档维护约定**：`ErrorCode.java` 新增错误码时同步更新 [docs/API.md](API.md) 与前端 `src/types/index.ts`；`WsDestinations.java` 新增目的地时同步更新 [docs/WEBSOCKET.md](WEBSOCKET.md)；`RoleAssigner` 的角色发放策略变更时同步更新 [docs/GAME_RULES.md](GAME_RULES.md) 第六节与本文档第二节的用例清单。
