# Builder Task Cards

> Planner output for backend-first implementation.

## Planner Frame

Mode: Mode A, 0 to 1 Build Mode.

Working goal:
Implement the MVP backend in narrow Builder cards, preserving the contracts in `doc/proposal.md`, `doc/detailed-design.md`, `doc/api-spec.md`, and `doc/backend-foundation.md`.

Execution rule:
Builder executes one card at a time. After each card, update `docs/builder/card_repo.md` with the active card, changed files, verification run, scope guard, next step, and `ready-for-closeout`.

Global non-goals:
- Do not introduce database persistence.
- Do not introduce MQ.
- Do not introduce multi-instance coordination.
- Do not add account login, friends, following, user profile, media upload, recommendation, or AI scope.
- Do not invent new HTTP endpoints, WebSocket message types, or Redis keys outside the current docs.
- Do not start frontend implementation in these cards.

Reference docs:
- `doc/proposal.md`
- `doc/detailed-design.md`
- `doc/api-spec.md`
- `doc/backend-foundation.md`
- `doc/tasks/progress.md`

## Stage Order

1. Foundation
2. Core room lifecycle
3. Real-time communication
4. Governance and lifecycle completion
5. Integration verification

---

## Card 01: Establish Backend Foundation Types

### Goal

Create the shared backend foundation that later service cards can build on: response envelopes, error codes, business exceptions, time, ID, JSON helpers, Redis key constants, and room-level locking.

### Files Involved

- `src/main/java/com/boot/drrr/common/**`
- `src/main/java/com/boot/drrr/config/**`
- `src/test/java/com/boot/drrr/common/**`
- `src/main/resources/application.yaml`
- `docs/builder/card_repo.md`

### Required Changes

- Add HTTP response envelope types matching `doc/api-spec.md`.
- Add WebSocket envelope base types only if needed by shared code; full WebSocket handling belongs to a later card.
- Add `ErrorCode` values from the API spec.
- Add a `BusinessException` and global HTTP exception mapping.
- Add injectable time provider wrapping `Clock`.
- Add ID generator methods for `userId`, `roomId`, `messageId`, and `eventId` with the documented prefixes.
- Add JSON codec wrapper around the configured object mapper.
- Add Redis key constants/factory methods for the documented `drrr:` keys.
- Add a JVM room lock abstraction keyed by `roomId`.
- Add configuration properties for reconnect timeout, lobby active window, empty-room expiry, max member bounds, and WebSocket endpoint.

### Reference Intent

The backend must share one foundation for IDs, time, errors, JSON, Redis keys, and lock boundaries so later cards do not improvise incompatible patterns.

### Non-goals

- Do not implement business services.
- Do not implement Redis repositories beyond constants/base utilities.
- Do not implement WebSocket handlers.
- Do not change product rules or API paths.

### Acceptance Criteria

- `mvn test` or the focused equivalent passes.
- Error envelope shape can be asserted by at least one controller-slice or exception-handler test.
- ID generator tests prove prefixes `u_`, `r_`, `m_`, and `e_`.
- Redis key factory tests prove all documented keys render exactly.
- Time provider is injectable in tests.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- Spring Boot 4.1 APIs may differ from older examples; Builder should follow installed dependency APIs, not old snippets.

---

## Card 02: Add Core Domain Models

### Goal

Add the domain objects and enums needed by all backend modules without wiring business flows yet.

### Files Involved

- `src/main/java/com/boot/drrr/domain/user/**`
- `src/main/java/com/boot/drrr/domain/room/**`
- `src/main/java/com/boot/drrr/domain/message/**`
- `src/main/java/com/boot/drrr/domain/event/**`
- `src/main/java/com/boot/drrr/domain/governance/**`
- `src/test/java/com/boot/drrr/domain/**`
- `docs/builder/card_repo.md`

### Required Changes

- Add `UserSession` and `UserStatus`.
- Add `Room`, `RoomStatus`, `RoomMember`, `MemberStatus`, `HistoryStrategy`, and `HistoryStrategyType`.
- Add `Message` and `MessageType`.
- Add `RoomEvent` and `RoomEventType`.
- Add `MuteRecord` and `BanRecord`.
- Keep fields aligned with `doc/detailed-design.md` and `doc/api-spec.md`.
- Add basic serialization/deserialization tests using the shared JSON codec.

