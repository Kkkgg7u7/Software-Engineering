# 部署与运行指南（deploy/README.md）

本项目提供三种运行方式，按“从简到全”排列：

| 方式 | 适用场景 | 依赖 | 前端地址 | 后端地址 |
| --- | --- | --- | --- | --- |
| **A. 本地零依赖演示（h2）** | 开发、答辩现场、断网环境 | JDK 17 + Node 18/20 | http://localhost:5173 | http://localhost:8080 |
| **B. 本地 MySQL（mysql）** | 需要真实落库 / 交作业演示数据库 | 上面的 + MySQL 8 | http://localhost:5173（或 Nginx :80） | http://localhost:8080 |
| **C. Docker Compose 一键部署** | 服务器 / 验收环境 | Docker + Compose V2 | http://localhost | 容器内 8080 |

> **当前版本定位（重要）**：只做基础功能（P0 核心闭环）—— 注册登录、房间、4 人完整对局（夜晚/白天/讨论/投票/胜负）、实时同步。
> **AI NPC 默认全部关闭**（`werewolf.ai.enabled=false`、`CreateRoomRequest.allowAi` 默认 `false`、`aiCount` 默认 `0`），
> 一局完全由真人浏览器窗口完成；需要 AI 陪玩时见文末「进阶：开启 AI NPC」。

---

## 0. 准备：工具版本与仓库结构

```
werewolf-game/
├── backend/           Spring Boot 3.3 + Java 17（Maven 项目，无 mvnw）
├── frontend/          Vue3 + Vite（pnpm 项目，有 pnpm-lock.yaml）
├── database/          MySQL 8 建库脚本（schema.sql / seed.sql）
└── deploy/            ← 本目录：Dockerfile 编排、Nginx 配置、本说明
```

需要的最小工具集：

| 工具 | 版本 | 检查命令 |
| --- | --- | --- |
| JDK | 17 及以上（本项目按 17 编译/运行） | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Node.js | 18 / 20（Vite 5 要求） | `node -v` |
| pnpm | 9 / 10（也可用 npm 兜底） | `pnpm -v` |
| MySQL | 8.0（仅方式 B/C） | `mysql --version` |
| Docker + Compose | Compose V2（仅方式 C） | `docker compose version` |

没有全局 Maven 时，可以下载解压后用绝对路径调用，例如 Windows：

```powershell
& "E:\tools\apache-maven-3.9.9\bin\mvn.cmd" -v
```

---

## 方式 A：本地零依赖演示（h2 profile，最推荐）

**特点**：H2 内存库，启动即自动建表；演示账号由后端 `DemoDataInitializer` 自动创建；不需要 MySQL、不需要网络、不需要任何 AI Key。

### A1. 启动后端（终端 1）

```powershell
# Windows PowerShell
Set-Location werewolf-game\backend
$env:SPRING_PROFILES_ACTIVE = "h2"
mvn spring-boot:run
```

```bash
# Linux / macOS
cd werewolf-game/backend
export SPRING_PROFILES_ACTIVE=h2
mvn spring-boot:run
```

> `application.yml` 里 `spring.profiles.active` 默认就是 `h2`，所以直接 `mvn spring-boot:run` 也可以。

**验证后端已就绪**：

```bash
curl http://localhost:8080/api/health
# 期望：{"code":0,"message":"ok","data":{"status":"UP","service":"werewolf-server",...}}
```

### A2. 启动前端（终端 2）

```bash
cd werewolf-game/frontend
pnpm install            # 首次执行；pnpm 不可用时用 npm install
pnpm dev                # 等价于 vite，端口 5173
```

`vite.config.ts` 已把 `/api` 与 `/ws` 代理到 `http://127.0.0.1:8080`，因此**前端不需要额外配置跨域**。

### A3. 验证（单窗口）

1. 浏览器打开 <http://localhost:5173>；
2. 用演示账号登录：**`demo` / `demo1234`**（另有 `player1` ~ `player4`，密码相同）；
3. 创建房间：人数 `4`、狼人 `1`、AI 数量 `0`、公开房；
4. 进入房间页，点「准备」，因为人数不足 4 人此时「开始游戏」会被后端拒绝（错误码 3008/3007，属正常现象）。

