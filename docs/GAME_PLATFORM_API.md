# 游戏平台接口

均需 JWT。GET /api/games 返回已注册游戏目录；GET /api/games/{gameType} 返回说明与配置元数据。

POST /api/rooms/match：{gameType:"WEREWOLF",maxPlayers:6,werewolfCount:1,privateChatEnabled:true}。省略请求体保留旧版 4 人默认。用户已有房间时幂等返回原房间。

POST /api/rooms/{code}/invite：{clientMessageId:"UUID",targetUserId:2} 或 {clientMessageId:"UUID",conversationId:3}，两种目标只能选一种。返回会话消息，contentType=GAME_INVITE、relatedCode=房间码。发送重试复用同一 clientMessageId。

创建和修改房间可提交 allowInvites / privateChatEnabled；RoomView 提供 gameType、configuration、createdAt。邀请加入仍调用 /api/rooms/join，不绕过密码、人数、状态检查。会话消息使用现有 CHAT_MESSAGE 私有事件推送。


## AI 数量与座位一致性（2026-10-29 修复）
创建或更新时指定正数 aiCount 且未指定 allowAi，即启用 AI；显式 allowAi=false 会移除所有 AI 并将 aiCount 归零。默认数量为 0，无外部模型 Key 时使用规则 AI。修改前验证当前真人+AI 数量不超过 maxPlayers。新增 AI 自动准备。详见 AI_ROOM_FIX.md。


## 自由身份配置（2026-10-30）
创建/更新接收 roleCounts，例如 {"WEREWOLF":2,"HUNTER":2,"VILLAGER":2}；服务端严格校验整数、总数、阵营、单实例技能上限和房主权限。RoomView 与 ROOM_UPDATE 同步 roleCounts；meta 提供 maxCount 和 roleDefaults。更新可用 resetRoleCounts=true 恢复默认，并指定合法 werewolfCount；原匹配排除组合不相同的房间。所有座位必须补齐后开始，服务端按保存的身份人数洗牌发放。完整契约/限制见 CUSTOM_ROLES_AND_ICON_DELIVERY.md。
