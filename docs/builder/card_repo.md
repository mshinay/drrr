# Builder Card Repo

## Active Card

Title: Card 02: Add Core Domain Models

Goal:
Add the shared backend domain records and enums that all later repository and service cards will persist as JSON in Redis, while keeping the simplified room password model and documented field names stable before any business flow wiring begins.

Files Involved:
- `src/main/java/com/boot/drrr/domain/user/**`
- `src/main/java/com/boot/drrr/domain/room/**`
- `src/main/java/com/boot/drrr/domain/message/**`
- `src/main/java/com/boot/drrr/domain/event/**`
- `src/main/java/com/boot/drrr/domain/governance/**`
- `src/test/java/com/boot/drrr/domain/**`
- `docs/builder/card_repo.md`

Required Changes:
- Added `UserSession` and `UserStatus`.
- added `Room`, `RoomStatus`, `RoomMember`, `MemberStatus`, `HistoryStrategy`, and `HistoryStrategyType`, with `Room` simplified to `passwordHash` only.
- Added `Message` and `MessageType`.
- Added `RoomEvent` and `RoomEventType`.
- Added `MuteRecord` and `BanRecord`.
- Removed redundant `hasPassword` from the room contract and aligned field names with `doc/detailed-design.md` and `doc/api-spec.md`.
- Added JSON serialization and deserialization tests using the shared `JsonCodec`.
- Chose Java records consistently for this card so Redis JSON field layout stays explicit with minimal boilerplate.

Reference Intent:
Later repository and service modules should all read and write one canonical JSON shape for sessions, rooms, members, messages, room events, and governance records instead of drifting into incompatible field names or enum spellings.

Non-goals:
- No persistence annotations were added.
- No service logic was implemented.
- No DTO layer was introduced beyond direct domain JSON verification.
- No documented field names or enum values were changed.

## Changed Files

- `src/main/java/com/boot/drrr/domain/user/`: added `UserSession` and `UserStatus`.
- `src/main/java/com/boot/drrr/domain/room/`: added `Room`, `RoomStatus`, `RoomMember`, `MemberStatus`, `HistoryStrategy`, and `HistoryStrategyType`, with `Room` simplified to `passwordHash` only.
- `src/main/java/com/boot/drrr/domain/message/`: added `Message` and `MessageType`, including `sourceEventId` and `sourceEventType`.
- `src/main/java/com/boot/drrr/domain/event/`: added `RoomEvent` and `RoomEventType`, with `payload` stored as flexible JSON via Jackson `JsonNode`.
- `src/main/java/com/boot/drrr/domain/governance/`: added `MuteRecord` and `BanRecord`.
- `src/test/java/com/boot/drrr/domain/DomainJsonCodecTest.java`: added shared-codec round-trip coverage for representative domain objects.

## Verification Run

- `JAVA_HOME=D:\work\language\Java\21; PATH prepend D:\work\language\Java\21\bin; .\\mvnw.cmd -q "-Dtest=DomainJsonCodecTest,FoundationTypesTest" test` passed.
- Test result summary: 8 tests, 0 failures, 0 errors.
- Verified by tests:
  - `DomainJsonCodecTest`: 7 representative JSON round-trip tests passed for `UserSession`, `Room`, `RoomMember`, `Message`, `RoomEvent`, `MuteRecord`, and `BanRecord`.
  - `FoundationTypesTest`: 1 shared foundation verification class passed, covering ID prefixes and documented Redis key rendering.
  - `Message` includes nullable `sourceEventId` and `sourceEventType` for system-message backtracking.
  - `Room` no longer persists redundant `hasPassword`; password presence is derived from `passwordHash != null`.
  - `RoomEvent.payload` accepts structured event-specific JSON without duplicating common event fields.

## Scope Guard

This card only introduces core domain models and JSON verification. It does not add repositories, service flows, persistence annotations, or transport DTO wiring.

## Next Step

The next backend card can build repositories and services directly on these domain records, using the stabilized enum values and JSON field names as the contract for Redis storage and module boundaries.

ready-for-closeout: yes



