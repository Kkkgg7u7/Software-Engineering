> 2026-10-29 版本提示：本文含早期狼人杀设计与操作示例。当前交付范围、规则和生产运行方式以 [当前状态](JIUJIU_CURRENT_STATUS.md)、[运行与部署](RELEASE_AND_OPERATIONS.md) 及对应阶段报告为准；旧的角色/人数/多数决、禁止 WebRTC 或删除卷升级等说法已被新需求覆盖。

# 让不同玩家在自己电脑上联机游戏 · 部署指南

> 本文档面向**项目负责人（开服的人）**，解决一个具体问题：**怎么让别人用他们自己的电脑、自己的浏览器，连到你机器上的这个游戏，和你一起打一局。**
>
> 按"从易到难"排了四种方式，选一种即可。每种都给出了完整的命令、验证方法和常见坑。

---

## 零、先理解网络上的三个必要条件

不管用哪种方式，只要有一条不满足，就会出现**"页面能打开，但登录失败 / 收不到实时消息"**：

| 条件 | 说明 | 本次已做的工程改动 |
| --- | --- | --- |
| **1. 服务监听在所有网卡上** | 不能只监听 `127.0.0.1`（那样只有本机能连） | 后端 Spring Boot 默认监听 `0.0.0.0`；前端 `vite.config.ts` 已设 `server.host: '0.0.0.0'` ✅ |
| **2. 跨域放行对方的来源地址** | 别人的浏览器地址是 `http://192.168.1.23:5173` 之类，不在白名单就会被 CORS 拒绝 | 重写了 `CorsConfigurationSource`：按请求动态判定并把**真实的 Origin** 回填到 `Access-Control-Allow-Origin`，同时**自动放行本机所有网卡地址** ✅ |
| **3. WebSocket 握手同样要放行** | WebSocket 有独立的 Origin 校验，只改 CORS 不够，会表现为"接口正常但实时同步失效" | `WebSocketConfig` 两个端点均放行；真正的安全边界是 CONNECT 帧里的 JWT ✅ |
| **4. 防火墙放行入站端口** | Windows 默认拦截外部访问 | 需要手动加一条入站规则（下方有命令） |

> **为什么前端不用改代码就能自动连对后端**：`frontend/src/websocket/stomp.ts` 里的 `resolveWsUrl()` 在 `VITE_WS_URL` 留空时会用 `window.location.host` 推导 WebSocket 地址，`api/request.ts` 默认走相对路径 `/api`。所以**前端构建产物是"地址无关"的**，同一份 dist 拷到任何机器都能用，局域网 IP 变了也不用重新构建。

### 关于来源白名单：为什么不写死 IP

这里踩过一个真实的坑，值得说明：

> 最初的白名单只放行了 `localhost` 和 `192.168.*.* / 10.*.*.* / 172.16~31.*.*` 这些 RFC1918 私网段。
> 但本机实测的局域网地址是 **`203.0.113.45`** 这类**公网段地址**（示例地址；校园网/企业网很常见，
> 出口不一定是 192.168 开头）。结果就是：预检返回 403、登录被浏览器拦截、WebSocket 握手 403。
> 表现正是"页面能打开，但登录失败、收不到实时消息"。
>
> 注：`203.0.113.0/24` 是 RFC 5737 保留给文档示例的地址段，放在这里是为了演示"公网段地址"这一情形，
> 实际部署请替换成你自己的局域网 IP。

因此现在的实现改成两条腿走路：

| 机制 | 作用 |
| --- | --- |
| **自动放行本机所有网卡 IPv4** | 启动时枚举本机网卡地址，凡是访问"我自己"的 Origin 一律放行。宿舍 → 教室 → 热点换网络、IP 变了都不用改配置 |
| **`WEREWOLF_CORS_ORIGINS` 可配** | 仍可用环境变量精确收窄；也支持 `*` 一次性放行全部来源（临时演示用） |

