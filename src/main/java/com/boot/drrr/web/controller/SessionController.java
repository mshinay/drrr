package com.boot.drrr.web.controller;

import com.boot.drrr.common.api.ApiResponse;
import com.boot.drrr.service.user.UserSessionService;
import com.boot.drrr.web.dto.CreateSessionRequest;
import com.boot.drrr.web.dto.SessionResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final UserSessionService userSessionService;

    public SessionController(UserSessionService userSessionService) {
        this.userSessionService = userSessionService;
    }

    @PostMapping
    public ApiResponse<SessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return ApiResponse.success(SessionResponse.from(
                userSessionService.createAnonymousSession(request.nickname())
        ));
    }
}