### Reference Intent

Redis stores complex objects as JSON, so domain classes must stabilize field names and enum values before repositories and services are built.

### Non-goals

- Do not add persistence annotations for a database.
- Do not add service logic.
- Do not add DTOs except minimal test helpers if needed.
- Do not change documented field names.

### Acceptance Criteria

- Domain JSON tests pass for representative `UserSession`, `Room`, `RoomMember`, `Message`, `RoomEvent`, `MuteRecord`, and `BanRecord`.
- Enum values match documented values exactly.
- `Message` supports `sourceEventId` and `sourceEventType` for system messages.
- `RoomEvent.payload` can represent event-specific JSON without duplicating common fields.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- Java records are concise, but future partial updates may be easier with mutable DTO-style classes. Builder should choose one style and keep it consistent.

---

## Card 03: Implement Redis Repository Layer

### Goal

Encapsulate all documented Redis reads and writes behind repository classes, without implementing full business workflows.

### Files Involved

- `src/main/java/com/boot/drrr/repository/user/**`
- `src/main/java/com/boot/drrr/repository/room/**`
- `src/main/java/com/boot/drrr/repository/message/**`
- `src/main/java/com/boot/drrr/repository/event/**`
- `src/main/java/com/boot/drrr/repository/governance/**`
- `src/main/java/com/boot/drrr/repository/lobby/**`
- `src/test/java/com/boot/drrr/repository/**`
- `src/main/resources/application.yaml`
- `docs/builder/card_repo.md`

### Required Changes

- Configure Redis template serialization for String keys and JSON values.
- Add repositories for:
  - `drrr:user:{userId}`
  - `drrr:user:reconnecting`
  - `drrr:lobby:active-users`
  - `drrr:room:{roomId}`
  - `drrr:room:members:{roomId}`
  - `drrr:room:member-detail:{roomId}`
  - `drrr:room:active`
  - `drrr:room:empty`
  - `drrr:room:messages:{roomId}`
  - `drrr:room:events:{roomId}`
  - `drrr:room:mute:{roomId}`
  - `drrr:room:mute:detail:{roomId}:{userId}`
  - `drrr:room:ban:{roomId}`
  - `drrr:room:ban:detail:{roomId}:{userId}`
- Add repository tests for String, Hash, List, Set, and ZSet behavior.
- Add cleanup helpers for tests if needed.

### Reference Intent

Redis is the runtime source of truth. Business services should not manually compose keys or scatter Redis operations.

### Non-goals

- Do not implement HTTP endpoints.
- Do not implement service workflows.
- Do not add new Redis keys.
- Do not add database persistence.

### Acceptance Criteria

- Repository tests pass against the configured Redis test setup.
- Each documented key has exactly one repository owner or explicit shared index repository.
- Service/controller layers are not introduced in this card.
- Key names match `doc/api-spec.md` and `doc/backend-foundation.md`.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- Redis test dependencies may require local Docker or Spring test support. If unavailable, Builder must document the exact blocker and provide the smallest useful fallback tests.

---

## Card 04: Implement User Session and Lobby APIs

### Goal

Implement anonymous session creation and lobby data retrieval as the first user-visible HTTP slice.

### Files Involved

- `src/main/java/com/boot/drrr/service/user/**`
- `src/main/java/com/boot/drrr/service/lobby/**`
- `src/main/java/com/boot/drrr/web/controller/SessionController.java`
- `src/main/java/com/boot/drrr/web/controller/LobbyController.java`
- `src/main/java/com/boot/drrr/web/dto/**`
- `src/test/java/com/boot/drrr/service/user/**`
- `src/test/java/com/boot/drrr/service/lobby/**`
- `src/test/java/com/boot/drrr/web/controller/**`
- `docs/builder/card_repo.md`

### Required Changes

- Implement `POST /api/sessions`.
- Implement `GET /api/lobby`.
- Create `UserSession` with generated `userId`, nickname, `ONLINE` status, timestamps, and no room.
- Write `drrr:user:{userId}`.
- Update `drrr:lobby:active-users`.
- Read active users within the 5-minute window.
- Read active rooms and member counts for lobby cards.
- Support lobby sorting fields documented in `proposal.md`.

