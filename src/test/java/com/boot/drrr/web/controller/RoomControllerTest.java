package com.boot.drrr.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boot.drrr.common.error.GlobalExceptionHandler;
import com.boot.drrr.domain.room.HistoryStrategy;
import com.boot.drrr.domain.room.HistoryStrategyType;
import com.boot.drrr.domain.room.MemberStatus;
import com.boot.drrr.domain.room.Room;
import com.boot.drrr.domain.room.RoomMember;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.service.room.CreateRoomCommand;
import com.boot.drrr.service.room.CreateRoomView;
import com.boot.drrr.service.room.JoinRoomCommand;
import com.boot.drrr.service.room.JoinRoomView;
import com.boot.drrr.service.room.LeaveRoomCommand;
import com.boot.drrr.service.room.LeaveRoomResult;
import com.boot.drrr.service.room.RoomPasswordHasher;
import com.boot.drrr.service.room.RoomService;
import com.boot.drrr.service.room.UpdateRoomCommand;
import com.boot.drrr.service.room.UpdateRoomView;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class RoomControllerTest {
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void createJoinLeaveAndUpdateReturnDocumentedEnvelope() throws Exception {
        validator.afterPropertiesSet();
        RoomPasswordHasher hasher = new RoomPasswordHasher();
        RoomService service = new RoomService(null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public CreateRoomView createRoom(CreateRoomCommand command) {
                return new CreateRoomView(
                        new Room(
                                "r_123",
                                command.name().trim(),
                                "desc",
                                hasher.hashNullable(command.password()),
                                command.maxMembers(),
                                command.userId(),
                                command.userId(),
                                RoomStatus.ACTIVE,
                                command.userListVisible(),
                                command.historyStrategy(),
                                command.allowOwnerConfigChange(),
                                1717300000000L,
                                1717300000000L,
                                null
                        ),
                        new RoomMember("r_123", command.userId(), "Alice", MemberStatus.ONLINE, 1717300000000L, 1717300000000L, true)
                );
            }

            @Override
            public JoinRoomView joinRoom(String roomId, JoinRoomCommand command) {
                return new JoinRoomView(
                        new Room(
                                roomId,
                                "Night Talk",
                                "desc",
                                null,
                                6,
                                "u_owner",
                                "u_owner",
                                RoomStatus.ACTIVE,
                                true,
                                new HistoryStrategy(HistoryStrategyType.COUNT, 50),
                                true,
                                1717300000000L,
                                1717300100000L,
                                null
                        ),
                        new RoomMember(roomId, command.userId(), "Bob", MemberStatus.ONLINE, 1717300100000L, 1717300100000L, false),
                        List.of(
                                new RoomMember(roomId, "u_owner", "Owner", MemberStatus.ONLINE, 1717300000000L, 1717300000000L, true),
                                new RoomMember(roomId, command.userId(), "Bob", MemberStatus.ONLINE, 1717300100000L, 1717300100000L, false)
                        )
                );
            }

            @Override
            public LeaveRoomResult leaveRoom(String roomId, LeaveRoomCommand command) {
                return new LeaveRoomResult(true, true, "u_new_owner", RoomStatus.ACTIVE);
            }

            @Override
            public UpdateRoomView updateRoom(String roomId, UpdateRoomCommand command) {
                return new UpdateRoomView(
                        new Room(
                                roomId,
                                command.name().trim(),
                                command.description().trim(),
                                null,
                                6,
                                command.operatorUserId(),
                                command.operatorUserId(),
                                RoomStatus.ACTIVE,
                                command.userListVisible(),
                                command.historyStrategy(),
                                command.allowOwnerConfigChange(),
                                1717300000000L,
                                1717300200000L,
                                null
                        )
                );
            }
        };

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RoomController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"u_alice",
                                  "name":"Night Talk",
                                  "description":"desc",
                                  "password":"secret",
                                  "maxMembers":6,
                                  "userListVisible":true,
                                  "historyStrategy":{"type":"COUNT","value":50},
                                  "allowOwnerConfigChange":true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.room.roomId").value("r_123"))
                .andExpect(jsonPath("$.data.member.isOwner").value(true));

        mockMvc.perform(post("/api/rooms/r_123/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"u_bob","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.room.roomId").value("r_123"))
                .andExpect(jsonPath("$.data.members[1].userId").value("u_bob"));

        mockMvc.perform(post("/api/rooms/r_123/leave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"u_alice"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ownerTransferred").value(true))
                .andExpect(jsonPath("$.data.newOwnerUserId").value("u_new_owner"));

        mockMvc.perform(patch("/api/rooms/r_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorUserId":"u_owner",
                                  "name":"New Name",
                                  "description":"new desc",
                                  "userListVisible":false,
                                  "historyStrategy":{"type":"MINUTES","value":30},
                                  "allowOwnerConfigChange":false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.room.name").value("New Name"))
                .andExpect(jsonPath("$.data.room.userListVisible").value(false));
    }

    @Test
    void createRoomRejectsBlankNameWithInvalidRequestEnvelope() throws Exception {
        validator.afterPropertiesSet();
        RoomService service = new RoomService(null, null, null, null, null, null, null, null, null, null, null);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RoomController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"u_alice",
                                  "name":" ",
                                  "description":"desc",
                                  "maxMembers":6,
                                  "userListVisible":true,
                                  "historyStrategy":{"type":"COUNT","value":50},
                                  "allowOwnerConfigChange":true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("name: must not be blank"));
    }
}

