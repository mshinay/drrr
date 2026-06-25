# Builder Card Repo

## Active Card

Title: Card 09: Implement Governance Commands

Status:
Implemented and verified in the backend workspace.

Goal:
Implement owner-only mute, kick, and ban commands with Redis governance records, room-event/system-message generation, kick/ban target notification, and chat/join enforcement that matches the current MVP contract.

Files Involved:
- `src/main/java/com/boot/drrr/service/governance/GovernanceService.java`
- `src/main/java/com/boot/drrr/service/governance/MuteMemberCommand.java`
- `src/main/java/com/boot/drrr/service/governance/KickMemberCommand.java`
- `src/main/java/com/boot/drrr/service/governance/BanMemberCommand.java`
- `src/main/java/com/boot/drrr/service/governance/MuteMemberResult.java`
- `src/main/java/com/boot/drrr/service/governance/KickMemberResult.java`
- `src/main/java/com/boot/drrr/service/governance/BanMemberResult.java`
- `src/main/java/com/boot/drrr/service/event/RoomEventService.java`
- `src/main/java/com/boot/drrr/web/controller/GovernanceController.java`
- `src/main/java/com/boot/drrr/web/dto/governance/MuteMemberRequest.java`
- `src/main/java/com/boot/drrr/web/dto/governance/MuteMemberResponse.java`
- `src/main/java/com/boot/drrr/web/dto/governance/KickMemberRequest.java`
- `src/main/java/com/boot/drrr/web/dto/governance/KickMemberResponse.java`
- `src/main/java/com/boot/drrr/web/dto/governance/BanMemberRequest.java`
- `src/main/java/com/boot/drrr/web/dto/governance/BanMemberResponse.java`
- `src/test/java/com/boot/drrr/service/governance/GovernanceServiceTest.java`
- `src/test/java/com/boot/drrr/web/controller/GovernanceControllerTest.java`
- `docs/builder/card_repo.md`

Implementation Scope:
- Added `GovernanceService` as the owner-checked application entry for `muteMember(...)`, `kickMember(...)`, and `banMember(...)`.
- Implemented `POST /api/rooms/{roomId}/members/{targetUserId}/mute`, `/kick`, and `/ban` through `GovernanceController` and dedicated governance DTOs.
- Persisted mute runtime state through the existing governance Redis repository using the documented ZSet + detail key pair.
- Persisted ban runtime state through the existing governance Redis repository using the documented Set + detail key pair.
- Enforced owner-only governance by requiring the operator to be the current room owner member before any mutation runs.
- Required target membership for mute and kick, and required target user existence for ban so offline users can still be banned.
- Cleared kicked/banned users from the room member structures, cleared `currentRoomId`, removed reconnecting markers, and kept kicked users rejoinable when they are not banned.
- Reused existing join-time ban enforcement and existing send-time mute enforcement so governance immediately affects chat and room entry behavior.
- Extended `RoomEventService` to record `USER_MUTED`, `USER_KICKED`, and `USER_BANNED`, generate matching `SYSTEM` messages, and include the kicked/banned target in event/message visibility where required.
- Kept unrelated pre-existing doc edits in `doc/*.md` and `docs/planner/builder-task-cards.md` untouched.

Verification Run:
- Executed with Java 21:
  - `JAVA_HOME=D:\work\language\Java\21`
  - `PATH=D:\work\language\Java\21\bin;%PATH%`
  - `./mvnw.cmd -q "-Dtest=GovernanceServiceTest,GovernanceControllerTest,RoomEventServiceTest,MessageServiceTest,RoomServiceTest" test`
- Result: passed.

Acceptance Coverage:
- Non-owner governance requests fail with `FORBIDDEN`.
- Muted users are blocked from both public and direct messages until the mute expires.
- Kicked users lose room membership, lose reconnect recovery for the old room, and can rejoin when not banned.
- Banned users are removed when present, remain banned on repeated ban calls, and cannot rejoin the room.
- Governance actions append matching `RoomEvent` entries, generate matching `SYSTEM` messages, and notify the kicked/banned target connection when it still exists.

Scope Guard:
This card stays within governance command delivery plus the required event/message integration. It does not introduce moderator roles, temporary bans, or new persistence beyond the documented Redis runtime records and `RoomEvent` history.

ready-for-closeout: yes