### Reference Intent

Users enter the MVP through nickname-only anonymous sessions and a lobby showing recent active users plus rooms.

### Non-goals

- Do not implement room creation or join.
- Do not implement WebSocket.
- Do not implement frontend.
- Do not add authentication.

### Acceptance Criteria

- `POST /api/sessions` returns the documented envelope and session payload.
- `GET /api/lobby` returns active user count and room summaries.
- Invalid nickname returns the documented error envelope.
- Lobby active count respects the 5-minute configured window via injectable time.
- Tests cover service behavior and HTTP envelope behavior.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- Sorting behavior should stay minimal and match documented MVP fields; do not add pagination unless docs are updated first.

---

## Card 05: Implement Room Creation, Join, Leave, and Owner Transfer

### Goal

Implement the core room lifecycle: create room, join room, leave room, member state updates, owner assignment, owner inheritance, and ACTIVE/EMPTY transitions.

### Files Involved

- `src/main/java/com/boot/drrr/service/room/**`
- `src/main/java/com/boot/drrr/service/owner/**`
- `src/main/java/com/boot/drrr/web/controller/RoomController.java`
- `src/main/java/com/boot/drrr/web/dto/room/**`
- `src/test/java/com/boot/drrr/service/room/**`
- `src/test/java/com/boot/drrr/service/owner/**`
- `src/test/java/com/boot/drrr/web/controller/RoomController*`
- `docs/builder/card_repo.md`

### Required Changes

- Implement `POST /api/rooms`.
- Implement `POST /api/rooms/{roomId}/join`.
- Implement `POST /api/rooms/{roomId}/leave`.
- Implement `PATCH /api/rooms/{roomId}` or the method documented in `doc/api-spec.md`.
- Validate room name, max members, password, nickname duplication, current user room context, room status, and room full state.
- Store password hash only, not plaintext.
- Write room, member-index, member-detail, user-session, active-room, and empty-room indexes as documented.
- Assign initial owner on create.
- Transfer owner by earliest `joinedAt` when current owner leaves.
- Move room to `EMPTY` and set `emptySince` when the last member leaves.

### Reference Intent

Room is the core aggregate. This card establishes the MVP's room lifecycle before chat, governance, and cleanup depend on it.

### Non-goals

- Do not implement WebSocket broadcasts.
- Do not implement chat message sending.
- Do not implement RoomEvent writes except if a previous accepted card already created a required event API and this card explicitly calls it; otherwise leave events to Card 07.
- Do not implement cleanup scheduler.

### Acceptance Criteria

- Create room writes `Room`, creator member index, creator `RoomMember` detail, creator `UserSession.currentRoomId`, and `drrr:room:active`.
- Join room rejects full, expired, password-invalid, duplicate nickname, and banned users.
- Leave room clears user room context and removes both member index and member detail.
- Owner transfer selects earliest remaining `joinedAt`.
- Last member leaving sets room `EMPTY` and writes `drrr:room:empty`.
- Tests cover create, join, leave, owner transfer, and empty transition.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- Ban checks require governance repository availability from Card 03, but full Ban command is later. If no Ban data exists yet, only repository-backed check is needed.

---

## Card 06: Implement WebSocket Connection Infrastructure

### Goal

Implement `/ws/rooms/{roomId}` connection validation, in-memory connection registry, room broadcast, direct push, disconnect callback, and WebSocket error envelope.

### Files Involved

- `src/main/java/com/boot/drrr/config/**`
- `src/main/java/com/boot/drrr/ws/**`
- `src/main/java/com/boot/drrr/service/user/**`
- `src/test/java/com/boot/drrr/ws/**`
- `docs/builder/card_repo.md`

### Required Changes

- Configure WebSocket endpoint `/ws/rooms/{roomId}`.
- Validate `userId`, `UserSession.currentRoomId`, room existence/status, and membership before accepting the room context.
- Add connection registry:
  - `roomId -> userId -> session`
  - `sessionId -> roomId/userId`
- Add room broadcast and user-targeted push APIs.
- On disconnect, notify user session service to move the user/member into `RECONNECTING`.
- Return `ERROR` envelope for handled WebSocket message failures.

