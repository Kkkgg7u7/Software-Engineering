# 新图标与自由身份配置交付记录

2026-10-30。修改直接位于 E 盘原工程，保留原引擎与全部九种身份、技能和阶段。未增加原生安装程序；交付仍为 Vue 3 + Spring Boot 响应式 Web 及一体化 JAR。原三份 DOCX 不修改，本次按用户新需求增加配置能力。

## 图标

使用用户附件原图，原样保存在 frontend/public/assets/brand/jiujiu-original.png。没有重新绘制、另找图片或使用生成式替代。Pillow 仅按比例重采样及居中留白；PNG 尺寸为 16、32、48、64、128、180、192、256、512，favicon.ico 包含 16/32/48/64。CSS object-fit:contain 保持比例，沿用各处原圆角和占位尺寸。

更新浏览器 favicon、Apple 主屏图标、首屏加载、导航、登录页、404 页和游戏目录海报。海报保持原 16:9 区域，以 contain 完整显示方形原图。身份牌、职业图标、AI 头像和动画不替换。

## 功能与数据流

房间准备页增加九身份加减按钮（44×44 触屏目标）、各人数、实时总数/差额/合法性、预设选择和恢复默认。手动修改后显示“自定义身份”。改房间人数时只增减平民名额以尽量保留自选组合；平民不足以抵消时保留差额提示，由房主继续调整。默认牌组由后端 RoleConfiguration/原 RoleAssigner 提供，不在 Vue 重复硬编码人数。

房主编辑本地草稿，点击“保存配置”后经 PUT /api/rooms/{code} 提交 roleCounts；服务端先验证权限、阶段、整数/范围、总人数和阵营，再修改内存、数据库与 AI 座位，广播原 ROOM_UPDATE 给全房间。客人看到只读身份配置。未保存、非法总数、未满员或未准备时禁止开始。快捷增删 AI 在有未保存草稿时禁用，避免覆盖身份编辑。

roleCounts 是身份枚举名称到整数人数的映射，未列出的已知身份视为 0，未知身份、字符串/小数/布尔人数拒绝。Room.werewolfCount 由狼人+白狼王人数计算，避免两份配置矛盾。原客户端不传 roleCounts 时沿用旧默认预设；匹配仍匹配原预设，并排除身份组合不同的房间。

开局读取已保存配置，RoleConfiguration 生成完整列表，复用 RoleAssigner 的 Collections.shuffle 随机洗牌后逐座发放。同一段分配代码重置确认、存活、药水、情侣、守卫记录、查验和死亡技能状态；引擎/运行时同时清理轮次、投票、夜晚目标、翻牌、发言顺序、警长及死亡技能队列。重开保留房间配置，重新洗牌。

## 规则和限制

- 房间仍支持 4～13 人；身份合计必须等于本局人数，已入座人数也必须相等。需要一人试玩时添加本局人数减 1 个 AI。
- 狼人、平民、猎人、白痴、白狼王可为 0～本局人数，组合仍受总数和阵营校验。多猎人/白狼王使用原死亡技能队列，各自独立；多白痴独立免死并失去投票权。
- 预言家、女巫、守卫、爱神当前共享翻牌窗口/药水结算/守护目标/唯一情侣配对状态，因此明确限制为 0～1；前后端一致拦截，不会虚假接受多实例。
- 必须有狼人阵营和好人；狼人阵营不得达到或超过好人数，否则按现有人数压制胜负规则开局即满足胜利。没有额外的职业比例或必选神职限制。
- 13 人标准方案保留，也允许手动修改。13 人仍执行原首日警长选举；警长是职务，不是第十种发牌身份。其他核心规则，包括狼人一致击杀、药水、守护、情侣及死亡技能后判胜，保持不变。
- 夜晚参与者和行动直接按实际存活身份计算，关闭某身份不会产生该身份的无效等待环节。
- 房间仍存于单实例内存，重启不续局；本次增加身份配置持久化，不改变原部署边界。

