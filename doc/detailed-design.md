# Anonymous Temporary Community

> 匿名临时社区系统（MVP）详细设计文档

---

## 1. 文档说明

本文档基于以下输入文档编写：

- [proposal.md](D:\work\project\myproject\drrr\doc\proposal.md)
- [high-level-design.md](D:\work\project\myproject\drrr\doc\high-level-design.md)

目标是将概要设计进一步细化为可落地开发的模块级设计，严格遵守以下边界：

- 仅覆盖 MVP 范围
- 不新增需求范围
- 不展开代码实现
- 不引入数据库、MQ、多实例等非目标能力

对于输入文档未明确、但实现必须确定的细节，本文档统一采用“默认设计假设”方式给出落地方案，并在文末汇总“待确认问题”。

---

## 2. 设计原则

### 2.1 范围约束

- 系统为单体后端 + Redis + Vue3 前端
- Redis 为唯一运行态权威存储
- 消息仅做临时缓存，不做永久存储
- 房间、用户、消息均围绕临时社区生命周期设计

### 2.2 模块划分原则

- 每个模块围绕单一职责组织
- 模块之间通过明确的输入输出协作，避免共享内部状态
- Redis Key 按聚合边界拆分，避免跨模块随意读写
- 模块应支持独立开发、独立单元测试、独立集成测试

### 2.3 默认设计假设

- HTTP 接口负责创建会话、创建房间、加入房间、治理操作、导出操作等命令型请求
- WebSocket 负责实时消息与房间内事件推送
- `userId` 由服务端生成并返回给前端，前端仅本地保存
- 同一 `userId` 在任意时刻最多处于一个房间上下文中
- 同一浏览器会话只维护一个匿名用户身份
- Redis 中对象统一采用 JSON 字符串存储复杂结构，使用 Set、ZSet、List 保存索引和有序集合

---

## 3. 核心实体详细设计

本节定义跨模块共享的核心数据结构。字段以业务设计所需最小集为准。

### 3.1 UserSession

模块职责上的含义：表示匿名用户在当前浏览器会话中的权威身份。

```json
{
  "userId": "u_xxx",
  "nickname": "Alice",
  "currentRoomId": "r_xxx",
  "status": "ONLINE",
  "connected": true,
  "lastConnectedAt": 1717300000000,
  "lastDisconnectedAt": 1717300100000,
  "createdAt": 1717299000000,
  "updatedAt": 1717300100000
}
```

字段说明：

- `userId`：匿名用户唯一标识
- `nickname`：当前匿名昵称
- `currentRoomId`：当前所在房间，没有则为空
- `status`：`ONLINE / RECONNECTING / OFFLINE`
- `connected`：是否存在有效 WebSocket 连接
- `lastConnectedAt`：最近一次连接成功时间
- `lastDisconnectedAt`：最近一次断开时间

### 3.2 Room

表示房间主数据，是系统核心聚合根。

```json
{
  "roomId": "r_xxx",
  "name": "深夜电台",
  "description": "匿名闲聊",
  "hasPassword": true,
  "passwordHash": "xxx",
  "maxMembers": 10,
  "ownerUserId": "u_xxx",
  "status": "ACTIVE",
  "userListVisible": true,
  "historyStrategy": {
    "type": "COUNT",
    "value": 50
  },
  "allowOwnerConfigChange": true,
  "createdAt": 1717299000000,
  "lastActiveAt": 1717300100000,
  "emptySince": null
}
```

字段说明：

- `status`：`ACTIVE / EMPTY / EXPIRED`
- `historyStrategy.type`：默认设计为 `NONE / COUNT / MINUTES`
- `historyStrategy.value`：当类型为 `COUNT` 或 `MINUTES` 时生效
- `hasPassword` 与 `passwordHash`：避免明文密码入 Redis

### 3.3 RoomMember

表示用户与房间之间的成员关系。

```json
{
  "roomId": "r_xxx",
  "userId": "u_xxx",
  "nickname": "Alice",
  "memberStatus": "ONLINE",
  "joinedAt": 1717299050000,
  "lastActiveAt": 1717300100000,
  "isOwner": false
}
```

字段说明：

- `memberStatus`：与用户会话状态保持一致，但只表示该房间上下文中的状态
- `joinedAt`：用于房主继承排序
- `isOwner`：便于房间成员列表直接展示

### 3.4 Message

表示房间消息。