排障提示：`GET /api/health` 会返回 `localAddresses` 字段，直接告诉别人该用哪个地址连你。


---

## 一、方案对比：先选一条路

| 方式 | 适用场景 | 对方需要 | 需要买服务器 | 需要改路由 | HTTPS | 难度 |
| --- | --- | --- | --- | --- | --- | --- |
| **A. 局域网直连** | 同一间宿舍 / 同一个教室 / 同一个 WiFi | 连同一个 WiFi | ❌ | ❌ | ❌ | ⭐ 最简单 |
| **B. 内网穿透（cloudflared）** | 舍友在别的网络、异地同学、答辩时给老师扫码 | 有公网 | ❌ | ❌ | ✅ 自动 | ⭐⭐ |
| **C. 云服务器 + Docker** | 想让项目"一直在线"，随时能玩 | 有公网 | ✅ 约 ¥60~100/月 | ❌ | 需自行配 | ⭐⭐⭐ |
| **D. 家庭宽带端口映射** | 有公网 IP 且不想用穿透 | 有公网 IP | ❌ | ✅ | ❌ | ⭐⭐⭐ |

**给你的建议**：
- **课程项目答辩 / 组内测试** → 选 **A**，5 分钟就能开始。
- **想让不在同一网络的队友加入** → 选 **B**，不用买服务器，还自带 HTTPS。
- **想要一个"永久地址"写进报告** → 选 **C**。

---

## 二、方案 A：局域网直连（推荐先跑通这个）

### A1. 在"开服的那台电脑"上启动后端

```powershell
# 强烈建议用文件库，这样别人注册的账号重启后还在
$env:WEREWOLF_DB_URL="jdbc:h2:file:./data/werewolf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"

cd E:\ds工作1-软工\werewolf-game\backend
java -jar target\werewolf-server.jar --spring.profiles.active=h2
```

```bash
# bash 版本
export WEREWOLF_DB_URL="jdbc:h2:file:./data/werewolf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"
cd werewolf-game/backend
java -jar target/werewolf-server.jar --spring.profiles.active=h2
```

看到 `Tomcat started on port 8080` 就成功了。

### A2. 查自己的局域网 IP

```powershell
ipconfig | Select-String -Pattern "IPv4"
```

```bash
# Linux / macOS
ip addr | grep "inet " || ifconfig | grep "inet "
```

记下形如 `192.168.1.23` 的地址（**不是** `127.0.0.1`）。

### A3. 放行防火墙端口

```powershell
# 需要以【管理员身份】打开 PowerShell
New-NetFirewallRule -DisplayName "Werewolf Backend 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
New-NetFirewallRule -DisplayName "Werewolf Frontend 5173" -Direction Inbound -Protocol TCP -LocalPort 5173 -Action Allow
```

```bash
# Linux（如果用 ufw）
sudo ufw allow 8080/tcp
sudo ufw allow 5173/tcp
```

### A4. 启动前端

**开发模式**（推荐，热更新，改代码即生效）：

```bash
cd werewolf-game/frontend
pnpm dev --host
```

启动后 Vite 会打印两行地址，**把 `Network:` 那一行发给队友**：

```
  ➜  Local:   http://localhost:5173/
  ➜  Network: http://192.168.1.23:5173/     <-- 把这个发给队友
```

**生产模式**（更省资源，适合正式演示前）：

```bash
cd werewolf-game/frontend
pnpm build:only                    # 产出 dist/
pnpm preview --host --port 4173    # 用 Vite 预览服务器托管 dist
```

### A5. 队友怎么加入

队友在自己电脑的浏览器打开 `http://192.168.1.23:5173`，然后：

1. 各自注册账号（或直接用 `demo` / `player1` / `player2` / `player3`，密码 `demo1234`）；
2. 一个人创建房间，把**房间码**（6 位）念给其他人；
3. 其他人在大厅「输入房间码加入」；
4. 全员点准备，房主点开始。