### Reference Intent

The WebSocket layer is transport and routing. Redis remains authoritative; the in-memory registry only tracks live connections in the single running backend.

### Non-goals

- Do not implement public/direct chat message semantics.
- Do not implement frontend reconnect UI.
- Do not add multi-instance registry or pub/sub.
- Do not add new message types.

### Acceptance Criteria

- Non-member or mismatched `roomId` cannot establish usable room context.
- Accepted connection is registered by room and user.
- Disconnect updates user/member state to `RECONNECTING` and writes `drrr:user:reconnecting`.
- Broadcast and direct push can be tested with fake or test WebSocket sessions.
- WebSocket errors use the documented `ERROR` shape.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- Spring WebSocket testing can be awkward. Builder should prefer small unit tests for registry/handler behavior plus one integration check if practical.

---

## Card 07: Implement Chat Messages

### Goal

Implement public messages, direct messages, history retrieval/cropping, visibility filtering, and `MESSAGE_CREATED` push.

### Files Involved

- `src/main/java/com/boot/drrr/service/message/**`
- `src/main/java/com/boot/drrr/ws/handler/**`
- `src/main/java/com/boot/drrr/ws/message/**`
- `src/test/java/com/boot/drrr/service/message/**`
- `src/test/java/com/boot/drrr/ws/**`
- `docs/builder/card_repo.md`

### Required Changes

- Handle `SEND_PUBLIC_MESSAGE`.
- Handle `SEND_DIRECT_MESSAGE`.
- Validate sender session, room context, membership, target membership, and muted state.
- Write `Message.type=PUBLIC` and `Message.type=DIRECT` to `drrr:room:messages:{roomId}`.
- Apply history strategy for `NONE`, `COUNT`, and `MINUTES`.
- Push `MESSAGE_CREATED` to all online room members for public messages.
- Push `MESSAGE_CREATED` only to sender and target for direct messages.
- Expose service method for reading history according to room strategy and message visibility.

### Reference Intent

Chat is real-time and temporary. Direct messages live in the same room message flow but are visible only to sender and recipient.

### Non-goals

- Do not implement system messages from RoomEvent in this card unless Card 08 calls into the message service after this card.
- Do not implement governance commands.
- Do not implement export.
- Do not add persistence beyond Redis message list.

### Acceptance Criteria

- Public message writes Redis and broadcasts to room.
- Direct message writes Redis and only pushes to sender/target.
- Muted sender cannot send public or direct messages.
- Non-member target fails for direct messages.
- History read filters direct messages by current user visibility.
- Tests cover message write, visibility, mute check, and push targeting.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- History strategy `NONE` should remain aligned with current docs; if implementation ambiguity appears, Builder must document the chosen minimal behavior in the Builder report.

---

## Card 08: Implement Room Events and System Messages

### Goal

Implement RoomEvent creation, event reading, `ROOM_EVENT_OCCURRED` push, and system message generation from room events.

### Files Involved

- `src/main/java/com/boot/drrr/service/event/**`
- `src/main/java/com/boot/drrr/service/message/**`
- `src/main/java/com/boot/drrr/ws/**`
- `src/test/java/com/boot/drrr/service/event/**`
- `src/test/java/com/boot/drrr/service/message/**`
- `docs/builder/card_repo.md`

### Required Changes

- Add `RoomEventService` methods to record and read events.
- Write events to `drrr:room:events:{roomId}`.
- Push `ROOM_EVENT_OCCURRED` after a successful event write.
- Generate `Message.type=SYSTEM` for event types that need user-visible messages.
- Populate `sourceEventId` and `sourceEventType` on event-derived system messages.
- Wire event creation into existing room/user/chat flows where already implemented:
  - `USER_JOIN`
  - `USER_LEAVE`
  - `USER_RECONNECTING`
  - `USER_RECONNECTED`
  - `OWNER_TRANSFER`
  - `ROOM_EMPTY`
- Keep room configuration change as system-message-only unless docs add a RoomEvent type.

### Reference Intent

RoomEvent records room runtime facts, while system messages make those facts visible in chat. Both are exported separately and deleted with the room.

### Non-goals

- Do not merge RoomEvent and Message into one object.
- Do not add new event types.
- Do not implement mute/kick/ban commands beyond event hooks needed by later cards.
- Do not change the API contract.

