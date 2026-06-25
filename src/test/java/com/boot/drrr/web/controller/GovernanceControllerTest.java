package com.boot.drrr.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boot.drrr.common.error.GlobalExceptionHandler;
import com.boot.drrr.domain.governance.MuteRecord;
import com.boot.drrr.domain.room.RoomStatus;
import com.boot.drrr.service.governance.BanMemberCommand;
import com.boot.drrr.service.governance.BanMemberResult;
import com.boot.drrr.service.governance.GovernanceService;
import com.boot.drrr.service.governance.KickMemberCommand;
import com.boot.drrr.service.governance.KickMemberResult;
import com.boot.drrr.service.governance.MuteMemberCommand;
import com.boot.drrr.service.governance.MuteMemberResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class GovernanceControllerTest {
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void muteKickAndBanReturnDocumentedEnvelope() throws Exception {
        validator.afterPropertiesSet();
        GovernanceService service = new GovernanceService(null, null, null, null, null, null, null, null, null, null) {
            @Override
            public MuteMemberResult muteMember(String roomId, String targetUserId, MuteMemberCommand command) {
                return new MuteMemberResult(true, new MuteRecord(roomId, targetUserId, command.operatorUserId(), 1717300200000L, 1717302000000L, command.reason()));
            }

            @Override
            public KickMemberResult kickMember(String roomId, String targetUserId, KickMemberCommand command) {
                return new KickMemberResult(true, targetUserId, RoomStatus.ACTIVE, false, null);
            }

            @Override
            public BanMemberResult banMember(String roomId, String targetUserId, BanMemberCommand command) {
                return new BanMemberResult(true, targetUserId, true);
            }
        };

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new GovernanceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        mockMvc.perform(post("/api/rooms/r_123/members/u_target/mute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorUserId":"u_owner",
                                  "durationMinutes":30,
                                  "reason":"owner_action"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.muted").value(true))
                .andExpect(jsonPath("$.data.record.userId").value("u_target"));

        mockMvc.perform(post("/api/rooms/r_123/members/u_target/kick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorUserId":"u_owner",
                                  "reason":"owner_action"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.kicked").value(true))
                .andExpect(jsonPath("$.data.targetUserId").value("u_target"));

        mockMvc.perform(post("/api/rooms/r_123/members/u_target/ban")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorUserId":"u_owner",
                                  "reason":"owner_action"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.banned").value(true))
                .andExpect(jsonPath("$.data.kicked").value(true));
    }

    @Test
    void muteRejectsBlankOperatorWithInvalidRequestEnvelope() throws Exception {
        validator.afterPropertiesSet();
        GovernanceService service = new GovernanceService(null, null, null, null, null, null, null, null, null, null);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new GovernanceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        mockMvc.perform(post("/api/rooms/r_123/members/u_target/mute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorUserId":" ",
                                  "durationMinutes":0,
                                  "reason":"owner_action"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("operatorUserId: must not be blank")))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("durationMinutes: must be greater than or equal to 1")));
    }
}


