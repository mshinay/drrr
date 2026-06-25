# Builder Card Repo

## Active Card

Title: Card 07: Implement Chat Messages

Status:
Implemented and verified in the backend workspace. This round adds public/direct chat message handling, room-scoped visibility filtering, history cropping for `NONE`/`COUNT`/`MINUTES`, mute enforcement, and `MESSAGE_CREATED` push routing on top of the Card 06 WebSocket infrastructure.

Goal:
Implement public messages, direct messages, history retrieval/cropping, visibility filtering, and `MESSAGE_CREATED` push while keeping Redis list storage and room/user validation aligned with the current docs.

Files Involved:
- `src/main/java/com/boot/drrr/service/message/**`
- `src/main/java/com/boot/drrr/ws/RoomWebSocketHandler.java`
- `src/main/java/com/boot/drrr/ws/message/**`
- `src/test/java/com/boot/drrr/service/message/**`
- `src/test/java/com/boot/drrr/ws/RoomWebSocketHandlerAndHandshakeTest.java`
- `docs/builder/card_repo.md`

Implemented Changes:
- Added `MessageService` plus dedicated public/direct send commands to validate room context, sender membership, direct-message target membership, sender mute state, and normalized message content.
- Stored `PUBLIC` and `DIRECT` messages in `drrr:room:messages:{roomId}` and refreshed `drrr:room:{roomId}` plus `drrr:room:active` whenever a message is accepted.
- Implemented history trimming for all three documented strategies:
  - `NONE`: append for the live send path, then immediately clear the Redis list so later history reads remain empty.
  - `COUNT`: keep only the newest configured message count.
  - `MINUTES`: keep only messages whose `sentAt` remains inside the configured rolling time window.
- Exposed `readVisibleHistory(roomId, viewerUserId)` so later room-sync/reconnect work can read history through one service that applies both room strategy and direct-message visibility filtering.
- Added WebSocket payload models for `SEND_PUBLIC_MESSAGE`, `SEND_DIRECT_MESSAGE`, and outbound `MESSAGE_CREATED` payloads.
- Extended `RoomWebSocketHandler` to route public/direct inbound commands, reject room/user context mismatches, broadcast public messages to all online room members, and push direct messages only to sender plus target.
- Added focused unit tests covering Redis-like message persistence semantics, direct-message visibility filtering, mute rejection and expiry cleanup, non-member direct-message failure, `NONE`/`MINUTES` history cropping behavior, and WebSocket push targeting.

Verification Run:
- Executed with Java 21:
  - `JAVA_HOME=D:\work\language\Java\21`
  - `PATH=D:\work\language\Java\21\bin;%PATH%`
  - `./mvnw.cmd -q "-Dtest=MessageServiceTest,RoomWebSocketHandlerAndHandshakeTest,RoomWebSocketOperationsTest" test`
- Result: passed.

Scope Guard:
This round stays inside Card 07 chat-message behavior. It does not add system-message generation from `RoomEvent`, reconnect replay/state sync, governance owner commands, exports, or any persistence beyond the documented Redis message list.

ready-for-closeout: yes