## 数据库与升级

新增 V008__room_role_counts.sql。H2 自动重复安全执行，原记录 NULL 表示沿用默认。MySQL 老库备份后手动执行该迁移一次，新库 schema.sql 已加入 JSON 列；MySQL/Docker 本次未运行实测。MyBatis JacksonTypeHandler 读写 Map，并显式允许 NULL 更新，确保恢复默认真的清空自定义值。

API 新增：创建/更新请求 roleCounts；更新还可用 resetRoleCounts=true 与合法 werewolfCount 恢复原预设。RoomView 顶层及 configuration 均包含 roleCounts；/api/game/meta 提供角色上限 maxCount 和各人数/狼数的 roleDefaults。

## 验证

- 20 类、152 项后端测试，0 失败、0 错误、0 跳过；完整 mvn -Pweb clean package 通过。最终 UI 文案/窄屏样式调整后重新类型检查、构建前端并打包一体化 JAR。
- 新增 13 项测试：100 个随机种子逐身份核对精确数量/随机性与玩家状态重置；全部非法边界；无神职整局；双猎人连锁/双白狼王分别带人/多白痴；创建、房主权限、数据库读回、广播视图一致、失败原子性、13 人非标准组合、预设恢复、匹配隔离、结束后重置和再开。
- 其中实际 AI 运行时对 4/8/13 人三种自定义牌组、各 6 个种子、每种两局，完成 36 局至 END 并重置；保留原 12 局标准 13 人回归。
- 真实 HTTP/STOMP 验证房主保存→客人收到身份人数更新、越权拒绝、非法修改不改变原配置、六人自定义开局及隐藏身份保护；集成包中所有 PNG 尺寸、favicon、Apple 图标和海报引用实际返回正确资源。
- 浏览器实际点击加减、0 人禁用、缺/超人数禁止开始、恢复默认、补齐 5 AI、保存“2 狼+2 猎人+2 平民”，进入真实发牌/身份确认。桌面和窄屏截图随交付保存；无真实麦克风授权或外部模型调用。

## 文件清单

后端：game/domain/RoleConfiguration.java（新增）、RoleAssigner.java；game/engine/WerewolfGameEngine.java；game/GameController.java、WerewolfDefinition.java；room/RoomService.java、entity/Room.java、runtime/RoomRuntime.java；room/dto/CreateRoomRequest.java、UpdateRoomRequest.java、RoomView.java、RoleCountDeserializer.java（新增）。

前端：src/components/RoleCountsEditor.vue（新增）、src/game/roleConfiguration.ts（新增）、src/views/RoomView.vue、GamesView.vue、LoginView.vue、NotFoundView.vue；src/types/index.ts、src/App.vue、index.html；public/assets/brand/jiujiu-original.png、jiujiu-{16,32,48,64,128,180,192,256,512}.png、public/favicon.ico。

迁移/配置：backend/src/main/resources/application-h2.yml、db/h2/migrations/V008__room_role_counts.sql（新增）；database/schema.sql、migrations/V008__room_role_counts.sql（新增）。

测试：game/engine/CustomRoleConfigurationTest.java（新增）；GamePlatformIntegrationTest.java、Standard13RuntimeTest.java、UserProfileMigrationTest.java。

文档：本文件、GAME_PLATFORM_API.md、GAME_RULES.md、JIUJIU_CURRENT_STATUS.md、RELEASE_AND_OPERATIONS.md、AI_ROOM_FIX.md、database/migrations/README.md。具体差异可对照 Codex 工作区 work/role-config/backup 中修改前的备份；原工程没有 Git，不伪造提交。

最终手机验收：390 与 320 像素视口均无横向滚动溢出；身份按钮 44×44，保存后可开始，浏览器错误日志为空。仅对准备页邀请栏和开关容器增加窄屏换行，桌面布局保持原样。最终截图：jiujiu-custom-roles-desktop.png / jiujiu-custom-roles-mobile.png。