### A4. 验证（4 个浏览器窗口模拟多人，答辩主线）

内存库 + 同源 Cookie/Token 都在 `localStorage`，所以**每个窗口必须是独立会话**：

| 窗口 | 建议做法 | 登录账号 |
| --- | --- | --- |
| 窗口 1 | 普通窗口 | `demo` / `demo1234` |
| 窗口 2 | 隐私/无痕窗口（InPrivate、Incognito） | `player1` / `demo1234` |
| 窗口 3 | 另一种浏览器（Edge）或第二个隐私窗口 | `player2` / `demo1234` |
| 窗口 4 | 第三种浏览器（Chrome）或第三个隐私窗口 | `player3` / `demo1234` |

> 同一浏览器里开两个普通标签页会共用 `localStorage`，第二个标签会被识别成同一个人，请务必用隐私窗口或不同浏览器。

操作顺序（每一步都可在 4 个窗口同时看到变化）：

1. 窗口 1：大厅 →「创建房间」→ 人数 4、狼人 1、AI 0、公开 → 记下 6 位房间码；
2. 窗口 2/3/4：大厅 → 输入房间码「加入房间」（座位号依次为 2/3/4）；
3. 3 位非房主玩家点「准备」→ 房主的「开始游戏」变可用；
4. 窗口 1 点「开始游戏」→ 4 个窗口都进入发牌动画，**各自只看到自己的身份**；
5. 夜晚：狼人窗口出现刀人面板；预言家出现查验面板；女巫出现药水面板；平民只看到「天黑请闭眼」；
6. 天亮 → 讨论（按座位顺序发言，只有当前发言人能输入）→ 投票（实时票数）→ 结算；
7. 循环若干轮直到弹出结算面板，4 个窗口的胜负结果必须一致。

**命令行验证（可选）**：

```bash
# 注册 -> 拿 token -> 建房（把 <TOKEN> 换成返回的 data.token）
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"tester01","password":"demo1234","nickname":"命令行玩家"}'

curl -X POST http://localhost:8080/api/rooms \
  -H "Content-Type: application/json" -H "Authorization: Bearer <TOKEN>" \
  -d '{"roomName":"命令行房","maxPlayers":4,"werewolfCount":1,"aiCount":0,"allowAi":false,"publicRoom":true}'

# 未登录访问受保护接口 -> 业务码 1001
curl http://localhost:8080/api/users/me
```

### A5. 跑测试（不需要数据库）

```bash
cd werewolf-game/backend
mvn test
# 期望最后几行：Tests run: xx, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
```

集成测试自带 `@ActiveProfiles("h2")`，用 MockMvc 跑真实 Spring 上下文，不依赖外部服务。

---

## 方式 B：本地 MySQL（mysql profile）

### B1. 建库建表

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p < werewolf-game/database/schema.sql
# 可选：2 条示例对局记录
mysql -h 127.0.0.1 -P 3306 -u root -p werewolf < werewolf-game/database/seed.sql
```

验证：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p -e "USE werewolf; SHOW TABLES;"
# 期望 5 张表：users / rooms / room_players / messages / game_records
```

更多细节（专用账号授权、常见报错）见 [`database/README.md`](../database/README.md)。

### B2. 启动后端（切到 mysql profile）

```powershell
# Windows PowerShell
Set-Location werewolf-game\backend
$env:SPRING_PROFILES_ACTIVE = "mysql"
$env:MYSQL_HOST = "127.0.0.1"
$env:MYSQL_PORT = "3306"
$env:MYSQL_DATABASE = "werewolf"
$env:MYSQL_USER = "werewolf"
$env:MYSQL_PASSWORD = "werewolf123"
$env:WEREWOLF_JWT_SECRET = "please-change-this-to-a-random-32-bytes-secret"
mvn spring-boot:run
```

```bash
# Linux / macOS
cd werewolf-game/backend
export SPRING_PROFILES_ACTIVE=mysql MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 \
       MYSQL_DATABASE=werewolf MYSQL_USER=werewolf MYSQL_PASSWORD=werewolf123 \
       WEREWOLF_JWT_SECRET=please-change-this-to-a-random-32-bytes-secret
mvn spring-boot:run
```