```json
{
  "messageId": "m_xxx",
  "roomId": "r_xxx",
  "type": "PUBLIC",
  "senderUserId": "u_a",
  "senderNickname": "Alice",
  "targetUserId": null,
  "targetNickname": null,
  "content": "你好",
  "visibleTo": ["u_a", "u_b"],
  "sentAt": 1717300200000
}
```

字段说明：

- `type`：默认设计为 `PUBLIC / DIRECT / SYSTEM`
- `visibleTo`：
  - 公共消息不显式保存完整成员列表，读取时按类型判定为全房间可见
  - 定向消息保存双方 `userId`
  - 系统消息默认全房间可见

### 3.5 RoomEvent

表示房间中的一条轻量运行事件。

```json
{
  "eventId": "e_xxx",
  "roomId": "r_xxx",
  "type": "OWNER_TRANSFER",
  "operatorUserId": "u_old_owner",
  "targetUserId": "u_new_owner",
  "payload": {
    "fromUserId": "u_old_owner",
    "toUserId": "u_new_owner"
  },
  "occurredAt": 1717300200000
}
```

字段说明：

- `type`：默认设计为 `USER_JOIN / USER_LEAVE / USER_RECONNECTING / USER_RECONNECTED / OWNER_TRANSFER / USER_MUTED / USER_KICKED / USER_BANNED / ROOM_EMPTY / ROOM_EXPIRED`
- `operatorUserId`：发起操作或触发事件的用户
- `targetUserId`：被影响用户，没有则为空
- `payload`：用于扩展事件上下文

### 3.6 MuteRecord

```json
{
  "roomId": "r_xxx",
  "userId": "u_xxx",
  "mutedBy": "u_owner",
  "startAt": 1717300200000,
  "endAt": 1717303800000,
  "reason": "owner_action"
}
```

### 3.7 BanRecord

```json
{
  "roomId": "r_xxx",
  "userId": "u_xxx",
  "bannedBy": "u_owner",
  "bannedAt": 1717300200000,
  "reason": "owner_action"
}
```

---

## 4. Redis 总体设计

### 4.1 Key 命名约定

- 前缀统一使用 `drrr:`
- 一级维度优先按聚合拆分：`user`、`room`、`member`、`msg`、`governance`
- 时间扫描类数据优先使用 `zset`
- 复杂对象统一 JSON 化，避免哈希结构字段演进带来兼容问题

### 4.2 全局 Key 清单

| Key | 类型 | 用途 |
| --- | --- | --- |
| `drrr:user:{userId}` | String | 用户会话主数据 |
| `drrr:room:{roomId}` | String | 房间主数据 |
| `drrr:room:members:{roomId}` | ZSet | 房间成员索引，score=`joinedAt` |
| `drrr:room:messages:{roomId}` | List | 房间消息流 |
| `drrr:room:events:{roomId}` | List | 房间轻量事件流 |
| `drrr:room:active` | ZSet | 活跃房间索引，score=`lastActiveAt` |
| `drrr:room:empty` | ZSet | 空房间索引，score=`emptySince` |
| `drrr:user:reconnecting` | ZSet | 重连中的用户索引，score=`lastDisconnectedAt` |
| `drrr:room:mute:{roomId}` | ZSet | 禁言用户索引，score=`endAt` |
| `drrr:room:mute:detail:{roomId}:{userId}` | String | 禁言记录 |
| `drrr:room:ban:{roomId}` | Set | 房间 Ban 用户集合 |
| `drrr:room:ban:detail:{roomId}:{userId}` | String | Ban 记录 |
| `drrr:lobby:active-users` | ZSet | 最近 5 分钟活跃用户索引，score=`lastActiveAt` |

### 4.3 默认 Redis 操作边界

- 房间模块只负责 `room` 和房间成员关系骨架的维护
- 用户会话模块只负责 `user` 和 `user:reconnecting` 的维护
- 聊天模块只负责 `messages` 的写入、裁剪、可见性过滤
- 房间事件模块只负责 `events` 的写入与读取
- 治理模块只负责 `mute`、`ban` 相关 Key
- 定时清理模块只负责扫描索引 Key，并调用业务模块执行最终清理

---

## 5. 模块详细设计

## 5.1 大厅模块

### 模块职责

- 提供大厅首页所需数据
- 返回最近 5 分钟活跃人数
- 返回房间列表摘要
- 支持房间排序

### 输入输出

输入：

- 排序方式：`LAST_ACTIVE / MEMBER_COUNT / SURVIVAL_TIME`
- 可选分页参数：默认设计假设为 MVP 可先返回全量列表