### Acceptance Criteria

- Event writes preserve order.
- `payload` stores only event-specific supplemental data.
- Event-derived system messages include `sourceEventId` and `sourceEventType`.
- `ROOM_EVENT_OCCURRED` is pushed to expected recipients.
- Existing room join/leave/reconnect/owner-transfer flows create documented events.
- Tests cover event write/read, payload shape, system message generation, and push.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- Some flows from earlier cards may need small integration edits. Keep edits limited to wiring events into accepted service methods.

---

## Card 09: Implement Governance Commands

### Goal

Implement mute, kick, and Ban operations with owner permission checks, Redis governance records, member/session updates, events, system messages, and target notifications.

### Files Involved

- `src/main/java/com/boot/drrr/service/governance/**`
- `src/main/java/com/boot/drrr/web/controller/GovernanceController.java`
- `src/main/java/com/boot/drrr/web/dto/governance/**`
- `src/main/java/com/boot/drrr/service/room/**`
- `src/main/java/com/boot/drrr/service/event/**`
- `src/main/java/com/boot/drrr/service/message/**`
- `src/test/java/com/boot/drrr/service/governance/**`
- `src/test/java/com/boot/drrr/web/controller/GovernanceController*`
- `docs/builder/card_repo.md`

### Required Changes

- Implement `POST /api/rooms/{roomId}/members/{targetUserId}/mute`.
- Implement `POST /api/rooms/{roomId}/members/{targetUserId}/kick`.
- Implement `POST /api/rooms/{roomId}/members/{targetUserId}/ban`.
- Check operator is current room owner.
- Check target exists where required.
- Write mute ZSet and mute detail record.
- Write ban Set and ban detail record.
- Kick clears target `currentRoomId`, removes both member index and member detail, and blocks old `roomId` auto-reconnect.
- Ban removes target from room if present, clears both member index and member detail, and prevents future join.
- Emit `USER_MUTED`, `USER_KICKED`, `USER_BANNED`, and any resulting `OWNER_TRANSFER` or `ROOM_EMPTY`.
- Notify target connection where applicable.

### Reference Intent

Owner governance is part of MVP room culture and must affect chat permissions, membership, rejoin behavior, and event/system-message history.

### Non-goals

- Do not add moderator roles beyond owner.
- Do not add temporary Ban; Ban is permanent for the current room.
- Do not add audit persistence outside RoomEvent and Redis runtime records.
- Do not change direct/public message behavior except through mute checks.

### Acceptance Criteria

- Non-owner governance requests fail with `FORBIDDEN`.
- Muted user cannot send public or direct messages until expiry.
- Kicked user returns to lobby state and may rejoin if not banned.
- Banned user cannot rejoin the current room.
- Governance actions produce matching RoomEvents and system messages.
- Owner transfer and empty-room transitions still work after kick/Ban.
- Tests cover permission, mute, kick, Ban, target notification, and event generation.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- Governance touches many services. Builder must avoid refactoring unrelated room or chat code beyond required integration points.

---

## Card 10: Implement Cleanup Scheduler

### Goal

Implement scheduled cleanup for reconnect timeout, empty-room expiry, and complete room runtime-data deletion.

### Files Involved

- `src/main/java/com/boot/drrr/service/cleanup/**`
- `src/main/java/com/boot/drrr/config/**`
- `src/main/java/com/boot/drrr/service/room/**`
- `src/main/java/com/boot/drrr/service/event/**`
- `src/main/java/com/boot/drrr/service/message/**`
- `src/test/java/com/boot/drrr/service/cleanup/**`
- `docs/builder/card_repo.md`

### Required Changes

- Enable and configure scheduled cleanup.
- Scan `drrr:user:reconnecting` for users disconnected longer than 5 minutes.
- Move timed-out users out of their room and update room/member/owner state.
- Scan `drrr:room:empty` for rooms empty longer than 24 hours.
- Record `ROOM_EXPIRED` before deletion when possible.
- Delete all room runtime keys listed in the lifecycle deletion constraint.
- Remove room from active and empty indexes.
- Notify remaining/stale connections with `ROOM_REMOVED` if any exist.

### Reference Intent