> `mysql` profile 下应用**不会**自动建表（`spring.sql.init.mode=never`），必须先执行 B1。

### B3. 前端 + 验证

前端启动方式与方式 A2 完全相同（`pnpm dev`，<http://localhost:5173>）。

**登录账号差异（重要）**：

- `h2` profile：`demo` / `player1`~`player4`（密码 `demo1234`）由 `DemoDataInitializer` 自动创建；
- `mysql` profile：**没有预置账号**（该初始化器只在 h2 profile 生效），
  请在登录页点「注册」自行创建 4 个账号（4 人开黑就注册 4 个），再按 A4 的 4 窗口流程操作。

### B4. 用 Nginx 提供前端静态页（可选，接近生产）

```bash
cd werewolf-game/frontend
pnpm install
pnpm build:only          # 产物在 frontend/dist

# 把 dist 放到 Nginx 站点根目录，例如 /var/www/werewolf
sudo mkdir -p /var/www/werewolf && sudo cp -r dist/* /var/www/werewolf/

# 使用仓库自带的示例站点配置（含 /api 与 /ws 反代、SPA history 回退、HTTPS 注释）
sudo cp ../deploy/nginx-standalone.conf /etc/nginx/conf.d/werewolf.conf
sudo nginx -t && sudo systemctl reload nginx
```

访问 <http://localhost>（或配置里的 `server_name`）即可，`/api` 与 `/ws` 由 Nginx 转发到 `127.0.0.1:8080`。

---

## 方式 C：Docker Compose 一键部署

### C1. 启动

```bash
cd werewolf-game/deploy
cp .env.example .env        # Windows: Copy-Item .env.example .env
# 编辑 .env：至少改掉 MYSQL_ROOT_PASSWORD / MYSQL_USER / MYSQL_PASSWORD / WEREWOLF_JWT_SECRET
docker compose up -d --build
```

首次启动做的事：

1. `mysql` 容器初始化数据目录，并执行挂载进去的 `database/schema.sql` 建库建表；
2. `mysql` 健康检查通过后（`mysqladmin ping`），`backend` 才开始构建/启动；
3. `frontend` 用 `node:20-alpine` 构建静态产物，再由 `nginx:1.27-alpine` 托管，并把 `/api`、`/ws` 反代到 `backend:8080`。

### C2. 查看状态与日志

```bash
docker compose ps                 # 三个服务都应 Up，mysql 应为 healthy
docker compose logs -f backend    # 看 Spring Boot 启动日志
curl http://localhost:8080/api/health   # {"code":0,...,"status":"UP"}
curl -I http://localhost/               # 200 OK（前端）
```

### C3. 验证

1. 浏览器打开 <http://localhost>（`WEB_PORT` 改了就用对应端口）；
2. 首次使用：点「注册」创建账号（Docker 走 mysql profile，**没有 demo 预置账号**）；
   - 想直接用 `demo/demo1234`：改为方式 A（h2 profile），或在 `deploy/docker-compose.yml` 里把
     `SPRING_PROFILES_ACTIVE` 改成 `h2` 临时演示（数据不落盘，重启即清空）。
3. 按 A4 的表格开 4 个浏览器窗口（`demo`/`player1`/`player2`/`player3` 或你注册的 4 个账号）完成一局。

### C4. 常用运维命令

```bash
docker compose restart backend                 # 只重启后端
docker compose up -d --build backend           # 后端代码改动后重建
docker compose down                            # 停止并删除容器（保留 MySQL 数据卷）
docker compose down -v                         # 连数据卷一起删（下次启动重新执行 schema.sql）
docker compose exec mysql mysql -uwerewolf -p werewolf -e "SELECT COUNT(*) FROM users;"
```

### C5. 对外网 / HTTPS

最省事的做法是让宿主机的 Nginx 或云负载均衡做 TLS，再转发到 `http://127.0.0.1:80`
（即 `frontend` 容器），或用 `deploy/nginx-standalone.conf` 里的 HTTPS 段落做模板。
此时：