> **重要**：同一台电脑上开多个窗口测试时，**必须用隐私模式（无痕窗口）**，否则多个标签共享 localStorage 里的 Token，会变成同一个账号反复顶号。

### A6. 验证是否真的通了

在队友的电脑上（或你自己的浏览器）打开开发者工具 F12：

| 检查项 | 期望结果 | 不正常的典型表现 |
| --- | --- | --- |
| Network → `GET /api/health` | 200，返回 `{"code":0,...}` | 有红色 CORS 报错 → 检查 `WEREWOLF_CORS_ORIGINS` |
| Network → WS → `ws` 连接 | 状态 101 Switching Protocols | 403 → WebSocket Origin 未放行 |
| 页面顶部标签 | 显示「实时已连接」（绿点） | 一直「连接中…」→ 看上一条 |

---

## 三、方案 B：内网穿透（不买服务器，让异地的人也能连）

原理：一个隧道客户端在你这台机器上运行，主动连到公网服务，公网给你一个临时域名，别人访问这个域名就等于访问你的机器。

### B1. 用 cloudflared（推荐，免费且自动 HTTPS）

```powershell
# 安装（Windows，用 winget）
winget install --id Cloudflare.cloudflared
```

```bash
# macOS
brew install cloudflared
```

启动隧道，把**前端的 5173 端口**暴露出去（只暴露这一个就够了，因为前端会代理 `/api` 和 `/ws`）：

```bash
cloudflared tunnel --url http://localhost:5173
```

终端会打印一个地址，形如：

```
https://random-words-1234.trycloudflare.com
```

**把这个地址发给任何人，他们直接打开就能玩**——不需要和你同一个网络，还自带 HTTPS。

> 本项目已默认放行 `https://*.trycloudflare.com` 来源，所以**不需要改任何配置**。

### B2. 用 ngrok（备选）

```bash
ngrok http 5173
```

同样会给出一个 `https://xxxx.ngrok-free.app` 地址，本项目也已默认放行。

### B3. 穿透方式的注意事项

| 事项 | 说明 |
| --- | --- |
| **必须走前端端口** | 暴露 5173（或生产模式的 4173），不要只暴露 8080 —— 前端是入口，它负责把 `/api`、`/ws` 代理到后端 |
| **临时域名会变** | 每次重启 cloudflared 域名都不同，直接把新地址发群里即可 |
| **免费隧道有速率限制** | 4~8 人玩狼人杀（低频文本消息）完全够用；不要用它传大文件 |
| **延迟** | 会增加约 50~200ms，对回合制游戏无影响 |
| **别泄露** | 隧道开着的时候，任何拿到地址的人都能注册进来玩 |

---

## 四、方案 C：云服务器 + Docker（长期在线）

### C1. 服务器规格

| 项 | 建议 | 说明 |
| --- | --- | --- |
| CPU / 内存 | 2 vCPU / 4 GB | 方案第十四节建议值，足够 |
| 磁盘 | 40 GB SSD | 镜像 + MySQL 数据 |
| 带宽 | 3~5 Mbps | 狼人杀是低频文本，带宽要求很低 |
| 系统 | Ubuntu 22.04 / 24.04 | |
| 成本 | 约 ¥60~100 / 月 | 学生机更便宜 |

### C2. 部署步骤

```bash
# 1) 装 Docker（官方脚本）
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER && newgrp docker

# 2) 拉代码
git clone <你的仓库地址> werewolf-game
cd werewolf-game/deploy

# 3) 配置环境变量（务必改掉密钥！）
cp .env.example .env
vi .env
```

`.env` 里**至少**要改这两项：

```bash
# 随机生成一个至少 32 字节的密钥：openssl rand -base64 48
WEREWOLF_JWT_SECRET=<粘贴随机串>

# 改成你的真实域名或服务器 IP:端口，多个用逗号分隔
WEREWOLF_CORS_ORIGINS=https://werewolf.example.com
```

