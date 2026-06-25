# Builder Card Repo

## Active Card

Title: Card 08R: Fix Room Event Review Blockers

Status:
Implemented and verified in the backend workspace. This follow-up fixes the Card 08 review blockers around join/config-change room-event wiring, reconnect-event gating, and lifecycle side-effect dispatch timing.

Goal:
Resolve the Card 08 review blockers before closeout without changing the documented HTTP/WebSocket contracts, Redis model split, or room lifecycle semantics.

Files Involved:
- `src/main/java/com/boot/drrr/repository/user/UserSessionRepository.java`
- `src/main/java/com/boot/drrr/service/room/RoomService.java`
- `src/main/java/com/boot/drrr/service/user/UserSessionService.java`
- `src/test/java/com/boot/drrr/service/room/RoomServiceTest.java`
- `src/test/java/com/boot/drrr/service/user/UserSessionServiceTest.java`
- `docs/builder/card_repo.md`

Review-Fix Scope:
- Wired `RoomService.joinRoom(...)` to record `USER_JOIN` after the locked room/member/session/index mutation succeeds.
- Wired `RoomService.updateRoom(...)` to create the room-configuration `SYSTEM` message after the locked room update succeeds, keeping `sourceEventId=null` and `sourceEventType=null`.
- Refactored room lifecycle methods to collect pending room-event/system-message side effects inside the lifecycle lock and dispatch them synchronously after the lock is released.
- Kept existing leave-room event behavior, but moved `USER_LEAVE`, `OWNER_TRANSFER`, and `ROOM_EMPTY` side effects out of the lifecycle lock as part of the same dispatch model.
- Added `UserSessionRepository.isReconnectingUser(...)` and gated `USER_RECONNECTED` emission in `UserSessionService.markRoomConnected(...)` so first successful WebSocket connections for already-online members do not create reconnect events.
- Preserved the real reconnect path: `markRoomDisconnected(...)` still records `USER_RECONNECTING`, and a subsequent reconnect from reconnecting state still records `USER_RECONNECTED`.
- Added focused tests for:
  - join-room `USER_JOIN` creation and lock-outside dispatch timing
  - update-room config `SYSTEM` message creation and lock-outside dispatch timing
  - first-connect-no-reconnect behavior
  - real reconnect behavior

Verification Run:
- Executed with Java 21:
  - `JAVA_HOME=D:\work\language\Java\21`
  - `PATH=D:\work\language\Java\21\bin;%PATH%`
  - `./mvnw.cmd -q "-Dtest=RoomEventServiceTest,RoomServiceTest,UserSessionServiceTest,MessageServiceTest,RoomWebSocketOperationsTest,RoomWebSocketHandlerAndHandshakeTest,RoomControllerTest,SessionControllerTest" test`
- Result: passed.

Scope Guard:
This review-fix round stays inside Card 08 blockers only. It does not add new `RoomEventType` values, change message persistence contracts, redesign the lifecycle lock model, or introduce Card 09 behavior.

ready-for-closeout: yes