- `WEREWOLF_CORS_ORIGINS` 填对外域名，例如 `https://werewolf.example.com`；
- 前端 WS 会自动使用 `wss://<当前域名>/ws`（同源），一般**不需要**设置 `VITE_WS_URL`。

---

## 三个服务的排障清单

| 现象 | 排查 |
| --- | --- |
| 后端启动报 `Table 'werewolf.users' doesn't exist` | mysql profile 下没执行 `database/schema.sql`（方式 B1 / Compose 的初始化卷） |
| 后端启动报 `werewolf.jwt.secret 至少需要 32 字节` | 把 `WEREWOLF_JWT_SECRET` 换成 ≥32 字节的随机串 |
| 前端 502 Bad Gateway | 后端没起来或容器名不是 `backend`（`docker compose ps` / `docker compose logs backend`） |
| 前端能打开但接口 404 | Nginx 的 `location /api/` 与 `proxy_pass` 路径必须都带 `/api/`（仓库配置已正确，勿只改一处） |
| WebSocket 一直重连 | 确认反代带了 `Upgrade`/`Connection` 头与 `proxy_read_timeout 3600s`；企业代理屏蔽 WS 时把 `VITE_USE_SOCKJS=true` 重新构建前端 |
| 4 个窗口互相顶号 | 用隐私窗口 / 不同浏览器，避免共享 `localStorage` 里的 token |
| MySQL 改了 schema 但不生效 | `docker-entrypoint-initdb.d` 只在数据卷为空时执行：`docker compose down -v` 后重来 |

---

## 部署前确认

`frontend/index.html` 已存在，本轮前端类型检查与 `vite build` 均通过。

1. **`demo/demo1234` 只在 h2 profile 存在**：`DemoDataInitializer` 标注了 `@Profile("h2")`，
   mysql profile（含 Docker 部署）下需要自行注册账号。这是刻意设计（BCrypt 哈希由
   `PasswordEncoder` 现场生成），`database/seed.sql` 顶部有说明。
2. **AI 默认关闭**：`werewolf.ai.enabled=false`，房间 `allowAi` 默认 `false`。
   不显式传 `allowAi=true` 时不会创建 AI 座位 —— 用接口/前端建房做 4 人局时请把 AI 数量留 0。
3. **后端容器没有 HEALTHCHECK**：`eclipse-temurin` 基础镜像不保证带 `curl`/`wget`，
   所以健康检查放在编排层或人工验证（`curl http://localhost:8080/api/health`）。

---

## 进阶：开启 AI NPC（可选，不是基础功能的依赖）

1. 在 `.env`（或本地环境变量）里设置：

   ```bash
   WEREWOLF_AI_ENABLED=true
   WEREWOLF_AI_API_KEY=sk-xxxxxxxxxxxxxxxx      # 不填则自动降级为“规则 AI”
   WEREWOLF_AI_BASE_URL=https://api.deepseek.com
   WEREWOLF_AI_MODEL=deepseek-chat
   ```

2. 建房时显式打开 AI：`allowAi=true`、`aiCount=3`（人数 4、狼人 1）。
   - 接口示例：

     ```bash
     curl -X POST http://localhost:8080/api/rooms \
       -H "Content-Type: application/json" -H "Authorization: Bearer <TOKEN>" \
       -d '{"roomName":"AI 陪玩房","maxPlayers":4,"werewolfCount":1,"aiCount":3,"allowAi":true,"publicRoom":true}'
     ```

   - 只有 1 个真人也能开局：房主不需要准备，3 个 AI 座位由后端自动标记为已准备。
3. AI 行为边界（答辩可强调）：AI 只产出“建议行动 + 发言”，
   经 `AiJsonParser` 格式校验 -> `AiService` 降级策略 -> `WerewolfGameEngine` 规则校验后才生效；
   未配置 Key、超时、输出非法时**自动回退规则 AI**，断网也能跑完一局。
4. 阶段时长想调快（演示用）：改 `backend/src/main/resources/application.yml` 里的
   `werewolf.phase.*-seconds`（发牌 6s、夜晚 30s、讨论 40s、投票 25s）。
