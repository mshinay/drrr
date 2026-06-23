# Anonymous Temporary Community

> 后端基础设施与工程落地文档

---

## 1. 文档说明

本文档面向后端 Builder，用于把 `proposal.md` 与 `detailed-design.md` 中已经确定的 MVP 需求落到 Spring Boot 工程基础结构中。

本文档只回答后端实现开始前必须统一的工程问题：

- 后端工程分层如何组织
- Redis、WebSocket、HTTP、定时任务的基础边界如何放置
- 领域模型、DTO、异常、时间、ID、测试基座如何统一
- 每个业务模块应依赖哪些基础组件

本文档不新增产品需求，不改变 API 语义，不引入数据库、MQ、多实例部署、账号体系、图片/文件/语音/视频、推荐算法或 AI 能力。

---

## 2. 当前工程基线

### 2.1 技术栈

当前工程已具备以下基础：

| 项 | 当前选择 |
| --- | --- |
| JDK | Java 21 |
| 后端框架 | Spring Boot 4.1.0 |
| 构建工具 | Maven |
| Web 框架 | Spring WebMVC |
| 实时通信 | Spring WebSocket |
| 状态存储 | Spring Data Redis |
| 参数校验 | Spring Validation |
| 本地运行 | Docker Compose |

### 2.2 已存在文件

| 文件 | 用途 |
| --- | --- |
| `pom.xml` | Maven 依赖与 Java 版本配置 |
| `compose.yaml` | 本地运行依赖编排 |
| `src/main/java/com/boot/drrr/DrrrApplication.java` | Spring Boot 启动入口 |
| `src/main/resources/application.yaml` | 应用配置入口 |
| `src/test/java/com/boot/drrr/DrrrApplicationTests.java` | 默认上下文测试 |

### 2.3 后端目标形态

后端是单体应用。Redis 是运行态权威存储。HTTP 接口承担命令和查询入口，WebSocket 承担房间实时通信，定时任务承担重连超时和空房销毁推进。

---

## 3. 基础设计原则

### 3.1 MVP 范围约束

- 不引入持久化数据库。
- 不引入 MQ。
- 不引入多实例一致性设计。
- 不新增注册登录、好友、关注、用户主页等账号能力。
- 不长期保存消息、事件或房间墓碑。
- 房间销毁时同步删除房间、成员、消息、事件、治理状态等运行态数据。

### 3.2 工程分层原则

- `controller` 只做 HTTP 入参校验、用户上下文提取和响应封装。
- `ws` 只做 WebSocket 连接、消息收发、在线连接路由和协议 Envelope。
- `service` 承载业务流程编排。
- `domain` 承载领域对象、枚举和值对象。
- `repository` 封装 Redis key 读写，不把 Redis 操作散落到业务流程中。
- `support` 承载通用基础能力，例如 ID、时间、JSON、异常、锁、响应 Envelope。
- 业务模块只能通过 service 或 repository 明确协作，不直接共享内部状态。

### 3.3 Redis 边界原则

- Redis key 必须来自 `detailed-design.md` 中的全局 Key 清单。
- 复杂对象统一以 JSON String 保存。
- 列表类数据使用 List。
- 集合类数据使用 Set。
- 时间扫描类索引使用 ZSet。
- 单个业务命令需要多 key 更新时，以 `roomId` 为粒度做单体内串行化，避免房主、成员、消息、事件互相覆盖。

---

## 4. 推荐包结构

```text
com.boot.drrr
├── DrrrApplication
├── common
│   ├── api
│   ├── error
│   ├── id
│   ├── json
│   ├── lock
│   └── time
├── config
├── domain
│   ├── event
│   ├── governance
│   ├── message
│   ├── room
│   └── user
├── repository
│   ├── event
│   ├── governance
│   ├── message
│   ├── room
│   └── user
├── service
│   ├── cleanup
│   ├── event
│   ├── export
│   ├── governance
│   ├── lobby
│   ├── message
│   ├── room
│   └── user
├── web
│   ├── controller
│   └── dto
└── ws
    ├── handler
    ├── message
    ├── registry
    └── session
```

