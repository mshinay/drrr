package com.boot.drrr.service.owner;

import static org.assertj.core.api.Assertions.assertThat;

import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.RoomMember;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnerTransferServiceTest {

    @Test
    void selectNextOwnerReturnsEarliestJoinedMember() {
        OwnerTransferService service = new OwnerTransferService();

        RoomMember ownerCandidate = service.selectNextOwner(List.of(
                new RoomMember("r_1", "u_2", "Bob", MemberStatus.ONLINE, 20L, 20L, false),
                new RoomMember("r_1", "u_1", "Alice", MemberStatus.ONLINE, 10L, 10L, false)
        )).orElseThrow();

        assertThat(ownerCandidate.userId()).isEqualTo("u_1");
    }
}