输出：

- 最近 5 分钟活跃人数
- 房间摘要列表

房间摘要结构：

```json
{
  "roomId": "r_xxx",
  "name": "深夜电台",
  "description": "匿名闲聊",
  "currentMembers": 5,
  "maxMembers": 10,
  "hasPassword": true,
  "lastActiveAt": 1717300200000,
  "createdAt": 1717299000000
}
```

### 核心数据结构

- `LobbySummary`
- `RoomCard`

### Redis Key 设计

- `drrr:lobby:active-users`
- `drrr:room:active`
- `drrr:room:{roomId}`
- `drrr:room:members:{roomId}`

### 核心流程

1. 读取 `drrr:lobby:active-users`，过滤 5 分钟窗口内用户，统计活跃人数。
2. 读取 `drrr:room:active` 获取候选房间列表。
3. 批量读取房间主数据与成员数量。
4. 根据排序方式排序：
   - 最近活跃：按 `lastActiveAt` 倒序
   - 人数排序：按 `currentMembers` 倒序
   - 存活时间排序：按 `createdAt` 升序
5. 组装大厅返回结果。

### 异常情况

- 房间索引存在但房间主数据缺失：视为脏数据，过滤并由定时清理模块后续收敛
- 排序参数非法：回退到默认 `LAST_ACTIVE`
- Redis 部分读取失败：返回失败，不输出不完整大厅数据

### 可独立测试点

- 最近 5 分钟活跃人数统计是否正确
- 三种排序是否符合预期
- 密码房是否仅暴露 `hasPassword`，不暴露密码内容
- 房间主数据缺失时是否正确过滤

---

## 5.2 用户会话模块

### 模块职责

- 生成匿名用户身份
- 维护用户状态机
- 管理断线重连
- 维护用户当前房间关系
- 维护大厅活跃状态

### 输入输出

输入：

- 用户进入大厅时的昵称
- WebSocket 连接建立/断开事件
- 重连请求中的 `userId`、`roomId`

输出：

- 新建或恢复后的 `UserSession`
- 状态变更结果
- 是否允许重连

### 核心数据结构

- `UserSession`
- `ReconnectContext`

默认设计假设：

- `ReconnectContext` 不单独建表，直接由 `UserSession` 中的 `status`、`currentRoomId`、`lastDisconnectedAt` 推导

### Redis Key 设计

- `drrr:user:{userId}`
- `drrr:user:reconnecting`
- `drrr:lobby:active-users`

### 核心流程

#### 5.2.1 创建匿名会话

1. 接收昵称。
2. 生成 `userId`。
3. 创建 `UserSession`，初始状态为 `ONLINE`，但 `currentRoomId` 为空。
4. 写入 `drrr:user:{userId}`。
5. 更新 `drrr:lobby:active-users`。
6. 返回 `userId`、昵称与当前状态。

#### 5.2.2 WebSocket 断开

1. 根据连接映射定位 `userId`。
2. 读取 `UserSession`。
3. 若用户当前在房间内，则状态切换为 `RECONNECTING`。
4. 更新 `lastDisconnectedAt`。
5. 写入 `drrr:user:reconnecting`。
6. 触发房间事件模块记录 `USER_RECONNECTING` 事件。
7. 通知聊天模块生成“用户断线”系统消息。

#### 5.2.3 重连恢复

1. 前端携带 `userId`、`roomId` 发起恢复请求。
2. 校验 `UserSession.currentRoomId` 是否一致。
3. 校验状态是否为 `RECONNECTING`。
4. 校验断线时间是否未超过 5 分钟。
5. 恢复 WebSocket 绑定。
6. 用户状态改为 `ONLINE`，从 `drrr:user:reconnecting` 删除。
7. 触发房间事件模块记录 `USER_RECONNECTED` 事件。
8. 更新房间成员状态并广播“恢复连接”系统消息。

### 异常情况

- 昵称为空：拒绝创建会话
- `userId` 不存在：重连失败，要求重新进入大厅
- 重连超过 5 分钟：视为失效
- `roomId` 与当前记录不一致：拒绝重连
- 用户已被房间清理为 `OFFLINE`：拒绝重连

### 可独立测试点

- 昵称创建会话是否生成唯一 `userId`
- 断线后是否正确切换为 `RECONNECTING`
- 5 分钟内重连是否成功恢复
- 超时重连是否被拒绝
- 不在房间中的用户断开时，是否不进入房间重连流程

---

## 5.3 房间模块

### 模块职责