说明：

- `common` 是基础设施公共层，不放业务状态。
- `domain` 只定义业务结构，不直接读写 Redis。
- `repository` 是 Redis 访问边界。
- `service` 是业务入口，负责组合 repository、事件、消息和推送。
- `web` 与 `ws` 是输入输出适配层。

---

## 5. 通用基础组件

### 5.1 API Envelope

HTTP 接口统一返回 Envelope。

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

失败响应：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ROOM_NOT_FOUND",
    "message": "room not found"
  }
}
```

基础类型建议：

- `ApiResponse<T>`
- `ApiError`
- `ErrorCode`

### 5.2 WebSocket Envelope

WebSocket 输入输出统一使用 Envelope。

```json
{
  "type": "SEND_PUBLIC_MESSAGE",
  "requestId": "req_xxx",
  "payload": {}
}
```

服务端推送：

```json
{
  "type": "MESSAGE_CREATED",
  "requestId": null,
  "payload": {}
}
```

基础类型建议：

- `WsInboundMessage`
- `WsOutboundMessage`
- `WsMessageType`
- `WsErrorPayload`

### 5.3 错误码

错误码应与 API 文档保持一致，至少覆盖：

| code | 含义 |
| --- | --- |
| `INVALID_ARGUMENT` | 入参不合法 |
| `USER_NOT_FOUND` | 用户不存在 |
| `USER_ALREADY_IN_ROOM` | 用户已处于其他房间 |
| `ROOM_NOT_FOUND` | 房间不存在 |
| `ROOM_EXPIRED` | 房间已过期 |
| `ROOM_FULL` | 房间人数已满 |
| `ROOM_PASSWORD_REQUIRED` | 需要房间密码 |
| `ROOM_PASSWORD_INVALID` | 房间密码错误 |
| `NICKNAME_DUPLICATED` | 房间内昵称重复 |
| `FORBIDDEN` | 权限不足 |
| `USER_MUTED` | 用户被禁言 |
| `USER_BANNED` | 用户被 Ban |
| `TARGET_NOT_FOUND` | 治理或定向消息目标不存在 |
| `ROOM_CONTEXT_MISMATCH` | 客户端房间上下文与服务端不一致 |

### 5.4 时间组件

所有业务时间统一通过 `Clock` 或封装后的 `TimeProvider` 获取。

必须覆盖的时间窗口：

- 大厅最近 5 分钟活跃人数。
- WebSocket 断开后的 5 分钟重连窗口。
- 空房 24 小时后销毁。
- 禁言结束时间。
- 消息历史中的最近 N 分钟。

### 5.5 ID 组件

服务端生成所有核心 ID：

- `userId`
- `roomId`
- `messageId`
- `eventId`

建议保留业务前缀：

| ID | 示例 |
| --- | --- |
| `userId` | `u_xxx` |
| `roomId` | `r_xxx` |
| `messageId` | `m_xxx` |
| `eventId` | `e_xxx` |

### 5.6 JSON 组件

Redis 中复杂对象统一 JSON 序列化。

建议集中提供：

- `JsonCodec.encode(Object)`
- `JsonCodec.decode(String, Class<T>)`
- `JsonCodec.decodeList(String, TypeReference<T>)`

业务模块不直接操作散落的 `ObjectMapper`。

### 5.7 房间级锁组件

单体 MVP 内可以先使用 JVM 内存锁，以 `roomId` 为粒度串行化关键命令。

适用流程：

- 创建房间后写成员和索引。
- 加入房间时通过 `room:members` 校验人数，通过 `room:member-detail` 校验昵称与读取详情，并同时写入两类成员 Key。
- 离开房间时删除成员、继承房主、推进空房状态。
- 踢出或 Ban 时同步删除成员顺序索引与成员详情、清理用户房间上下文、生成事件。
- 空房销毁时删除全部房间运行态 key。

该锁不解决多实例问题。多实例不属于 MVP。

---

## 6. 领域模型落地点

### 6.1 用户

核心类型：

- `UserSession`
- `UserStatus`

字段以详细设计为准：

- `userId`
- `nickname`
- `currentRoomId`
- `status`
- `connected`
- `lastConnectedAt`
- `lastDisconnectedAt`
- `createdAt`
- `updatedAt`

### 6.2 房间

核心类型：

- `Room`
- `RoomStatus`
- `HistoryStrategy`
- `HistoryStrategyType`
- `RoomMember`
- `MemberStatus`

约束：

- 人数上限范围为 1 到 20。
- 房间密码只保存哈希，不保存明文。
- `historyStrategy.type` 使用 `NONE / COUNT / MINUTES`。
- `RoomMember.joinedAt` 是房主继承排序依据。

### 6.3 消息

核心类型：

- `Message`
- `MessageType`

约束：

- `MessageType` 为 `PUBLIC / DIRECT / SYSTEM`。
- 公共消息全房间可见。
- 定向消息只对发送者和目标用户可见。
- 系统消息不能由客户端直接创建。
- 由 `RoomEvent` 生成的系统消息必须记录 `sourceEventId` 与 `sourceEventType`。

### 6.4 房间事件

核心类型：

- `RoomEvent`
- `RoomEventType`

事件类型：

- `USER_JOIN`
- `USER_LEAVE`
- `USER_RECONNECTING`
- `USER_RECONNECTED`
- `OWNER_TRANSFER`
- `USER_MUTED`
- `USER_KICKED`
- `USER_BANNED`
- `ROOM_EMPTY`
- `ROOM_EXPIRED`

约束：

- 事件记录房间运行态事实。
- 事件不直接替代聊天消息。
- `payload` 只保存事件特有补充信息，不重复公共字段。
- 房间销毁时事件流同步删除。

### 6.5 治理

核心类型：

- `MuteRecord`
- `BanRecord`

约束：

- 禁言影响公共消息与定向消息。
- Ban 只对当前房间生效。
- 被踢出用户回到大厅，未被 Ban 时允许再次加入。
- 被 Ban 用户如在房间内，需要同步移出房间。

---

## 7. Redis Repository 边界

### 7.1 Key 清单

后端只允许读写以下运行态 key：

| Key | 类型 | Repository |
| --- | --- | --- |
| `drrr:user:{userId}` | String(JSON) | `UserSessionRepository` |
| `drrr:room:{roomId}` | String(JSON) | `RoomRepository` |
| `drrr:room:members:{roomId}` | ZSet | `RoomMemberRepository` |
| `drrr:room:member-detail:{roomId}` | Hash(JSON) | `RoomMemberRepository` |
| `drrr:room:messages:{roomId}` | List | `MessageRepository` |
| `drrr:room:events:{roomId}` | List | `RoomEventRepository` |
| `drrr:room:active` | ZSet | `RoomIndexRepository` |
| `drrr:room:empty` | ZSet | `RoomIndexRepository` |
| `drrr:user:reconnecting` | ZSet | `UserSessionRepository` |
| `drrr:room:mute:{roomId}` | ZSet | `GovernanceRepository` |
| `drrr:room:mute:detail:{roomId}:{userId}` | String(JSON) | `GovernanceRepository` |
| `drrr:room:ban:{roomId}` | Set | `GovernanceRepository` |
| `drrr:room:ban:detail:{roomId}:{userId}` | String(JSON) | `GovernanceRepository` |
| `drrr:lobby:active-users` | ZSet | `LobbyRepository` |

### 7.2 Repository 规则

- Repository 返回领域对象或明确的缺失结果，不抛出业务异常给上层猜测。
- Repository 内部集中处理 JSON 编解码。
- RoomMemberRepository 维护双结构：ZSet(userId -> joinedAt) 负责顺序与计数，Hash(userId -> RoomMember JSON) 负责详情读取与状态更新。
- Repository 内部集中处理 Redis key 拼接。
- Service 不拼 Redis key。
- Controller 和 WebSocket handler 不直接访问 Repository。

### 7.3 消息裁剪

消息写入 `drrr:room:messages:{roomId}` 后，由聊天模块按房间历史策略裁剪。

裁剪规则：

- `NONE`：不返回历史消息，仍可保存当前实时消息所需的短期数据；最终以当前设计中的消息流生命周期为准。
- `COUNT`：保留最近 N 条。
- `MINUTES`：读取时按 `sentAt` 过滤最近 N 分钟；如需要主动裁剪，应只删除窗口外消息。

### 7.4 删除规则

房间销毁时必须删除：

- `drrr:room:{roomId}`
- `drrr:room:members:{roomId}`
- `drrr:room:member-detail:{roomId}`
- `drrr:room:messages:{roomId}`
- `drrr:room:events:{roomId}`
- `drrr:room:mute:{roomId}`
- `drrr:room:ban:{roomId}`
- 匹配该房间的 `drrr:room:mute:detail:{roomId}:{userId}`
- 匹配该房间的 `drrr:room:ban:detail:{roomId}:{userId}`
- `drrr:room:active` 中该 `roomId`
- `drrr:room:empty` 中该 `roomId`

用户会话 `drrr:user:{userId}` 不因房间销毁被整体删除，但需要清空相关 `currentRoomId` 或推进状态。

---

## 8. HTTP 层基座

### 8.1 Controller 划分

| Controller | 负责接口 |
| --- | --- |
| `SessionController` | 创建匿名会话 |
| `LobbyController` | 获取大厅数据 |
| `RoomController` | 创建、加入、离开、修改房间 |
| `GovernanceController` | 禁言、踢出、Ban |
| `ExportController` | 导出聊天记录 |

### 8.2 请求上下文

MVP 不引入登录态。HTTP 请求需要显式携带 `userId`，后端以 Redis 中 `UserSession` 为准。

推荐方式：

- 请求体或查询参数中的 `userId` 用于定位匿名会话。
- 后端校验 `drrr:user:{userId}` 是否存在。
- 涉及房间命令时校验 `UserSession.currentRoomId` 与目标 `roomId` 是否一致，或校验当前命令允许从大厅态进入目标房间。

### 8.3 Validation

使用 Bean Validation 做边界校验：

- 昵称非空。
- 房间名非空。
- 人数上限 1 到 20。
- 历史条数必须是允许值或正整数。
- 禁言自定义分钟数必须为正整数。
- 消息内容非空。

业务校验仍放在 service：

- 房间是否存在。
- 房间是否过期。
- 密码是否正确。
- 昵称是否重复。
- 是否房主。
- 是否被禁言或 Ban。

---

## 9. WebSocket 层基座

### 9.1 连接入口

连接地址：

```text
/ws/rooms/{roomId}
```

连接建立前必须校验：

- `userId` 对应 `UserSession` 存在。
- `UserSession.currentRoomId` 与路径 `roomId` 一致。
- `drrr:room:{roomId}` 存在且未过期。
- `drrr:room:members:{roomId}` 中存在该用户。

### 9.2 连接注册表

WebSocket 层需要维护内存连接注册表：

- `roomId -> userId -> session`
- `sessionId -> roomId/userId`

用途：

- 向房间广播 `MESSAGE_CREATED`。
- 向房间广播 `ROOM_EVENT_OCCURRED`。
- 向指定发送者和接收者推送定向消息。
- 踢出或 Ban 时通知目标连接并关闭或移出房间上下文。

该注册表是单体运行期内存结构，不作为权威业务状态。权威状态仍以 Redis 为准。

### 9.3 输入消息

客户端到服务端：

- `SEND_PUBLIC_MESSAGE`
- `SEND_DIRECT_MESSAGE`
- `RECONNECT_ROOM`

WebSocket handler 只解析 Envelope 并分发到对应 service。

### 9.4 输出消息

服务端到客户端：

- `MESSAGE_CREATED`
- `ROOM_EVENT_OCCURRED`
- `ROOM_STATE_SYNC`
- `ROOM_REMOVED`
- `ERROR`

系统消息推送链路：

1. 业务模块发生成员、治理或生命周期变化。
2. 房间事件模块写入 `RoomEvent`。
3. 聊天模块根据事件生成 `Message.type=SYSTEM`。
4. WebSocket 推送 `ROOM_EVENT_OCCURRED` 与 `MESSAGE_CREATED`。

房间配置变更当前没有独立 `RoomEvent` 类型，因此只生成 `SYSTEM` 消息，`sourceEventId=null`，`sourceEventType=null`。

---

## 10. Service 模块基座

### 10.1 大厅模块

职责：

- 统计最近 5 分钟活跃人数。
- 读取房间列表。
- 按最近活跃、人数、房间存活时间排序。

依赖：

- `LobbyRepository`
- `RoomRepository`
- `RoomMemberRepository`
- `TimeProvider`

### 10.2 用户会话模块

职责：

- 创建匿名会话。
- 维护在线、重连中、离线状态。
- 维护大厅活跃用户索引。
- 处理 WebSocket 断开和重连恢复。

依赖：

- `UserSessionRepository`
- `LobbyRepository`
- `TimeProvider`
- `IdGenerator`

### 10.3 房间模块

职责：

- 创建房间。
- 加入房间。
- 主动离开房间。
- 修改房间信息和配置。
- 推进 `ACTIVE / EMPTY / EXPIRED` 状态。

依赖：

- `RoomRepository`
- `RoomMemberRepository`
- `RoomIndexRepository`
- `UserSessionRepository`
- `GovernanceRepository`
- `RoomOwnerService`
- `RoomEventService`
- `MessageService`
- `RoomLock`

### 10.4 聊天模块

职责：

- 发送公共消息。
- 发送定向消息。
- 生成系统消息。
- 裁剪和读取历史消息。
- 做消息可见性过滤。

依赖：

- `MessageRepository`
- `RoomRepository`
- `RoomMemberRepository`
- `GovernanceService`
- `RoomIndexRepository`
- `WsPushService`

### 10.5 房间事件模块

职责：

- 记录房间轻量事件。
- 读取事件流。
- 触发 `ROOM_EVENT_OCCURRED` 推送。

依赖：

- `RoomEventRepository`
- `IdGenerator`
- `TimeProvider`
- `WsPushService`

约束：

- 不直接修改房间状态。
- 不直接删除成员。
- 不直接决定房主。

### 10.6 房主管理模块

职责：

- 初始房主标记。
- 房主离开后的继承。
- 权限校验。

依赖：

- `RoomRepository`
- `RoomMemberRepository`

### 10.7 治理模块

职责：

- 禁言。
- 禁言校验与过期清理。
- 踢出。
- Ban。

依赖：

- `GovernanceRepository`
- `RoomRepository`
- `RoomMemberRepository`
- `UserSessionRepository`
- `RoomOwnerService`
- `RoomEventService`
- `MessageService`
- `WsPushService`
- `RoomLock`

### 10.8 导出模块

职责：

- 房主导出当前保留范围内的聊天记录。
- 导出当前房间轻量事件日志。
- 生成 JSON 结构。

依赖：

- `RoomRepository`
- `RoomMemberRepository`
- `MessageRepository`
- `RoomEventRepository`

### 10.9 定时清理模块

职责：

- 扫描 5 分钟重连超时用户。
- 推进超时用户离房。
- 扫描空房 24 小时过期。
- 执行房间销毁。

依赖：

- `UserSessionRepository`
- `RoomRepository`
- `RoomMemberRepository`
- `RoomIndexRepository`
- `MessageRepository`
- `RoomEventRepository`
- `GovernanceRepository`
- `RoomOwnerService`
- `RoomEventService`
- `MessageService`
- `RoomLock`

---

## 11. 核心流程落地顺序

### 11.1 第一阶段：后端基础骨架

目标：让工程具备可持续开发的基础边界。

建议完成：

1. 通用响应 Envelope。
2. 错误码与业务异常。
3. 时间与 ID 组件。
4. JSON 编解码组件。
5. Redis key 常量与 repository 基类。
6. 核心领域对象与枚举。
7. 基础测试配置。

### 11.2 第二阶段：房间与会话主链路

目标：跑通创建匿名会话、创建房间、加入房间、离开房间。

建议完成：

1. `UserSessionService`
2. `RoomService`
3. `LobbyService`
4. 房主初始设置与继承。
5. 房间 ACTIVE/EMPTY 状态推进。

### 11.3 第三阶段：WebSocket 与消息

目标：跑通房间实时聊天。

建议完成：

1. WebSocket 连接校验。
2. 连接注册表。
3. 公共消息。
4. 定向消息。
5. 历史消息读取与裁剪。
6. WebSocket 错误推送。

### 11.4 第四阶段：事件、治理、清理、导出

目标：完成 MVP 验收中的治理与生命周期能力。

建议完成：

1. 房间事件写入与推送。
2. 系统消息生成。
3. 禁言。
4. 踢出。
5. Ban。
6. 重连超时清理。
7. 空房 24 小时销毁。
8. JSON 导出。

---

## 12. 测试基座

### 12.1 单元测试

优先覆盖：

- 时间窗口判断。
- ID 生成格式。
- 错误码映射。
- 房间参数校验。
- 昵称重复校验。
- 房主继承排序。
- 禁言过期判断。
- 消息可见性过滤。
- RoomEvent payload 约束。

### 12.2 Repository 测试

使用 Redis 测试依赖验证：

- JSON String 读写。
- List 顺序。
- Set 成员。
- ZSet score。
- key 删除完整性。

### 12.3 Service 集成测试

优先覆盖：

- 创建会话 -> 创建房间。
- 创建会话 -> 加入房间。
- 加入房间 -> 发公共消息。
- 加入房间 -> 发定向消息。
- 房主离开 -> 自动继承。
- 禁言后发消息失败。
- Ban 后重新入房失败。
- 断线 5 分钟内重连恢复。
- 空房 24 小时后销毁。
- 导出包含 messages 与 events。

### 12.4 WebSocket 测试

优先覆盖：

- 非成员无法建立房间 WebSocket。
- `SEND_PUBLIC_MESSAGE` 成功后广播 `MESSAGE_CREATED`。
- `SEND_DIRECT_MESSAGE` 只推送给发送者与接收者。
- 断开后进入 `RECONNECTING`。
- `RECONNECT_ROOM` 成功后推送 `ROOM_STATE_SYNC`。
- 业务错误返回 `ERROR`。

---

## 13. 配置基线

### 13.1 application.yaml 建议结构

```yaml
spring:
  application:
    name: drrr
  data:
    redis:
      host: localhost
      port: 6379