```bash
# 4) 一键启动
docker compose up -d --build

# 5) 确认三个容器都健康
docker compose ps

# 6) 看后端日志
docker compose logs -f backend
```

浏览器打开 `http://<服务器公网IP>` 即可。

### C3. 加 HTTPS（可选但推荐）

```bash
sudo apt install -y certbot
sudo certbot certonly --standalone -d werewolf.example.com
```

然后把证书路径填进 `deploy/nginx-standalone.conf` 的 HTTPS 段（文件里已有注释模板），重载 Nginx：

```bash
sudo nginx -t && sudo systemctl reload nginx
```

> HTTPS 之后，前端 WebSocket 会自动从 `ws://` 切成 `wss://`（因为 `resolveWsUrl()` 跟随页面协议），**不需要改任何前端代码**。

---

## 五、方案 D：家庭宽带端口映射

如果你家有**公网 IP**（可以问运营商，或看路由器 WAN 口地址是否与 `curl ifconfig.me` 一致）：

1. 路由器后台 → 端口转发（虚拟服务器）→ 把外网端口 `80`（或 `5173`）转发到内网 `192.168.1.23:5173`；
2. 防火墙放行对应端口；
3. 别人访问 `http://<你的公网IP>` 即可。

**坑**：
- 绝大多数家宽**没有公网 IP**（运营商 NAT），这条路走不通，请改用方案 B；
- 公网 IP 通常**会变**（动态 IP），需要 DDNS；
- **没有 HTTPS**，浏览器可能拦截部分功能；
- 把家里网络暴露到公网有安全风险，玩完记得关掉转发。

---

## 六、联机场景下的配置项速查

| 环境变量 | 默认值 | 什么时候要改 |
| --- | --- | --- |
| `WEREWOLF_JWT_SECRET` | 开发用固定串 | **对外提供服务时必须改**（否则别人可以伪造任意用户的 Token） |
| `WEREWOLF_CORS_ORIGINS` | 私网网段 + 穿透域名；**本机网卡地址自动放行** | 用自己的域名时收窄成 `https://your-domain.com`；临时演示可设 `*` 放行全部 |
| `WEREWOLF_DB_URL` | H2 内存库 | **想让账号持久化时改成文件库**（见 2.A1） |
| `SERVER_PORT` | `8080` | 端口冲突时改 |
| `WEREWOLF_AI_ENABLED` | `false` | 本轮基础功能交付不需要动 |
| `VITE_WS_URL` | 空（自动推导） | 仅当"前端与后端不同源且无反向代理"时显式指定 |
| `VITE_USE_SOCKJS` | `false` | 原生 WebSocket 被中间设备拦截时设 `true` |

### 已实测验证的联机能力

下面这些是用脚本模拟"别人的电脑"发起的真实请求，**11 项断言全部通过**：

| 验证项 | 结果 |
| --- | --- |
| 8080 监听在 `0.0.0.0`（而非仅 `127.0.0.1`） | ✅ |
| CORS 预检放行 `http://203.0.113.45:5173`（非私网段地址） | ✅ 200，回填真实 Origin |
| CORS 预检放行 `http://203.0.113.45:4173` | ✅ |
| CORS 预检放行 `http://localhost:5173` | ✅ |
| 真实跨域登录请求（带非私网段 Origin） | ✅ 200，`code=0` |
| 响应带 `Access-Control-Allow-Origin` 与 `Allow-Credentials` | ✅ |
| WebSocket 握手放行非私网段来源 | ✅ `HTTP/1.1 101` |
| WebSocket 握手放行 localhost 来源 | ✅ `HTTP/1.1 101` |
| H2 文件库落盘（账号重启不丢） | ✅ `data/werewolf.mv.db` |

---

## 七、故障排查手册