- 创建房间
- 修改房间基础信息
- 处理加入、离开
- 管理人数上限、密码校验、配置校验
- 维护房间生命周期状态

### 输入输出

输入：

- 创建房间请求
- 加入房间请求
- 离开房间请求
- 修改房间信息请求

输出：

- 房间创建结果
- 房间加入结果
- 房间离开结果
- 房间配置变更结果

### 核心数据结构

- `Room`
- `RoomMember`
- `RoomConfigView`

默认设计假设：

- 房间配置直接内嵌在 `Room` 中，不单独拆分对象存储

### Redis Key 设计

- `drrr:room:{roomId}`
- `drrr:room:members:{roomId}`
- `drrr:room:active`
- `drrr:room:empty`

### 核心流程

#### 5.3.1 创建房间

1. 校验创建者存在且当前未处于其他房间。
2. 校验房间名非空、人数上限在 `1-20`。
3. 生成 `roomId`。
4. 构建 `Room`：
   - 初始状态 `ACTIVE`
   - `ownerUserId` 为创建者
   - `lastActiveAt` 为当前时间
5. 写入 `drrr:room:{roomId}`。
6. 创建首个 `RoomMember`，写入成员索引。
7. 更新创建者 `UserSession.currentRoomId`。
8. 写入 `drrr:room:active`。
9. 返回房间详情。

#### 5.3.2 加入房间

1. 校验用户存在且未处于其他房间。
2. 读取房间主数据并校验状态不是 `EXPIRED`。
3. 校验用户是否被 Ban。
4. 校验房间密码。
5. 校验房间人数是否已达上限。
6. 校验房间内昵称是否重复。
7. 创建 `RoomMember` 逻辑关系，写入成员索引。
8. 更新 `UserSession.currentRoomId`。
9. 更新 `Room.lastActiveAt`，若房间原状态为 `EMPTY` 则恢复为 `ACTIVE` 并清空 `emptySince`。
10. 触发房间事件模块记录 `USER_JOIN` 事件。
11. 触发聊天模块生成“加入房间”系统消息。
12. 返回房间详情、成员摘要和允许范围内的历史消息。

#### 5.3.3 主动离开房间

1. 校验用户属于该房间。
2. 删除成员索引。
3. 清空 `UserSession.currentRoomId`，状态置为 `ONLINE` 或大厅态。
4. 调用房主管理模块判断是否需要继承。
5. 若房间无在线成员，则置为 `EMPTY`，记录 `emptySince`，写入 `drrr:room:empty`。
6. 触发房间事件模块记录 `USER_LEAVE`，若进入空房则同时记录 `ROOM_EMPTY`。
7. 触发聊天模块生成“离开房间”系统消息。

#### 5.3.4 修改房间信息

1. 校验操作者是当前房主。
2. 校验允许修改的字段范围：
   - 房间名
   - 房间简介
   - 配置项
3. 若配置项修改且当前操作者不是初始房主，则需检查 `allowOwnerConfigChange`。
4. 更新 `Room` 并刷新 `lastActiveAt`。
5. 触发聊天模块生成“房间配置变更”系统消息。

### 异常情况

- 用户已在其他房间：拒绝创建/加入
- 房间不存在或已过期：加入失败
- 密码错误：加入失败
- 房间已满：加入失败
- 同房间昵称重复：加入失败
- 非房主修改房间：拒绝
- 后续房主无权修改配置：拒绝

### 可独立测试点

- 创建房间时是否正确初始化房主和首成员
- 人数上限 1-20 校验是否正确
- 密码校验是否正确
- 房间满员时是否阻止加入
- 同房间昵称冲突时是否阻止加入
- 空房恢复到 `ACTIVE` 的状态切换是否正确

---

## 5.4 聊天模块

### 模块职责

- 发送公共消息
- 发送定向消息
- 生成系统消息
- 管理历史消息保留策略
- 提供历史消息读取能力

### 输入输出

输入：

- 公共消息发送请求
- 定向消息发送请求
- 来自其他模块的系统事件

输出：

- 已入消息流的 `Message`
- WebSocket 推送事件
- 历史消息列表

### 核心数据结构

- `Message`
- `HistoryStrategy`
- `MessageView`

默认设计假设：

- 历史消息读取时统一输出 `MessageView`，前端无需感知 Redis 原始结构
- 房间事件是系统消息的事实来源，系统消息是房间事件的展示结果

### Redis Key 设计

- `drrr:room:messages:{roomId}`
- `drrr:room:events:{roomId}`
- `drrr:room:{roomId}`

