package com.boot.drrr.web.controller;

import com.boot.drrr.common.api.ApiResponse;
import com.boot.drrr.service.lobby.LobbyService;
import com.boot.drrr.service.lobby.LobbySort;
import com.boot.drrr.web.dto.LobbyResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lobby")
public class LobbyController {
    private final LobbyService lobbyService;

    public LobbyController(LobbyService lobbyService) {
        this.lobbyService = lobbyService;
    }

    @GetMapping
    public ApiResponse<LobbyResponse> getLobby(@RequestParam(required = false) String sort) {
        return ApiResponse.success(LobbyResponse.from(lobbyService.getLobby(LobbySort.from(sort))));
    }
}
