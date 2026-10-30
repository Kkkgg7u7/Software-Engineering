# 房间实时语音

首版采用浏览器音频 WebRTC mesh：每位真人与其他已加入者各建一条音频连接，最多 13 人。所有加入者处于同一个公共语音频道；不提供独立狼人语音或自动按发言阶段禁言。AI 无音频轨道。

## 房间与接口

Room/CreateRoomRequest/UpdateRoomRequest/RoomView 新增 voiceEnabled，默认 false；只有房主能修改。V007 为 rooms 增加 voice_enabled；H2 启动自动执行，已有 MySQL 按 database/migrations/V007__room_voice.sql 执行一次。

全部接口要求有效 JWT 和当前房间成员资格，路径前缀 `/api/rooms/{code}/voice`：

| 方法/路径 | 参数或返回 |
| --- | --- |
| GET / | enabled、roomCode、members；不返回 ICE 凭据 |
| POST /join | clientId（16–80 位客户端随机 ID）；返回 sessionId、members、iceServers、leaseSeconds=30 |
| POST /heartbeat | sessionId、muted；返回成员快照，前端每 8 秒续约 |
| POST /leave | sessionId；幂等，旧会话不可退出新会话 |
| POST /signal | sessionId、targetUserId、targetSessionId、type=OFFER/ANSWER/ICE、payload |

信令只通过接收者的 `/user/queue/voice` 返回 VOICE_SIGNAL；sender、roomCode 和双方 sessionId 均由服务端核对。VOICE_MEMBERS 同步昵称和静音状态；VOICE_CLOSED 通知释放音轨。禁止跨房间、非成员、失效会话、错误 SDP/ICE、过大报文；每会话每 10 秒最多 240 条信令。没有公共主题音频或信令广播。

低 userId 一方发起 offer；ICE 在 remoteDescription 就绪前排队，避免协商竞争。切换房间页到对局页保留通话；离房、被踢、关闭语音、退出登录、页面离开、持续网络故障时关闭连接并停止所有本地音轨。服务端 30 秒租约并每 5 秒清理，房间变化即时清理；登录版本变更也会回收会话。

## 基础设施

本地 localhost 可调试；远程麦克风访问需安全上下文，用户自行允许权限。生产提供 HTTPS/WSS，并配置 `JIUJIU_VOICE_ICE_SERVERS` JSON，例如 `[ {"urls":["stun:stun.example.com:3478"]}, {"urls":["turns:turn.example.com:5349"],"username":"由运维配置","credential":"由运维配置"} ]`。示例域名不可直接使用，真实凭据只放环境变量。默认 [] 无外部 ICE 服务，不能保证跨 NAT 公网连通。TURN 使用独立运维凭据，建议短期凭据并轮换；不要复用账号密码。13 人 mesh 的上行带宽和移动端性能需要真实设备容量验收。

依据：[WebRTC W3C 标准](https://www.w3.org/TR/webrtc/)、[MDN getUserMedia](https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia)、[MDN addIceCandidate](https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/addIceCandidate)。STOMP 仅传递协商消息，音频由 RTCPeerConnection 传输。

## 已执行与待执行

4 项后端集成测试覆盖私人房间授权、关闭开关、静音、幂等/替换/旧会话退出、跨房间伪造和报文边界、超时租约。真实 HTTP + STOMP 回归确认定向投递且旁观账号无泄漏，关闭后无法再发信令。

开发页 `frontend/tests/voice-loopback.html` 通过实际 VoiceClient 和两端 RTCPeerConnection 接收 RTP 合成音频，验证静音/取消静音、退出释放、权限拒绝、授权迟到清理；不申请真实麦克风权限。该页面不进入 Vite 生产入口。

尚待用户真实设备验收：麦克风音质、双设备跨网络、TURN 中继、移动浏览器后台/耳机行为，以及 13 人同时通话容量。不能据本机合成音轨测试声称这些项目已通过。