### 核心流程

#### 5.4.1 发送公共消息

1. 校验发送者属于该房间且状态为 `ONLINE`。
2. 调用治理模块检查是否处于禁言状态。
3. 构建 `Message.type=PUBLIC`。
4. 写入 `drrr:room:messages:{roomId}`。
5. 根据房间历史策略执行裁剪。
6. 刷新房间 `lastActiveAt`。
7. 通过 WebSocket 向房间全体在线成员广播。

#### 5.4.2 发送定向消息

1. 校验发送者、接收者都属于该房间。
2. 校验发送者未被禁言。
3. 构建 `Message.type=DIRECT`，记录 `targetUserId` 与双方可见范围。
4. 写入消息流。
5. 执行历史裁剪。
6. 仅向发送者和接收者推送消息。

#### 5.4.3 发送系统消息

1. 接收业务事件类型与上下文。
2. 生成 `Message.type=SYSTEM`。
3. 写入消息流。
4. 依据历史策略裁剪。
5. 向房间内在线成员广播。

默认设计假设：

- 聊天模块不直接定义状态变化事实，只负责将房间事件转换为系统消息文本并推送

#### 5.4.4 读取历史消息

1. 读取房间配置的历史策略。
2. 若策略为 `NONE`，返回空列表。
3. 若策略为 `COUNT`，读取消息流尾部指定条数。
4. 若策略为 `MINUTES`，过滤指定时间窗口内消息。
5. 对读取结果按请求用户做可见性过滤：
   - 公共消息：可见
   - 系统消息：可见
   - 定向消息：仅发送者和接收者可见

### 异常情况

- 非房间成员发送消息：拒绝
- 接收者不存在：定向消息发送失败
- 发送者被禁言：拒绝
- 历史策略为 `MINUTES` 但窗口值非法：按默认假设回退为返回空历史

### 可独立测试点

- 公共消息是否正确广播给所有在线成员
- 定向消息是否只对双方可见
- 系统消息是否进入同一聊天流
- `COUNT` 和 `MINUTES` 两种历史策略是否正确裁剪
- 历史读取时是否正确过滤不可见私聊消息

---

## 5.5 房间事件模块

### 模块职责

- 记录房间级轻量运行事件
- 为系统消息生成提供事实来源
- 为状态排查提供房间级事件轨迹
- 为导出模块提供房间事件数据

### 输入输出

输入：

- 来自房间模块、用户会话模块、房主管理模块、禁言/Ban 模块、定时清理模块的业务事件

输出：

- 已写入事件流的 `RoomEvent`
- 按房间范围读取的事件列表

### 核心数据结构

- `RoomEvent`
- `RoomEventView`

### Redis Key 设计

- `drrr:room:events:{roomId}`

### 核心流程

#### 5.5.1 记录房间事件

1. 接收事件类型与事件上下文。
2. 构建 `RoomEvent`。
3. 写入 `drrr:room:events:{roomId}`。
4. 按房间生命周期保留事件流，不做跨房间聚合。

#### 5.5.2 读取房间事件

1. 根据 `roomId` 读取事件流。
2. 按时间顺序返回事件列表。
3. 提供给聊天模块生成系统消息展示，或提供给导出模块输出。

### 异常情况

- 房间不存在：拒绝写入事件
- 事件上下文不完整：拒绝写入或按默认空载荷处理
- 房间已销毁：不允许继续追加事件

### 可独立测试点

- 用户加入、离开、断线、恢复连接是否正确写入事件
- 房主转移、禁言、踢出、Ban 是否正确写入事件
- 房间进入 `EMPTY`、`EXPIRED` 是否正确写入事件
- 事件读取顺序是否与写入顺序一致

---

## 5.6 房主管理模块

### 模块职责

- 设置初始房主
- 处理房主继承
- 提供房主权限判定
- 管理成员 `isOwner` 状态

### 输入输出

输入：

- 房间创建事件
- 房主离开事件
- 权限校验请求

输出：

- 当前房主信息
- 继承结果
- 权限校验结果

### 核心数据结构

- `OwnerTransferResult`
- `OwnerPermissionCheck`

默认设计假设：

- 房主继承只在房主实际离开房间或被清理为离线离房时触发

### Redis Key 设计

- `drrr:room:{roomId}`
- `drrr:room:members:{roomId}`

### 核心流程

#### 5.5.1 初始房主设置

