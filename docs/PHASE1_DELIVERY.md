# 啾啾阶段 1 交付记录

日期：2026年10月27日。完成平台基础、个人中心、账号安全与房间访问控制。

## 本阶段交付

- 啾啾品牌基础和游戏/我的导航；桌面横向导航、手机底部导航，个人中心 /me。
- 本人昵称、默认头像/头像 URL、个性签名的真实读取与保存；显示注册时间、更新时间、战绩；加载/失败/表单校验反馈。
- POST /api/users/me/password：验证原密码，BCrypt 新密码，原子提升认证版本；事务提交后关闭旧 WebSocket（4002），旧 JWT 不再有效。
- HTTP 私人房间详情及按码游戏状态限制成员；房间主题订阅限制成员，投递时再次校验，退出后停止旧订阅。公开房间列表/元信息继续可用。
- 登录、资料和战绩采用局部字段更新，防止并发回写旧密码；退出/强制失效清理前端房间、游戏和聊天状态。
- users 新增 signature、updated_at；H2 V002 自动增量升级，MySQL V002 手动脚本与迁移说明。

## 修改范围

后端：auth/AuthService、CredentialsChanged、AuthResponse；user/UserController、User entity；room/RoomAccessPolicy、RoomController、RoomService（仅战绩更新字段）；game/GameService（私人查询权限）；websocket/WsAuthChannelInterceptor、RoomTopicAccessInterceptor、WsSessionRegistry、WsSessionCloser；config/WebSocketConfig。

数据库：H2 schema、application-h2.yml、migrations/V002__user_profile.sql；database/schema.sql、database/migrations/mysql/V002__user_profile.sql、迁移 README。

前端：ProfileView、App、router、auth API/store、room store本地清理、types、main、stomp、index、LoginView品牌文字。API、WebSocket、数据库和测试文档已补充当前契约。

## 验证结果

- Maven 全量测试：10 类、79例，0失败、0错误、0跳过。
- 前端 vue-tsc --noEmit 和生产构建通过（最后样式调整后已再次构建通过）。
- H2 新库/旧表执行增量脚本两次，旧用户、密码哈希和注册时间保留。
- 真实 HTTP/WebSocket：非成员查询/订阅拒绝；成员可收消息；退出后旧订阅不再收到；Unicode签名保存；改密旧连接关闭4002；旧JWT失效；其他账号正常；新密码可登录。
- 浏览器实际保存签名/选择头像、刷新保留，390px手机和1440px桌面布局检查；手机无横向溢出；无页面控制台错误。截图作为交付证明。

## 边界

现有 backend/data 文件库未打开或迁移，测试使用独立内存库。MySQL脚本尚未在真实MySQL实例执行。没有执行部署、外部LLM或真实手机麦克风操作。

好友、图片上传、群聊、动态、游戏邀请、13人规则和语音按后续阶段交付。个人中心当前不显示尚未实现的虚假社交入口。原始修改前文件备份于本轮工作目录 work/phase1/backup。
