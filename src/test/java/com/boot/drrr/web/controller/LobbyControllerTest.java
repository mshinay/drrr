package com.boot.drrr.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boot.drrr.common.error.GlobalExceptionHandler;
import com.boot.drrr.service.lobby.LobbyService;
import com.boot.drrr.service.lobby.LobbySort;
import com.boot.drrr.service.lobby.LobbyView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LobbyControllerTest {

    @Test
    void getLobbyReturnsDocumentedEnvelope() throws Exception {
        LobbyService service = new LobbyService(null, null, null, null, null, null) {
            @Override
            public LobbyView getLobby(LobbySort sort) {
                return new LobbyView(
                        12L,
                        List.of(new LobbyView.LobbyRoomSummary(
                                "r_123",
                                "Late Night Radio",
                                "anonymous talk",
                                5L,
                                10,
                                1717300200000L,
                                1717299000000L
                        ))
                );
            }
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new LobbyController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/lobby").queryParam("sort", "MEMBER_COUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.activeUsersLast5Minutes").value(12))
                .andExpect(jsonPath("$.data.rooms[0].roomId").value("r_123"))
                .andExpect(jsonPath("$.data.rooms[0].name").value("Late Night Radio"))
                .andExpect(jsonPath("$.data.rooms[0].currentMembers").value(5))
                .andExpect(jsonPath("$.data.rooms[0].maxMembers").value(10));
    }

    @Test
    void getLobbyFallsBackToLastActiveWhenSortIsUnknown() throws Exception {
        CapturingLobbyService service = new CapturingLobbyService();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new LobbyController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/lobby").queryParam("sort", "UNKNOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(service.receivedSort).isEqualTo(LobbySort.LAST_ACTIVE);
    }

    private static final class CapturingLobbyService extends LobbyService {
        private LobbySort receivedSort;

        private CapturingLobbyService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public LobbyView getLobby(LobbySort sort) {
            this.receivedSort = sort;
            return new LobbyView(0L, List.of());
        }
    }
}