1. 房间创建成功后，将创建者 `userId` 写入 `Room.ownerUserId`。
2. 创建者在房间成员关系中视为房主。

#### 5.6.2 房主继承

1. 读取 `drrr:room:members:{roomId}` 按 `joinedAt` 升序获取候选人。
2. 过滤已离房成员。
3. 选择最早加入且仍为有效成员的用户。
4. 更新旧房主成员标记为 `false`。
5. 更新新房主成员标记为 `true`。
6. 更新 `Room.ownerUserId`。
7. 触发房间事件模块记录 `OWNER_TRANSFER` 事件。
8. 调用聊天模块发送“房主转移”系统消息。

### 异常情况

- 房主离开后没有剩余成员：不发生继承，由房间模块进入空房逻辑
- 成员索引存在但对应用户会话缺失：跳过脏成员并继续选择
- 并发继承请求：默认设计假设由单体业务锁或串行命令保证只执行一次

### 可独立测试点

- 创建房间时创建者是否自动成为房主
- 房主离开后是否按加入顺序继承
- 房主离开且无剩余成员时是否不错误地产生新房主
- 成员脏数据存在时是否仍能选出正确继承人

---

## 5.7 禁言/Ban 模块

### 模块职责

- 执行禁言
- 执行踢出
- 执行永久 Ban
- 提供消息发送前和加入前校验

### 输入输出

输入：

- 房主管理命令
- 消息发送前校验请求
- 用户入房前校验请求

输出：

- 治理操作结果
- 当前禁言状态
- 当前 Ban 状态

### 核心数据结构

- `MuteRecord`
- `BanRecord`
- `GovernActionResult`

### Redis Key 设计

- `drrr:room:mute:{roomId}`
- `drrr:room:mute:detail:{roomId}:{userId}`
- `drrr:room:ban:{roomId}`
- `drrr:room:ban:detail:{roomId}:{userId}`

### 核心流程

#### 5.7.1 禁言

1. 校验操作者是房主。
2. 校验目标成员存在且不是当前房主。
3. 解析禁言时长，计算 `endAt`。
4. 写入 `drrr:room:mute:{roomId}` 和详情 Key。
5. 触发房间事件模块记录 `USER_MUTED` 事件。
6. 触发聊天模块发送“用户被禁言”系统消息。

默认设计假设：

- 自定义禁言时长输入单位为整数分钟
- 若重复禁言同一用户，以最后一次设置覆盖

#### 5.7.2 禁言校验

1. 在 `drrr:room:mute:{roomId}` 查询 `userId` 对应过期时间。
2. 若当前时间未超过 `endAt`，返回禁言中。
3. 若已过期，删除禁言索引与详情并返回未禁言。

#### 5.7.3 踢出

1. 校验操作者是房主。
2. 校验目标成员存在且不是房主本人。
3. 调用房间模块执行目标成员离房。
4. 触发房间事件模块记录 `USER_KICKED` 事件。
5. 清理目标用户当前房间的重连上下文，禁止其继续使用旧 `roomId` 走自动重连恢复流程。
6. 若目标处于在线连接，则通过 WebSocket 下发踢出事件。

默认设计假设：

- 被踢出后，用户返回大厅
- 若未被 Ban，用户仍可重新通过“加入房间”流程进入该房间
- 踢出只阻止旧房间上下文的自动重连恢复，不等同于 Ban

#### 5.7.4 Ban

1. 校验操作者是房主。
2. 写入房间 Ban 集合与详情。
3. 若目标当前在房间内，立即执行踢出。
4. 触发房间事件模块记录 `USER_BANNED` 事件。
5. 发送“用户被 Ban”系统消息。

### 异常情况

- 非房主执行治理：拒绝
- 房主试图禁言或踢出自己：拒绝
- 目标成员不存在：治理失败
- 禁言时长非法：拒绝
- Ban 已存在：幂等返回成功

### 可独立测试点

- 禁言是否正确阻止公共消息和私聊消息
- 禁言过期后是否自动恢复
- 踢出是否正确移除成员关系
- Ban 后是否无法重新进入房间
- 重复 Ban 是否保持幂等

---

## 5.8 导出模块

### 模块职责

- 允许房主导出当前可保留的聊天记录
- 按 JSON 输出
- 不新增永久存储

### 输入输出

输入：

- `roomId`
- 操作者 `userId`

输出：

- 文件名
- JSON 文本内容

默认输出结构：

```json
{
  "roomId": "r_xxx",
  "roomName": "深夜电台",
  "exportedAt": 1717300200000,
  "messages": [],
  "events": []
}
```

