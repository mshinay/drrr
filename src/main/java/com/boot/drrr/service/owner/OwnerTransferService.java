package com.boot.drrr.service.owner;

import com.boot.drrr.domain.room.RoomMember;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class OwnerTransferService {
    public Optional<RoomMember> selectNextOwner(List<RoomMember> remainingMembers) {
        if (remainingMembers == null || remainingMembers.isEmpty()) {
            return Optional.empty();
        }
        return remainingMembers.stream()
                .min(Comparator.comparingLong(RoomMember::joinedAt).thenComparing(RoomMember::userId));
    }

    public List<RoomMember> applyOwnerFlag(List<RoomMember> members, String ownerUserId) {
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream()
                .map(member -> new RoomMember(
                        member.roomId(),
                        member.userId(),
                        member.nickname(),
                        member.memberStatus(),
                        member.joinedAt(),
                        member.lastActiveAt(),
                        member.userId().equals(ownerUserId)
                ))
                .toList();
    }
}