| 症状 | 最可能的原因 | 怎么排查 |
| --- | --- | --- |
| 页面完全打不开 | 服务没起 / 端口没放行 / IP 写错 | 在开服机器上 `curl http://localhost:5173`；确认队友用的是 `192.168.x.x` 而不是 `localhost` |
| 页面能开，但登录报网络错误 | **CORS 未放行对方来源** | F12 → Console 找 `has been blocked by CORS policy`；确认后端 `WEREWOLF_CORS_ORIGINS` 覆盖了队友的访问地址 |
| 页面能开、能登录，但看不到别人准备/发言 | **WebSocket 握手被拒** | F12 → Network → WS，看是否为 403；确认 `WebSocketConfig` 用的是 `setAllowedOriginPatterns` |
| 右上角一直"连接中…" | Token 失效 / 后端未启动 / 代理没转发 `/ws` | 重新登录；确认 Vite 代理或 Nginx 的 `/ws` 段配置了 `Upgrade` 头 |
| 重启后端后账号没了 | 用的是 H2 **内存**库 | 设置 `WEREWOLF_DB_URL` 为文件库（见 2.A1） |
| 重启后端后房间没了 | **正常现象**（房间状态在内存） | 见 `docs/DATABASE.md`：对局状态设计上不落库 |
| 队友访问很慢 / 频繁掉线 | WiFi 信号差 / 穿透带宽限制 | 换有线；或改用方案 B/C |
| 4 个人登录后变成同一个账号 | 同一浏览器多标签**共享 localStorage** | 用隐私模式，或用不同浏览器 |
| 手机上打不开 | 手机连的不是同一个 WiFi | 确认手机与电脑在同一网段；防火墙放行 |

---

## 八、安全提醒（对外提供服务前必读）

| 项 | 要求 |
| --- | --- |
| **JWT 密钥** | 必须换成随机值：`openssl rand -base64 48`。默认值是公开在源码里的，用它等于没有鉴权 |
| **房间密码** | 已用 BCrypt 哈希存储，可以放心 |
| **数据库密码** | Docker 部署时改掉 `.env` 里的 `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` |
| **AI API Key** | 本轮默认关闭 AI，无需配置；若要开启，密钥只放 `.env`，**不要提交到仓库** |
| **HTTPS** | 公网提供服务建议配证书，否则 Token 在链路上是明文 |
| **临时隧道** | 玩完记得 `Ctrl+C` 关掉，避免长期暴露 |
| **演示账号** | `demo` / `player1~4` 只在 h2 profile 下自动创建；mysql profile 不会写入，生产环境请自行注册 |

---

## 九、最省事的"开黑"清单（照抄即可）

**开服的人（1 次）**：
```powershell
# 1. 放行防火墙（管理员 PowerShell，只需一次）
New-NetFirewallRule -DisplayName "Werewolf 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
New-NetFirewallRule -DisplayName "Werewolf 5173" -Direction Inbound -Protocol TCP -LocalPort 5173 -Action Allow

# 2. 查自己的 IP，记下来（例如 192.168.1.23）
ipconfig | Select-String "IPv4"

# 3. 启动后端（文件库，账号可持久）
$env:WEREWOLF_DB_URL="jdbc:h2:file:./data/werewolf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"
cd E:\ds工作1-软工\werewolf-game\backend
java -jar target\werewolf-server.jar --spring.profiles.active=h2

# 4. 另开一个终端启动前端
cd E:\ds工作1-软工\werewolf-game\frontend
pnpm dev --host
```

**每个玩家（各自电脑）**：
1. 浏览器打开 `http://192.168.1.23:5173`
2. 注册自己的账号（密码自己定）
3. 等房主给房间码 → 大厅「输入房间码加入」→ 点准备

**房主**：创建房间（4 人 / 1 狼）→ 等大家进来 → 全部准备 → 开始游戏。

**异地玩家**：把第 4 步换成
```bash
cloudflared tunnel --url http://localhost:5173
```
然后把打印出来的 `https://xxx.trycloudflare.com` 发给他们。