### 核心数据结构

- `ChatExport`
- `ExportMessageItem`
- `ExportEventItem`

### Redis Key 设计

- `drrr:room:{roomId}`
- `drrr:room:messages:{roomId}`
- `drrr:room:events:{roomId}`

### 核心流程

1. 校验操作者是当前房主。
2. 读取房间主数据。
3. 按聊天模块的历史读取能力拉取当前 Redis 中仍保留的消息，包含系统消息。
4. 按房间事件模块的读取能力拉取当前房间事件日志。
5. 组装 JSON 导出结构。
6. 生成默认文件名 `room_{roomId}.json`。

### 异常情况

- 非房主导出：拒绝
- 房间不存在：导出失败
- 房间消息为空：允许导出空数组
- 房间事件为空：允许导出空数组

### 可独立测试点

- 仅房主可导出是否生效
- 导出内容是否同时包含消息与事件
- 空消息或空事件房间是否仍可导出合法 JSON

---

## 5.9 定时清理模块

### 模块职责

- 清理重连超时用户
- 推进空房间生命周期
- 销毁过期房间
- 清理治理、消息与事件脏数据

### 输入输出

输入：

- 定时触发信号

输出：

- 清理结果日志
- 房间状态推进结果

### 核心数据结构

- `CleanupTaskResult`
- `ExpiredRoomCleanupContext`

### Redis Key 设计

- `drrr:user:reconnecting`
- `drrr:room:empty`
- `drrr:room:{roomId}`
- `drrr:room:members:{roomId}`
- `drrr:room:messages:{roomId}`
- `drrr:room:events:{roomId}`
- `drrr:room:mute:{roomId}`
- `drrr:room:ban:{roomId}`

### 核心流程

#### 5.8.1 重连超时清理

1. 扫描 `drrr:user:reconnecting` 中超过 5 分钟的用户。
2. 将其 `UserSession.status` 置为 `OFFLINE`。
3. 若仍在房间中，则调用房间模块执行离房。
4. 从重连索引中移除。

#### 5.8.2 空房推进

1. 扫描 `drrr:room:empty` 中已记录空房的房间。
2. 读取房间主数据，校验仍为 `EMPTY`。
3. 若 `emptySince` 已超过 24 小时，则进入过期销毁。

#### 5.8.3 房间销毁

1. 将房间状态视为 `EXPIRED`。
2. 触发房间事件模块记录 `ROOM_EXPIRED` 事件。
3. 删除房间主数据。
4. 删除成员索引。
5. 删除消息列表。
6. 删除事件列表。
7. 删除禁言、Ban 相关数据。
8. 删除房间活跃索引与空房索引。

默认设计假设：

- 为简化实现，销毁时不保留“墓碑记录”，因此同名房间可立即重建，且完全视为新房间

### 异常情况

- 索引存在但主数据已删除：直接清理索引
- 用户已被人工离房但仍残留在重连索引：直接删除索引
- 房间状态与空房索引不一致：以房间主数据为准

### 可独立测试点

- 重连超过 5 分钟是否被正确清理为 `OFFLINE`
- 空房超过 24 小时是否被完整销毁
- 销毁后同名房间是否可重新创建
- 脏索引是否能被清理收敛

---

## 6. 模块协作规则

### 6.1 低耦合约束

- 大厅模块不直接处理成员状态机，只读摘要数据
- 聊天模块不直接修改成员关系，只依赖房间与会话校验结果
- 房间事件模块不直接改写房间状态，只记录事件事实
- 房主管理模块不直接删除成员，只决定新房主
- 治理模块不直接维护消息，只发布治理事件
- 定时清理模块不直接拼装业务消息，只调用现有业务模块完成状态推进

### 6.2 建议调用方向

- 大厅模块 -> 房间模块 / 用户会话模块（只读）
- 房间模块 -> 用户会话模块 / 房主管理模块 / 房间事件模块 / 聊天模块
- 聊天模块 -> 房间事件模块 / 治理模块 / 房间模块 / 用户会话模块（校验）
- 治理模块 -> 房间模块 / 房间事件模块 / 聊天模块
- 定时清理模块 -> 用户会话模块 / 房间模块 / 房间事件模块 / 房主管理模块

### 6.3 一致性边界

默认设计假设：

- 单个业务命令以内存级串行执行业务步骤，必要时以 `roomId` 为粒度加锁，避免房间成员、房主、消息状态出现并发覆盖
- Redis 写入顺序以“主数据先更新，索引后更新”为主；删除时反向处理

