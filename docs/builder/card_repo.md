# Builder Card Repo

## Active Card

Title: Card 06: Implement WebSocket Connection Infrastructure

Status:
Implemented and verified in the backend workspace. This round adds room-scoped WebSocket handshake validation, single-node live-connection tracking, room broadcast/direct push helpers, disconnect-to-RECONNECTING state transition, and documented `ERROR` envelope behavior for handled inbound message failures.

Goal:
Implement `/ws/rooms/{roomId}` connection validation, in-memory connection registry, room broadcast, direct push, disconnect callback, and WebSocket error envelope while keeping Redis authoritative for room and user state.

Files Involved:
- `src/main/java/com/boot/drrr/config/WebSocketConfig.java`
- `src/main/java/com/boot/drrr/service/user/RoomSessionContext.java`
- `src/main/java/com/boot/drrr/service/user/UserSessionService.java`
- `src/main/java/com/boot/drrr/repository/room/RoomMemberRepository.java`
- `src/main/java/com/boot/drrr/ws/**`
- `src/test/java/com/boot/drrr/service/user/UserSessionServiceTest.java`
- `src/test/java/com/boot/drrr/web/controller/SessionControllerTest.java`
- `src/test/java/com/boot/drrr/ws/**`
- `docs/builder/card_repo.md`

Implemented Changes:
- Registered `/ws/rooms/{roomId}` through `WebSocketConfig` and attached a handshake interceptor plus room text handler.
- Added handshake-time validation for `userId`, `UserSession.currentRoomId`, room existence/expiry, member-order index presence, and member-detail presence before the connection is accepted.
- Extended `UserSessionService` with room-connection validation, connect-time ONLINE refresh, disconnect-time RECONNECTING transition, and `drrr:user:reconnecting` write-through.
- Extended `RoomMemberRepository` with a direct membership-order existence check so the WebSocket layer can validate both documented Redis membership structures.
- Added a single-node in-memory registry that tracks both `roomId -> userId -> WebSocketSession` and `sessionId -> (roomId, userId)`.
- Added reusable room broadcast and user-targeted push helpers that serialize documented outbound envelopes.
- Added handled inbound-message failure behavior that returns the documented `ERROR` envelope only to the requesting connection.
- Added focused unit tests for the registry, push helpers, handshake validation wiring, handler error envelope behavior, and reconnecting session/member transition coverage.

Verification Run:
- Executed with Java 21:
  - `JAVA_HOME=D:\work\language\Java\21`
  - `PATH=D:\work\language\Java\21\bin;%PATH%`
  - `./mvnw.cmd -q "-Dtest=UserSessionServiceTest,RoomWebSocketConnectionRegistryTest,RoomWebSocketOperationsTest,RoomWebSocketHandlerAndHandshakeTest" test`
- Result: passed.
- Verified by tests:
  - Invalid room context is rejected at handshake time and does not receive room-scoped attributes.
  - Accepted connections are registered by room, user, and session id.
  - Broadcast reaches only sessions in the target room; direct push reaches only the targeted user session.
  - Disconnect transitions the user session and room member to `RECONNECTING` and records the user in `drrr:user:reconnecting`.
  - Unsupported/handled inbound WebSocket requests return the documented `ERROR` envelope back only to the originating session.

Scope Guard:
This round stays inside Card 06 transport/routing infrastructure. It does not implement public/direct chat semantics, reconnect state replay, frontend reconnect UX, multi-instance registry coordination, pub/sub fan-out, or any new business message type handling beyond returning documented `ERROR` envelopes for handled failures.

ready-for-closeout: yes
