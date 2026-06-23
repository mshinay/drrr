# Builder Card Repo

## Active Card

Title: Card 03: Implement Redis Repository Layer

Goal:
Encapsulate all documented Redis reads and writes behind repository classes, without implementing full business workflows.

Files Involved:
- `src/main/java/com/boot/drrr/repository/user/**`
- `src/main/java/com/boot/drrr/repository/room/**`
- `src/main/java/com/boot/drrr/repository/message/**`
- `src/main/java/com/boot/drrr/repository/event/**`
- `src/main/java/com/boot/drrr/repository/governance/**`
- `src/main/java/com/boot/drrr/repository/lobby/**`
- `src/test/java/com/boot/drrr/repository/**`
- `src/main/resources/application.yaml`
- `docs/builder/card_repo.md`

Required Changes:
- Configured a shared `RedisTemplate<String, String>` with String serializers so repositories read and write documented JSON strings consistently.
- Added `UserSessionRepository` for `drrr:user:{userId}` and `drrr:user:reconnecting`.
- Added `LobbyRepository` for `drrr:lobby:active-users`.
- Added `RoomRepository` for `drrr:room:{roomId}`.
- Added `RoomMemberRepository` for `drrr:room:members:{roomId}` and `drrr:room:member-detail:{roomId}`.
- Added `RoomIndexRepository` as the explicit shared index owner for `drrr:room:active` and `drrr:room:empty`.
- Added `MessageRepository` for `drrr:room:messages:{roomId}`.
- Added `RoomEventRepository` for `drrr:room:events:{roomId}`.
- Added `GovernanceRepository` for `drrr:room:mute:{roomId}`, `drrr:room:mute:detail:{roomId}:{userId}`, `drrr:room:ban:{roomId}`, and `drrr:room:ban:detail:{roomId}:{userId}`.
- Tightened repository APIs so they expose key ownership plus raw score/range/list operations only, leaving time-window decisions, history trimming strategy, and other workflow judgments to future services.
- Added repository integration tests covering String, List, Set, and ZSet behavior, plus a Redis cleanup helper that skips with an explicit message when no local Redis test instance is reachable.
- Added a configuration fallback test that verifies template serializers and repository owner wiring without requiring a live Redis daemon.

Reference Intent:
Redis remains the runtime source of truth, but service and transport layers now have one repository owner per documented key or one explicit shared index repository, instead of manually composing keys and scattering Redis operations.

Non-goals:
- No HTTP endpoints were implemented.
- No service workflows were implemented.
- No new Redis keys were introduced.
- No database persistence was introduced.

## Changed Files

- `src/main/java/com/boot/drrr/config/RedisConfig.java`: added the shared Redis template serialization config.
- `src/main/java/com/boot/drrr/repository/RedisJsonOperations.java`: added JSON encode/decode helpers, typed Redis operation access, and a shared Lua script execution hook for multi-step repository writes.
- `src/main/java/com/boot/drrr/repository/user/UserSessionRepository.java`: narrowed reconnecting-user access to plain save/remove/score-range reads.
- `src/main/java/com/boot/drrr/repository/lobby/LobbyRepository.java`: renamed ZSet access to raw `zAdd`/`zRem`/`zCountByScore`/`zRangeByScore` semantics so lobby activity rules stay in future services.
- `src/main/java/com/boot/drrr/repository/room/RoomRepository.java`: added room JSON persistence.
- `src/main/java/com/boot/drrr/repository/room/RoomMemberRepository.java`: changed room-member persistence to the documented dual structure, batches member-detail reads while preserving ZSet order, and wraps index/detail writes in one Lua script per operation.
- `src/main/java/com/boot/drrr/repository/room/RoomIndexRepository.java`: replaced `active`/`empty` business verbs with raw ZSet operations plus an index-key selector so selection logic can live in services.
- `src/main/java/com/boot/drrr/repository/message/MessageRepository.java`: changed message trimming to raw Redis list trim bounds instead of repository-level keep-last policy.
- `src/main/java/com/boot/drrr/repository/event/RoomEventRepository.java`: added room event List access.
- `src/main/java/com/boot/drrr/repository/governance/GovernanceRepository.java`: narrowed mute/ban access to record persistence plus raw index membership/range reads.
- `src/test/java/com/boot/drrr/repository/AbstractRedisRepositoryTest.java`: added test Redis cleanup support with an explicit environment-skip guard.
- `src/test/java/com/boot/drrr/repository/RedisRepositoryIntegrationTest.java`: updated repository integration coverage to assert storage semantics instead of service-level policy semantics.
- `src/test/java/com/boot/drrr/repository/RedisRepositoryConfigurationTest.java`: added fallback verification for serializer wiring and repository ownership registration.
- `src/main/resources/application.yaml`: added Redis host and port baseline entries for local runtime and tests.

## Verification Run

- Executed with Java 21:
  - `JAVA_HOME=D:\work\language\Java\21`
  - `PATH=D:\work\language\Java\21\bin;%PATH%`
  - `./mvnw.cmd -q "-Dtest=RedisRepositoryIntegrationTest,RedisRepositoryConfigurationTest" test`
- Result: passed.
- Verified by tests:
  - String-key and JSON-string repository persistence for `UserSession`, `Room`, `MuteRecord`, and `BanRecord`.
  - Room-member dual-structure persistence where the ZSet stores only ordered `userId` members, the Hash stores `RoomMember` JSON details, list reads use one batched hash fetch in ZSet order, and save/remove run in one Lua script.
  - List ordering plus raw Redis trim behavior for room messages and room events.
  - Set membership behavior for room bans.
  - ZSet score-range behavior for reconnecting users, lobby active users, room members, active rooms, empty rooms, and mute expirations.
  - `RedisTemplate<String, String>` serializer wiring and repository bean ownership registration.

## Scope Guard

This card only adds Redis repository ownership, shared serialization setup, and repository-level tests. Repository APIs now stop at raw Redis persistence and index access. `active` / `empty` selection and any future nickname-based rules remain service-layer work.

## Next Step

The next backend card can build service workflows directly on these repository owners without re-deciding key names, JSON shapes, or base Redis collection behavior.

ready-for-closeout: yes






