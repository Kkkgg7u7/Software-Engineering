# 数据库（MySQL 8）使用说明

本目录是**生产 / 答辩演示**环境（`SPRING_PROFILES_ACTIVE=mysql`）的数据库脚本。
本地零依赖演示（`h2` profile）不需要这里的任何命令，H2 会用
`backend/src/main/resources/db/h2/schema.sql` 自动建表。

| 文件 | 作用 | 是否必须 |
| --- | --- | --- |
| `schema.sql` | 建库 + 建 5 张表（`users` / `rooms` / `room_players` / `messages` / `game_records`）+ 索引 + 注释 | 必须 |
| `seed.sql` | 可选种子数据：2 条示例对局记录；**不含用户**（演示账号由后端创建） | 可选 |
| `README.md` | 本文档 | — |

---

## 1. 前置条件

- MySQL **8.0** 及以上（脚本使用了 `utf8mb4_0900_ai_ci` 排序规则，MySQL 5.7 不支持）
- 有 `mysql` 命令行客户端（Windows 安装 MySQL 时勾选 Client，或使用 MySQL Shell / Workbench）
- 一个具备建库权限的账号（例如 `root`）

检查版本：

```bash
mysql --version
```

---

## 2. 执行建库脚本

### 2.1 一条命令（推荐）

```bash
# Windows PowerShell / CMD 与 Linux / macOS 通用
mysql -h 127.0.0.1 -P 3306 -u root -p < database/schema.sql
```

> 在仓库根目录 `werewolf-game/` 下执行；若在其它目录，把路径换成
> `schema.sql` 的绝对路径或先 `cd database`。

### 2.2 进入客户端后执行

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p
```

```sql
source database/schema.sql;   -- Windows 下路径分隔符写 / 或 \\ 均可
-- 或者：SOURCE E:/ds工作1-软工/werewolf-game/database/schema.sql;
```

### 2.3 可选：写入示例数据

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p werewolf < database/seed.sql
```

### 2.4 验证建库结果

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p -e "USE werewolf; SHOW TABLES; SELECT COUNT(*) AS game_records FROM game_records;"
```

期望看到 5 张表：

```
+--------------------+
| Tables_in_werewolf |
+--------------------+
| game_records       |
| messages           |
| room_players       |
| rooms              |
| users              |
+--------------------+
```

查看表结构（确认列名与后端实体一致）：

```sql
USE werewolf;
DESC users;
DESC rooms;
DESC room_players;
DESC messages;
DESC game_records;
```

---

## 3. 创建专用数据库账号（推荐）

应用默认使用 `werewolf / werewolf123`，生产环境请自行改强密码：

```sql
CREATE USER IF NOT EXISTS 'werewolf'@'%' IDENTIFIED BY 'werewolf123';
GRANT ALL PRIVILEGES ON `werewolf`.* TO 'werewolf'@'%';
FLUSH PRIVILEGES;
```

Docker Compose 部署时，`deploy/docker-compose.yml` 会用 `.env` 里的
`MYSQL_USER` / `MYSQL_PASSWORD` 自动创建同名账号，**不需要**手工执行本节 SQL。

---

## 4. 切换后端到 mysql profile

`backend/src/main/resources/application-mysql.yml` 的连接信息全部走环境变量，
默认值如下（不设置也能连上本机默认实例）：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `h2` | 必须设为 `mysql` |
| `MYSQL_HOST` | `127.0.0.1` | 数据库主机 |
| `MYSQL_PORT` | `3306` | 数据库端口 |
| `MYSQL_DATABASE` | `werewolf` | 库名 |
| `MYSQL_USER` | `werewolf` | 账号 |
| `MYSQL_PASSWORD` | `werewolf123` | 密码 |

### Windows PowerShell

```powershell
$env:SPRING_PROFILES_ACTIVE = "mysql"
$env:MYSQL_HOST = "127.0.0.1"
$env:MYSQL_PORT = "3306"
$env:MYSQL_DATABASE = "werewolf"
$env:MYSQL_USER = "werewolf"
$env:MYSQL_PASSWORD = "werewolf123"
$env:WEREWOLF_JWT_SECRET = "please-change-this-to-a-random-32-bytes-secret"

Set-Location backend
mvn spring-boot:run
```

### Linux / macOS

```bash
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_HOST=127.0.0.1
export MYSQL_PORT=3306
export MYSQL_DATABASE=werewolf
export MYSQL_USER=werewolf
export MYSQL_PASSWORD=werewolf123
export WEREWOLF_JWT_SECRET=please-change-this-to-a-random-32-bytes-secret

cd backend && mvn spring-boot:run
```

### 启动成功自检

```bash
curl http://localhost:8080/api/health
```

返回 `{"code":0,...,"data":{"status":"UP",...}}` 即表示数据库连接正常
（该接口无需 Token，见 `SecurityConfig`）。

> 注意：`mysql` profile 下 `spring.sql.init.mode=never`，应用**不会**自动建表，
> 所以必须先执行第 2 节的 `schema.sql`，否则启动后第一次访问接口会报
> `Table 'werewolf.users' doesn't exist`。

---

## 5. 演示账号说明

- `h2` profile（本地零依赖演示）：启动时由 `com.werewolf.config.DemoDataInitializer`
  自动创建 `demo` / `player1` / `player2` / `player3` / `player4`，密码统一 `demo1234`。
- `mysql` profile：**没有**预置账号（该初始化器只在 h2 profile 生效），
  请在登录页点击“注册”创建账号；4 人开黑时注册 4 个账号即可。
- 详细原因见 `seed.sql` 顶部注释：BCrypt 哈希必须由 `PasswordEncoder` 现场生成，
  不适合写死在 SQL 里。

---

## 6. 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| `ERROR 1273 (HY000): Unknown collation: 'utf8mb4_0900_ai_ci'` | MySQL 版本低于 8.0，请升级到 8.0+ |
| `ERROR 1045: Access denied for user` | 账号密码不对；确认 `MYSQL_USER` / `MYSQL_PASSWORD` 与授权语句一致 |
| `ERROR 2003: Can't connect to MySQL server on '127.0.0.1'` | MySQL 服务未启动，或端口不是 3306（改 `MYSQL_PORT`） |
| 启动报 `Public Key Retrieval is not allowed` | 连接串已带 `allowPublicKeyRetrieval=true`；若自行修改过 URL 请保留该参数 |
| 中文昵称乱码 | 库/表必须使用 `utf8mb4`；连接串已带 `characterEncoding=utf8`，不要改成 `latin1` |
| 重复执行 `schema.sql` | 脚本使用 `CREATE ... IF NOT EXISTS`，可直接重复执行，不会清空数据 |
