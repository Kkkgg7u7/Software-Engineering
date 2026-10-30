# 墙与动态接口

所有接口需登录。平台内已登录用户可查看所有未删除动态、评论及其图片，发布区明确显示这一可见范围。前端以纯文本渲染内容，无 HTML 注入入口。

| 方法与路径 | 请求 / 返回 |
| --- | --- |
| GET /api/posts?authorId=&beforeId=&limit=12 | ID倒序游标信息流；authorId 可选；limit 最大30；返回 items,nextBeforeId,hasMore |
| GET /api/posts/{id} | 动态详情，含作者公开资料、图片ID、有序图片、点赞/评论数和本人点赞状态 |
| POST /api/posts | {clientRequestId,content?,mediaIds?}，文字最多2000 Unicode码点、图片最多9张，至少一项非空 |
| DELETE /api/posts/{id} | 仅作者可删；软删除且重复删除幂等 |
| PUT /api/posts/{id}/like | 点赞，重复调用不叠加 |
| DELETE /api/posts/{id}/like | 取消点赞，重复调用幂等 |
| GET /api/posts/{id}/comments?beforeId=&limit=20 | 评论游标，items为正序；limit最大50 |
| POST /api/posts/{id}/comments | {clientRequestId,content}，最多500 Unicode码点 |
| DELETE /api/posts/{postId}/comments/{id} | 仅评论作者可删 |

作者来自登录身份，忽略客户端伪造authorId。发布和评论请求标识为8至64位字母数字、下划线或连字符；作者+请求标识唯一，相同请求返回已有记录，换内容复用拒绝。

图片仍先上传 PRIVATE；发布时验证上传者本人、PRIVATE用途、上限及重复。未发布时仅原有授权者可读；关联到可见动态后，平台已登录用户获得读取授权；动态删除后撤销此来源的授权。如果同图还关联其他未删除动态/有权限的聊天，其他有效授权仍保留。上传者始终可读自己的文件。

所有受保护图片响应 Cache-Control: private, no-store，避免切换账号时复用浏览器缓存；已下载副本无法远程撤回。头像仍为明确公开缓存路径。

新增表 posts/post_images/post_likes/post_comments；点赞有数据库唯一约束，图片顺序持久化。阶段4默认只做公开到平台的信息流，没有匿名访问、推荐算法、审核后台或好友可见开关。

升级：H2 自动 V005 可重复执行；MySQL在V004后手动执行V005一次。线上发布前需正常备份数据库和媒体目录。