---

## 7. 关键流程详细说明

### 7.1 用户创建房间流程

1. 前端提交昵称与房间创建参数。
2. 若用户尚未建立匿名会话，先创建 `UserSession`。
3. 房间模块校验参数并创建 `Room`。
4. 房主管理模块标记创建者为房主。
5. 房间模块写入成员关系。
6. 前端建立 WebSocket 并进入房间页面。

### 7.2 用户加入房间流程

1. 前端提交 `roomId`、密码。
2. 房间模块校验房间状态、人数、密码、昵称重复、Ban 状态。
3. 房间模块写入成员关系并更新 `UserSession.currentRoomId`。
4. 聊天模块读取历史消息。
5. WebSocket 建立后广播“加入房间”系统消息。

### 7.3 断线重连流程

1. WebSocket 层感知断开，通知用户会话模块。
2. 用户会话模块将用户置为 `RECONNECTING`。
3. 前端在 5 分钟内使用本地 `userId`、`roomId` 发起恢复。
4. 会话校验通过后恢复连接和成员状态。
5. 若超时则由定时清理模块推动离房。

### 7.4 房主离开流程

1. 房间模块执行离房。
2. 若离开者是房主，则调用房主管理模块。
3. 房主管理模块按加入顺序选取继承人。
4. 聊天模块广播房主转移系统消息。
5. 若没有剩余成员，则房间进入 `EMPTY`。

### 7.5 房间过期销毁流程

1. 房间进入 `EMPTY` 时记录 `emptySince`。
2. 定时清理模块扫描 `drrr:room:empty`。
3. 超过 24 小时则执行全量运行态删除。
4. 房间后续如被同名创建，不复用任何旧数据。

---

## 8. 测试设计建议

### 8.1 单元测试优先级

- 用户状态机流转
- 房间参数校验
- 昵称重复校验
- 房间事件写入与读取顺序
- 房主继承排序
- 禁言/Ban 校验
- 历史消息裁剪与可见性过滤

### 8.2 集成测试优先级

- 创建房间 -> 入房 -> WebSocket 建连 -> 发消息主链路
- 断线 -> 5 分钟内重连恢复
- 断线超时 -> 自动离房 -> 房主继承
- 空房 -> 24 小时后销毁
- 导出聊天记录时附带事件日志
- Ban 后重新入房失败

### 8.3 可测试性设计约束

- 每个模块的 Redis Key 操作集中封装，便于 mock 与断言
- 系统消息生成逻辑集中在聊天模块，便于断言输出
- 房间事件生成逻辑集中在房间事件模块，便于断言状态流转
- 时间相关逻辑统一通过可注入时钟实现，便于测试 5 分钟与 24 小时窗口

---

## 9. 默认设计假设汇总

1. `historyStrategy` 使用 `NONE / COUNT / MINUTES` 三种内部类型表示需求中的历史策略。
2. 房间密码仅存储哈希值，不存储明文。
3. 同一 `userId` 同时只能在一个房间中存在。
4. 浏览器本地保存的 `userId`、`nickname`、`roomId` 作为重连凭据，但最终以 Redis 中会话状态为准。
5. “最近 N 分钟消息”中的 `N` 允许房主自定义输入，不设置最大值范围。
6. 用户进入大厅后但尚未加入房间时，`UserSession.status` 统一使用 `ONLINE`。
7. 自定义禁言时长输入单位为整数分钟，不设置最大时长限制。
8. 被踢出的用户返回大厅，但由于未被 Ban，因此允许再次加入同一房间。
9. “后续房主允许修改/禁止修改”同时约束房间配置、房间名和房间简介修改。
10. 导出聊天记录时包含系统消息。
11. 大厅房间列表在 MVP 阶段默认可返回全量，不强制分页。
12. 每个房间维护独立轻量事件日志，记录状态流转与治理事件，并在房间销毁时一并删除。
13. 系统消息由房间事件驱动生成，但事件日志与聊天消息分离存储。
14. 房间消息统一写入同一消息流，通过消息类型和可见性控制展示范围。
15. Redis 复杂对象统一存为 JSON，索引使用 List、Set、ZSet。
16. 房间销毁后不保留墓碑记录，同名房间可立即重新创建。
17. 创建房间成功后不额外生成“房间已创建”系统消息。
18. 并发修改默认由单体服务内按 `roomId` 粒度串行化处理。

---

## 10. 待确认问题

当前无。
