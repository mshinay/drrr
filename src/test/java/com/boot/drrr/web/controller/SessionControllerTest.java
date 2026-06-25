package com.boot.drrr.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boot.drrr.common.error.GlobalExceptionHandler;
import com.boot.drrr.config.DrrrProperties;
import com.boot.drrr.domain.user.UserSession;
import com.boot.drrr.domain.user.UserStatus;
import com.boot.drrr.service.user.UserSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class SessionControllerTest {
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    @Test
    void createSessionReturnsDocumentedEnvelope() throws Exception {
        validator.afterPropertiesSet();
        UserSessionService service = new UserSessionService(null, null, null, null, null, null, null, new DrrrProperties()) {
            @Override
            public UserSession createAnonymousSession(String nickname) {
                return new UserSession(
                        "u_123",
                        nickname,
                        null,
                        UserStatus.ONLINE,
                        false,
                        null,
                        null,
                        1717300000000L,
                        1717300000000L
                );
            }
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SessionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"Alice"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value("u_123"))
                .andExpect(jsonPath("$.data.nickname").value("Alice"))
                .andExpect(jsonPath("$.data.status").value("ONLINE"))
                .andExpect(jsonPath("$.data.currentRoomId").isEmpty());
    }

    @Test
    void createSessionRejectsBlankNicknameWithInvalidRequestEnvelope() throws Exception {
        validator.afterPropertiesSet();
        UserSessionService service = new UserSessionService(null, null, null, null, null, null, null, new DrrrProperties());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SessionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("nickname: must not be blank"));
    }
}

