# Builder Card Repo

## Active Card

Title: Card 10: Implement Cleanup Scheduler

Status:
Implemented and verified in the backend workspace.

Goal:
Implement scheduled cleanup for reconnect timeout, empty-room expiry, and complete room runtime-data deletion so Redis runtime state follows the MVP lifecycle contract.

Files Involved:
- `src/main/java/com/boot/drrr/config/FoundationConfig.java`
- `src/main/resources/application.yaml`
- `src/main/java/com/boot/drrr/service/cleanup/CleanupService.java`
- `src/main/java/com/boot/drrr/service/cleanup/CleanupScheduler.java`
- `src/main/java/com/boot/drrr/service/event/RoomEventService.java`
- `src/main/java/com/boot/drrr/repository/governance/GovernanceRepository.java`
- `src/main/java/com/boot/drrr/ws/RoomWebSocketConnectionRegistry.java`
- `src/main/java/com/boot/drrr/ws/RoomWebSocketOperations.java`
- `src/main/java/com/boot/drrr/ws/RoomRemovedPayload.java`
- `src/test/java/com/boot/drrr/service/cleanup/CleanupServiceTest.java`
- `docs/builder/card_repo.md`

Implementation Scope:
- Enabled Spring scheduling in the foundation config and wired two cleanup jobs for reconnect-timeout scans and empty-room expiry scans.
- Added `CleanupService` to scan `drrr:user:reconnecting` for users older than the configured 5-minute reconnect timeout and move them to `OFFLINE` with `currentRoomId=null`.
- Reused room lifecycle rules during reconnect-timeout cleanup so member removal, owner transfer, `EMPTY` transitions, `drrr:room:empty`, and room leave events stay aligned with the current MVP behavior.
- Added empty-room expiry cleanup that scans `drrr:room:empty`, verifies the room is still expired under the 24-hour window, records `ROOM_EXPIRED` when stale connections still exist, and then deletes all room runtime keys.
- Completed runtime deletion by removing room data, member data, message/event lists, mute/ban indexes, mute/ban detail keys, and room active/empty indexes.
- Added WebSocket support to enumerate room-bound stale connections, push `ROOM_REMOVED { reason: EXPIRED }`, and close those room sessions after expiry cleanup.
- Extended `RoomEventService` so `ROOM_EXPIRED` can emit its event/system-message flow to stale recipients before the room runtime keys are deleted.
- Left the pre-existing unrelated edits in `doc/api-spec.md`, `doc/backend-foundation.md`, `doc/detailed-design.md`, and `docs/planner/builder-task-cards.md` untouched.

Verification Run:
- Executed with Java 21:
  - `JAVA_HOME=D:\work\language\Java\21`
  - `PATH=D:\work\language\Java\21\bin;%PATH%`
  - `./mvnw.cmd -q "-Dtest=CleanupServiceTest" test`
  - `./mvnw.cmd -q "-Dtest=CleanupServiceTest,RoomEventServiceTest,RoomServiceTest,GovernanceServiceTest,MessageServiceTest,UserSessionServiceTest" test`
- Result: passed.

Acceptance Coverage:
- Reconnecting users older than 5 minutes are removed from room membership, removed from `drrr:user:reconnecting`, and persisted as `OFFLINE` with no current room.
- Owner transfer and empty-room transitions still occur when timed-out reconnecting users leave behind active members or no members.
- Empty rooms older than 24 hours are expired and fully deleted.
- Room expiry deletes the documented runtime keys and both active/empty indexes, including mute/ban detail keys.
- Tests use injectable time to verify both the 5-minute reconnect timeout and the 24-hour empty-room expiry window.
- Stale room-bound WebSocket connections receive `ROOM_EXPIRED`/system-message delivery when observable, then receive `ROOM_REMOVED` before the cleanup closes the sessions.

Scope Guard:
This card stays within scheduled runtime cleanup and lifecycle deletion. It does not add tombstones, historical export retention, distributed locking, or admin cleanup endpoints.

ready-for-closeout: yes
