# 用户会话模块任务列表

> 来源：`doc/proposal.md` 3 用户系统、8 用户状态；`doc/detailed-design.md` 5.2 用户会话模块。

## 目标

交付匿名用户身份、用户状态机、大厅活跃状态和 5 分钟断线重连能力。

## 前置约定

- `userId` 由服务端生成并返回客户端保存。
- 同一 `userId` 同一时刻最多处于一个房间上下文。
- 重连上下文直接由 `UserSession` 推导，不单独建表。

## 最小任务

- [ ] 定义 `UserSession` 数据结构。
- [ ] 定义用户状态枚举 `ONLINE / RECONNECTING / OFFLINE`。
- [ ] 封装 `drrr:user:{userId}` 读写逻辑。
- [ ] 封装 `drrr:user:reconnecting` 索引维护逻辑，score 使用 `lastDisconnectedAt`。
- [ ] 封装 `drrr:lobby:active-users` 写入逻辑，score 使用 `lastActiveAt`。
- [ ] 实现创建匿名会话：校验昵称非空。
- [ ] 实现创建匿名会话：生成唯一 `userId`。
- [ ] 实现创建匿名会话：初始状态为 `ONLINE`，`currentRoomId` 为空。
- [ ] 实现创建匿名会话：写入用户主数据并更新大厅活跃索引。
- [ ] 在后端建立 WebSocket 连接与 `userId` 的运行时映射。
- [ ] 实现 WebSocket 断开处理：定位 `userId` 并读取 `UserSession`。
- [ ] 实现房间内断线：状态切换为 `RECONNECTING`，写入断线时间。
- [ ] 实现房间内断线：写入 `drrr:user:reconnecting`。
- [ ] 实现房间内断线：触发 `USER_RECONNECTING` 房间事件。
- [ ] 实现房间内断线：通知聊天模块生成断线系统消息。
- [ ] 实现非房间用户断开：不进入房间重连流程。
- [ ] 实现重连恢复入口，接收 `userId` 和 `roomId`。
- [ ] 校验重连用户存在。
- [ ] 校验 `UserSession.currentRoomId` 与请求 `roomId` 一致。
- [ ] 校验用户状态为 `RECONNECTING`。
- [ ] 校验断线时间未超过 5 分钟。
- [ ] 重建 WebSocket 绑定。
- [ ] 将用户状态恢复为 `ONLINE`，从重连索引移除。
- [ ] 触发 `USER_RECONNECTED` 房间事件。
- [ ] 通知房间模块同步成员状态。
- [ ] 通知聊天模块生成恢复连接系统消息。

## 测试验收

- [ ] 昵称为空时创建会话失败。
- [ ] 创建会话能生成唯一 `userId`。
- [ ] 房间内断线后状态正确变为 `RECONNECTING`。
- [ ] 5 分钟内重连可以恢复。
- [ ] 超过 5 分钟重连被拒绝。
- [ ] `roomId` 不一致时重连被拒绝。
- [ ] 不在房间内的用户断开时不会写入重连索引。

## 完成定义

- [ ] 用户会话模块任务全部完成。
- [ ] 状态机单元测试覆盖主要转换和拒绝分支。
- [ ] 与房间模块、房间事件模块、聊天模块的后端调用点已接通或留有明确接口。
