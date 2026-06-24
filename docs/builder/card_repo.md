# Builder Card Repo

## Active Card

Title: Card 05: Implement Room Creation, Join, Leave, and Owner Transfer

Status:
Review-fix round completed. Original Card 05 implementation was blocked by Planner review on same-user cross-room join concurrency and inherited-owner config permission semantics. Both blockers are resolved in this round.

Goal:
Implement the core room lifecycle: create room, join room, leave room, room metadata update, owner assignment, owner inheritance, and ACTIVE/EMPTY transitions, then harden the write path so user-room context cannot diverge under concurrent joins and inherited owner config permissions match the documented contract.

Files Involved:
- `src/main/java/com/boot/drrr/common/lock/**`
- `src/main/java/com/boot/drrr/domain/room/Room.java`
- `src/main/java/com/boot/drrr/service/room/**`
- `src/main/java/com/boot/drrr/service/owner/**`
- `src/main/java/com/boot/drrr/web/controller/RoomController.java`
- `src/main/java/com/boot/drrr/web/dto/room/**`
- `src/test/java/com/boot/drrr/common/JvmRoomLockTest.java`
- `src/test/java/com/boot/drrr/domain/DomainJsonCodecTest.java`
- `src/test/java/com/boot/drrr/repository/RedisRepositoryIntegrationTest.java`
- `src/test/java/com/boot/drrr/service/lobby/LobbyServiceTest.java`
- `src/test/java/com/boot/drrr/service/room/**`
- `src/test/java/com/boot/drrr/service/owner/**`
- `src/test/java/com/boot/drrr/web/controller/RoomControllerTest.java`
- `docs/builder/card_repo.md`

Reviewer Blockers Addressed:
- `joinRoom` no longer locks only by `roomId`. Room lifecycle mutations now use a shared deterministic multi-key lock policy covering the acting user and target room.
- `updateRoom` no longer treats all owners identically. `Room` now persists `initialOwnerUserId`, and inherited owners are blocked with `CONFIG_LOCKED` when `allowOwnerConfigChange=false`.

Implemented Changes:
- Extended `Room` persistence/read models with `initialOwnerUserId` and exposed it through `RoomResponse`.
- Updated create-room to set both `ownerUserId` and `initialOwnerUserId` to the creator.
- Updated join/leave/owner-repair/update flows to preserve `initialOwnerUserId` across room state transitions.
- Extended `RoomLock` and `JvmRoomLock` with deterministic multi-key locking based on sorted distinct keys, plus automatic cleanup after execution.
- Replaced ad hoc single-key room locking in `RoomService` with a shared helper that locks `user:{userId}` and `room:{roomId}` where applicable.
- Enforced documented config-lock behavior in `updateRoom`: current owner is still required, and inherited owners now receive `CONFIG_LOCKED` when room config changes are locked.
- Updated regression coverage for JSON codec, Redis repository round-trip, lobby room fixtures, lock behavior, controller envelopes, and room service lifecycle/concurrency behavior.

Verification Run:
- Executed with Java 21:
  - `JAVA_HOME=D:\work\language\Java\21`
  - `PATH=D:\work\language\Java\21\bin;%PATH%`
  - `./mvnw.cmd -q "-Dtest=JvmRoomLockTest,DomainJsonCodecTest,RedisRepositoryIntegrationTest,LobbyServiceTest,OwnerTransferServiceTest,RoomServiceTest,RoomControllerTest" test`
- Result: passed.
- Verified by tests:
  - Room create persists `initialOwnerUserId` with the creator and still hashes passwords.
  - Same user cannot successfully join two rooms concurrently; only one join wins and user session/member state remain single-room consistent.
  - Owner transfer still selects earliest remaining `joinedAt` and preserves the initial owner marker.
  - Last-member leave still transitions room to `EMPTY` and writes the empty-room index.
  - Initial owner may update locked config; inherited owner may update only when `allowOwnerConfigChange=true`; otherwise `CONFIG_LOCKED` is returned.
  - Room JSON and repository serialization round-trip the new `initialOwnerUserId` field.
  - Multi-key JVM lock ordering does not deadlock and releases lock entries after execution.

Scope Guard:
This round stays inside Card 05 review-fix scope. It does not add WebSocket broadcasts, chat message persistence, room event writes, cleanup scheduling, governance commands, reconnect flow changes, or distributed locking infrastructure.

ready-for-closeout: yes
