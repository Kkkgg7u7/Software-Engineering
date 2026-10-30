# 发布与运维（当前版本）

## 本地一体化版本

先在 frontend 执行 `pnpm install --frozen-lockfile`、`pnpm build`，再在 backend 执行 `mvn -Pweb clean package`。会将前端 dist 作为 static 资源放入原 Spring Boot JAR，HTTP/API/WebSocket 使用同一端口；刷新 /chat、/games、/wall、/me、房间和邀请深链接会回到 SPA，API 仍需 JWT。

发布包包括 jiujiu-server.jar、Start-Local.ps1、web 静态文件与交付文档。安装 Java 17 或更高版本后，在解压目录执行 `./Start-Local.ps1`，浏览器访问 http://127.0.0.1:8080；端口占用时执行 `./Start-Local.ps1 -Port 18081`。Ctrl+C 停止。脚本仅绑定回环地址，H2 文件与图片保存在包内 data；首次本地演示账号 demo / demo1234，也可自行注册。备份整个 data 目录，备份数据库文件前先停进程。

本地包不等于公网发布。生产不要启用 h2 或演示账号，应使用 mysql,prod、独立密钥与 HTTPS。

## 生产配置

1. 准备持续在线服务器、真实域名、受信任证书及 MySQL 8。完整源码中的 deploy/.env.example 是空密钥模板；PowerShell 7 可执行 `./Initialize-Environment.ps1 -Origin https://你的真实域名` 生成独立随机密钥，脚本不会覆盖已有 .env，也不打印密钥。
2. 使用 `SPRING_PROFILES_ACTIVE=mysql,prod`。prod 会拒绝开发 JWT、低强度密码、h2 和通配/HTTP CORS 来源。WEREWOLF_JWT_SECRET 至少 48 字符，MYSQL_PASSWORD 至少 16 字符，均只放运行环境。公开健康检查不再返回本机网卡和模型服务地址。
3. MySQL 新库使用 database/schema.sql（当前脚本库名为 werewolf）；已有库先备份，再按 database/migrations/V002–V008 顺序执行尚未应用的迁移。不要在已有库重复执行完整初始化或已应用的 ALTER 迁移，也不要删除数据卷来升级。H2 增量脚本支持重复执行。
4. Compose 的后端端口仅映射到 127.0.0.1；MySQL 不公开端口；media-data 保留图片，mysql-data 保留数据库。需要同时备份两者。直接裸机运行须把 JIUJIU_MEDIA_DIRECTORY 指向持久目录。
5. 已有可信 TLS 入口时使用 deploy/docker-compose.yml；否则将真实 fullchain.pem / privkey.pem 放入 deploy/certificates，修改 deploy/nginx-https.conf 两处示例域名，再执行 `docker compose -f docker-compose.yml -f docker-compose.https.yml up -d --build`。该覆盖配置保留 80→HTTPS 跳转并提供 443。裸机示例见 nginx-https-standalone.conf。不要将测试自签名证书当作生产证书。
6. Nginx 允许 8MB 请求，覆盖应用的 5MB 图片上限和 multipart 开销；登录/注册按来源 IP 限流。反代应保留 /api、/ws，开启 WebSocket Upgrade，语音媒体不经过 STOMP。已有上游代理时应明确配置可信源 IP，不能信任客户端随意提供的转发头。
7. 按 VOICE_API.md 配置 JIUJIU_VOICE_ICE_SERVERS，生产需要可达的 STUN/TURN，默认 [] 只适合本机/局域网调试。实际 TURN 凭据不要放入 VITE_ 变量或仓库；浏览器会在鉴权加入后获得所需 ICE 凭据，建议运维使用短期专用凭据。
8. 外部模型仅从后端读取 WEREWOLF_AI_ENABLED / BASE_URL / API_KEY / MODEL。缺少 Key 使用规则 AI。网络请求有连接/读超时和有界异步队列，失败与非法输出按合法动作兜底，单局预算统计请求尝试（包括失败），旧回合结果不会写入新回合。

## 上线后验收

用两台不同网络设备分别注册，验证好友/群聊/图片/动态、匹配/邀请/密码房、完整对局、断线重连和语音。检查媒体在容器重建后仍可读；离群/删帖/踢人后权限失效；语音退出后浏览器麦克风指示消失。验证 TURN relay 候选、HTTPS/WSS、证书续期及数据库/媒体联合恢复。当前没有这些外部环境的通过记录。

## 已验证范围

H2 全量测试、前端类型检查/构建、一体化 JAR 本机启动、真实 HTTP/STOMP 授权与消息/图片/未读、浏览器页面和本机合成音轨 WebRTC。4 份 Nginx 配置已通过官方 Windows Nginx 1.30.6 的 -t；验证时仅将 Docker 内部主机名替换为本机地址，并用隔离测试证书校验 SSL 配置，未启动公网监听。[Nginx 官方下载来源](https://nginx.org/en/download.html)。Docker 镜像和 MySQL 尚未运行验证。