Rooms and users are temporary. Cleanup completes the lifecycle promised by the MVP and ensures Redis does not keep expired room state.

### Non-goals

- Do not add persistent tombstones.
- Do not keep historical exports after room deletion.
- Do not add distributed locking.
- Do not add admin cleanup endpoints unless already documented.

### Acceptance Criteria

- Reconnecting users older than 5 minutes become offline/out of room according to current design.
- Empty rooms older than 24 hours are expired and fully deleted.
- Room deletion removes all documented room keys and indexes.
- Same room name can later be recreated without old data.
- Tests use injectable time to verify both timeout windows.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- `ROOM_EXPIRED` event is deleted with the room by design. Tests should assert the write-then-delete behavior only if observable through mocks or service seams.

---

## Card 11: Implement Chat Export

### Goal

Implement owner-only JSON export of current retained messages and current room event log.

### Files Involved

- `src/main/java/com/boot/drrr/service/export/**`
- `src/main/java/com/boot/drrr/web/controller/ExportController.java`
- `src/main/java/com/boot/drrr/web/dto/export/**`
- `src/test/java/com/boot/drrr/service/export/**`
- `src/test/java/com/boot/drrr/web/controller/ExportController*`
- `docs/builder/card_repo.md`

### Required Changes

- Implement `GET /api/rooms/{roomId}/export`.
- Check room exists and operator is current owner.
- Read current retained messages from `drrr:room:messages:{roomId}`.
- Read current events from `drrr:room:events:{roomId}`.
- Return JSON export structure matching `doc/api-spec.md`.
- Include filename format such as `room_{roomId}.json`.

### Reference Intent

Export is a personal save action by the current room owner. The system still does not keep permanent server-side history.

### Non-goals

- Do not write export files to server disk unless explicitly required by API behavior.
- Do not export deleted room data.
- Do not allow non-owner export.
- Do not add CSV or other formats.

### Acceptance Criteria

- Owner can export room JSON with room metadata, messages, and events.
- Non-owner export fails with documented error.
- Missing/expired room export fails with documented error.
- Export includes only currently retained messages.
- Tests cover owner success, non-owner failure, and message/event inclusion.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- If HTTP response download headers are not yet specified, return the documented envelope/payload shape rather than inventing file-download behavior.

---

## Card 12: Run Backend MVP Integration Verification

### Goal

Add and run end-to-end backend integration tests that prove the MVP paths work together across HTTP, Redis, WebSocket, events, governance, cleanup, and export.

### Files Involved

- `src/test/java/com/boot/drrr/integration/**`
- `src/test/resources/**`
- `docs/builder/card_repo.md`

### Required Changes

- Add integration tests for:
  - create session -> create room -> join room
  - join room -> WebSocket connect -> public message
  - direct message visibility
  - owner leave -> owner transfer
  - mute -> message blocked
  - kick -> target lobby state and optional rejoin allowed
  - Ban -> rejoin blocked
  - disconnect -> reconnect within 5 minutes
  - disconnect -> timeout cleanup
  - empty room -> 24-hour cleanup
  - export includes messages and events
- Verify Redis keys are cleaned between tests.
- Run the broadest backend test command available.

### Reference Intent

Earlier cards prove pieces. This card proves the MVP backend behaves as one system and is ready for frontend integration.

### Non-goals

- Do not add frontend tests.
- Do not redesign services to make tests easier unless a bug requires a narrow fix.
- Do not add product behavior outside the existing docs.

### Acceptance Criteria

- Integration test suite passes.
- `mvn test` passes, or any blocker is documented with exact failure text and smallest next step.
- MVP acceptance mapping in `doc/tasks/progress.md` can be updated honestly if the user asks for status tracking.
- `docs/builder/card_repo.md` records verification and scope.

### Risks

- This card may reveal bugs in earlier cards. Fix only defects required for the integration paths and keep unrelated cleanup as follow-up cards.

---

## Follow-up Frontend Card Boundary

Frontend work is intentionally deferred. After backend MVP integration passes, Planner should create a separate frontend card set for:

- Lobby page and nickname entry.
- Room page and message flow.
- Owner governance controls.
- WebSocket reconnect UI.
- Export action.
- End-to-end browser verification.



