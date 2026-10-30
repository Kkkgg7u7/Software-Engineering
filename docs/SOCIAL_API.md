# 社交与媒体 API

阶段 2 契约，2026年10月27日。除标明公开的头像下载外，所有接口均使用现有 JWT 身份、ApiResponse 信封。分页为 {items,total,page,size}，page 从1开始，size范围1—50，默认20。

## 公开资料与好友

| 方法 | 路径 | 请求 | 结果和权限 |
| --- | --- | --- | --- |
| GET | /api/users/search | q，page，size | 登录用户按昵称/登录名/数字ID查询；q长度1—50；返回公开资料分页 |
| GET | /api/users/{id} | 无 | 登录用户读取公开资料；不返回登录名、密码或认证版本 |
| GET | /api/friends | page，size | 本人的已接受好友分页；双方共用同一关系 |
| POST | /api/friends/requests | {targetUserId} | 发起好友申请，重复待处理申请幂等返回同一ID |
| GET | /api/friends/requests | direction=incoming/outgoing，page，size | incoming仅本人收到的待处理申请；outgoing为本人发出的当前申请状态 |
| POST | /api/friends/requests/{id}/accept | 无 | 只有接收人能接受，重复相同操作幂等 |
| POST | /api/friends/requests/{id}/reject | 无 | 只有接收人能拒绝，重复相同操作幂等 |
| DELETE | /api/friends/{userId} | 无 | 解除双方好友关系；数据库保留DELETED状态 |

PublicUser字段：id、nickname、avatar、signature、online、totalGames、winGames。online根据后端当前WebSocket注册表计算，是查询时快照。

申请字段：id、requester、recipient、status、createdAt。状态PENDING/ACCEPTED/REJECTED/DELETED。好友由规范化user_low/user_high唯一约束保存，不能自己加自己；反向已有待处理申请时提示先处理，不自动接受；已是好友不能再次申请；拒绝或删除后可以重新申请。

错误沿用1000参数/业务冲突、1001未登录、1002无权限、1003资源不存在、2002用户不存在。好友关系不会授予私人房间访问权。

事务提交后向两个参与者的 /user/queue/social 发送 {type:'FRIEND_UPDATE',data:{refresh:true},timestamp}；客户端重拉本人列表。仅使用Spring用户队列，客户端不能指定其他人的ID。

## 图片上传与访问

POST /api/media/images，multipart/form-data的file字段；purpose=PRIVATE（默认）或AVATAR。支持JPEG、PNG、WebP，输入最多5MB、1200万像素、单边6000像素；后端实际解码而非相信扩展名/MIME，统一重编码PNG并移除原始元数据。头像最长边缩至512，其他图片缩至2560，保持宽高比；处理后最多10MB。最多同时处理两张图片，繁忙返回1005。

返回{id,url,purpose,width,height,sizeBytes,contentType}，文件名与ID由服务器生成。私人图片url=/api/media/{id}/content，当前阶段只有上传者能通过Bearer认证读取；第三方1002，未登录1001。该二进制响应不使用ApiResponse包装。

头像url=/api/media/avatars/{id}，明确为公开图片，可无JWT加载；该路径拒绝读取PRIVATE资源。PUT /api/users/me设置已上传头像时仍校验资源归本人且用途为AVATAR，不能把他人的资源设为自己的头像。私人图片绝不能为了方便显示而改走头像入口。

本地存储位置由JIUJIU_MEDIA_DIRECTORY指定，默认backend/data/media；通过ImageStorage接口预留对象存储替换点。数据库只保存元数据和引用，不保存Base64。当前阶段尚未把PRIVATE图片关联到会话；聊天阶段需要按会话成员校验读取权。

依赖：TwelveMonkeys imageio-webp 3.15.3，用于实际WebP解码，版本来源为官方发布页 https://github.com/haraldk/TwelveMonkeys/releases 。图片仍会经过本项目的尺寸和内容校验。


## 阶段 3：独立会话、群聊与未读

所有接口需登录；业务错误仍使用统一信封。新增表 conversations / conversation_members / conversation_messages，与原房间聊天 messages 分离。

| 方法与路径 | 请求 / 作用 |
| --- | --- |
| GET /api/conversations?page=1&size=20 | 本人当前会话、最后消息、未读数；size 最大50 |
| POST /api/conversations/direct | {targetUserId}，须为好友；双方复用同一会话 |
| GET /api/conversations/{id} | 当前成员专属会话资料 |
| GET /api/conversations/{id}/messages?beforeId=&limit=30 | ID 游标历史，返回 items（时间正序）、nextBeforeId、hasMore；limit 最大50 |
| POST /api/conversations/{id}/messages | {clientMessageId,contentType:TEXT/IMAGE,content?,mediaId?} |
| POST /api/conversations/{id}/read | {messageId?}；缺省读到最新，游标只前进 |
| POST /api/groups | {name}；1至40字、上限50成员 |
| POST /api/groups/join | {code}；8位群码，重复加入幂等 |
| GET /api/groups/{id}/members | 当前群成员 |
| PUT /api/groups/{id} | {name}，仅群主 |
| POST /api/groups/{id}/leave | 退群；群主移交最早加入的当前成员，最后一人退出关闭群 |
| POST /api/groups/{id}/invite | {targetUserId,clientMessageId}；须为好友，私聊投递服务端生成的群邀请卡 |

消息 clientMessageId 为 8至64位字母数字、下划线或连字符，前端使用 UUID。唯一约束为 senderId + clientMessageId；相同请求返回已有消息，重复标识更换内容或会话会拒绝。文本上限2000 Unicode码点，保留换行，以纯文本渲染。

图片先上传 PRIVATE，再引用 mediaId 发送；只允许发送自己上传的 PRIVATE 图片。读取权限为上传者或含该图片消息的当前会话成员；退群后立即失去该群所授予的图片访问权。图片已下载到设备后的副本无法撤回。

私聊解除好友后历史可读，但禁止继续发消息。群码持有人可主动加入；新加入或重新加入成员可读群完整历史，界面已明确告知。邀请不会自动添加成员。最后成员退出后群码失效。

提交后通过 /user/queue/social 推送 CHAT_MESSAGE（MessageView）或 CONVERSATION_UPDATE（{conversationId}），仅发给当时的当前成员。前端按消息ID去重，重连/页面重新可见时重新拉取最新历史与会话未读；早期历史可用游标再次加载。未读仅计算其他人发来的、超过个人游标的消息。退出登录清理平台会话缓存，未完成请求用代次隔离。

持久化：开发默认 H2 内存库随进程结束丢失；使用文件库或 MySQL 才可跨服务重启保存。MySQL 升级手动按序执行 V004（仅执行一次），H2 自动迁移可重复执行。
