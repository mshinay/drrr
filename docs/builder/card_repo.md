# Builder Card Repo

## Active Card

Title: Card 01: Establish Backend Foundation Types

Goal:
Create the shared backend foundation later service cards can build on: HTTP response envelopes, error codes, business exceptions, time, IDs, JSON helpers, Redis key constants, room-level locking, and baseline configuration properties.

Files Involved:
- `pom.xml`
- `src/main/java/com/boot/drrr/DrrrApplication.java`
- `src/main/java/com/boot/drrr/common/**`
- `src/main/java/com/boot/drrr/config/**`
- `src/main/resources/application.yaml`
- `src/test/java/com/boot/drrr/common/**`
- `docs/builder/card_repo.md`

Required Changes:
- Added `ApiResponse` / `ApiError` matching the documented HTTP envelope shape.
- Added minimal shared WebSocket envelope records for future room messaging code.
- Added `ErrorCode`, `BusinessException`, and `GlobalExceptionHandler` aligned to the API spec error code set.
- Added injectable `TimeProvider` over a configured `Clock` bean.
- Added prefixed ID generation for `userId`, `roomId`, `messageId`, and `eventId`.
- Added `JsonCodec` around Spring Boot 4's configured Jackson 3 `ObjectMapper`.
- Added `RedisKeys` constants and factory methods for all documented `drrr:` runtime keys.
- Added `RoomLock` plus JVM-backed `JvmRoomLock` keyed by `roomId`.
- Updated `JvmRoomLock` lifecycle so room locks remain registered after each critical section and are only removed via explicit `release(roomId)` during later room-destroy cleanup.
- Added `DrrrProperties` plus baseline `application.yaml` settings for reconnect timeout, lobby active window, empty-room expiry, member bounds, and WebSocket endpoint.
- Added focused tests for exception envelope mapping, ID prefixes, Redis key rendering, time injection, and property binding.
- Added a focused JvmRoomLock lifecycle test covering lock retention until explicit release.
- Updated build dependencies so the Boot 4 JSON object mapper is available through `spring-boot-starter-json`.

Reference Intent:
Later backend cards should reuse one consistent foundation for IDs, time, errors, JSON, Redis key naming, and room-level locking instead of re-inventing incompatible utilities.

Non-goals:
- No business services were implemented.
- No Redis repositories beyond shared key utilities were implemented.
- No WebSocket handlers or runtime room messaging flows were implemented.
- No API paths or product rules were changed.

## Changed Files

- `pom.xml`: added `spring-boot-starter-json` so Boot 4 exposes the configured Jackson 3 object mapper required by `JsonCodec`.
- `src/main/java/com/boot/drrr/DrrrApplication.java`: enabled configuration properties scanning.
- `src/main/java/com/boot/drrr/common/api/`: added shared HTTP envelope records.
- `src/main/java/com/boot/drrr/common/error/`: added error enum, business exception, and global HTTP exception mapper.
- `src/main/java/com/boot/drrr/common/id/`: added shared prefixed ID generator.
- `src/main/java/com/boot/drrr/common/json/`: added JSON codec wrapper over Boot 4 Jackson.
- `src/main/java/com/boot/drrr/common/lock/`: added room-level JVM lock abstraction.
- `src/main/java/com/boot/drrr/common/redis/`: added canonical `drrr:` Redis key factory.
- `src/main/java/com/boot/drrr/common/time/`: added `Clock`-backed injectable time provider.
- `src/main/java/com/boot/drrr/common/ws/`: added minimal shared WebSocket envelope records.
- `src/main/java/com/boot/drrr/config/`: added `Clock` bean and typed `DrrrProperties` binding.
- `src/main/resources/application.yaml`: added backend foundation configuration defaults.
- `src/test/java/com/boot/drrr/common/`: added focused verification tests for envelope/error handling, ID prefixes, Redis keys, time injection, property binding, and room-lock lifecycle.

## Verification Run

- `JAVA_HOME=D:\work\language\Java\21; PATH prepend D:\work\language\Java\21\bin; ./mvnw test` passed.
- Test result summary: 8 tests, 0 failures, 0 errors.
- Verified by tests:
  - HTTP error envelope shape and exception mapping.
  - ID generator prefixes `u_`, `r_`, `m_`, `e_`.
  - Exact rendering of all documented shared Redis keys.
  - Injectable fixed clock behavior through `TimeProvider`.
  - Configuration binding for reconnect timeout, lobby active window, empty-room expiry, member bounds, and WebSocket endpoint.
  - Room lock retention after unlock and explicit cleanup via `release(roomId)`.

## Scope Guard

This card only establishes backend foundation types and configuration. It does not introduce service logic, repository implementations, WebSocket handlers, or any product-scope changes.

## Next Step

The next backend service card can build directly on `common/` and `config/` without adding duplicate utility layers. Room, session, lobby, and governance flows should consume these shared primitives rather than creating ad hoc IDs, time calls, JSON helpers, Redis key strings, or lock logic.

ready-for-closeout: yes