drrr:
  room:
    max-members-min: 1
    max-members-max: 20
    empty-expire-hours: 24
  user:
    reconnect-timeout-minutes: 5
    active-window-minutes: 5
  websocket:
    endpoint: /ws/rooms
```

以上配置仅表达当前 MVP 设计中已经存在的时间和人数边界，不新增产品能力。

### 13.2 Docker Compose

本地运行只需要后端应用和 Redis。若 `compose.yaml` 已包含 Redis，则后端配置应与 Compose 暴露端口保持一致。

---

## 14. Builder 实施守则

后续 Builder 实现时遵守：

1. 先做基础组件和领域对象，再做业务 service。
2. 每个业务模块只读写自己声明的 Redis key。
3. 不在 controller 或 WebSocket handler 中写业务状态。
4. 不绕过 `TimeProvider` 直接调用系统时间。
5. 不绕过 `IdGenerator` 直接生成业务 ID。
6. 不新增 Redis key，除非先更新详细设计和 API 文档。
7. 不把系统消息开放给客户端直接创建。
8. 不把房间事件和聊天消息混成同一个领域对象。
9. 不引入数据库、MQ、多实例锁或外部认证。
10. 每个可执行卡片完成后更新 Builder 记录。

---

## 15. 当前待确认问题

当前无。



