# 六人小组交接索引

以下为后续维护责任建议，不代表虚构成员提交或开发工时。原 DOCX 保留，最新范围以 JIUJIU_CURRENT_STATUS.md 为准，包含独立聊天、墙和 WebRTC。

| 区域 | 接手重点 | 验收依据 |
| --- | --- | --- |
| A 账号/房间 | auth/user/room、登录版本、配置与匹配、邀请授权 | 账号/房间/平台集成测试 |
| B 游戏规则 | 原 WerewolfGameEngine、角色与死亡队列、警长/情侣、状态投影 | Standard13EngineTest / Standard13RuntimeTest |
| C 实时通信 | websocket/conversation/friendship/voice、会话权限、未读/幂等与媒体 | 私有 STOMP 回归、VoiceIntegrationTest |
| D AI | AiContextBuilder / Prompt / JsonParser / Service、只读上下文、超时/有界异步/预算 | LlmHttpIntegrationTest、RuleAgentTest |
| E 前端 | 四板块、响应式页面、房间/13 人交互、VoiceClient 与音轨生命周期 | 实际浏览器截图和 tests/voice-loopback.html |
| F 测试/运维 | H2/MySQL 迁移、数据库/媒体备份、TLS/STUN/TURN、发布包 | RELEASE_AND_OPERATIONS.md 与各阶段交付报告 |

当前外部验收事项：MySQL/Docker、真实域名/HTTPS、公网双设备语音/TURN、真实模型服务、容量和恢复演练。没有实际远端 Git 地址，未创建或伪造多人提交历史。
