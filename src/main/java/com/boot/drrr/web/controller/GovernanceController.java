package com.boot.drrr.web.controller;

import com.boot.drrr.common.api.ApiResponse;
import com.boot.drrr.service.governance.GovernanceService;
import com.boot.drrr.web.dto.governance.BanMemberRequest;
import com.boot.drrr.web.dto.governance.BanMemberResponse;
import com.boot.drrr.web.dto.governance.KickMemberRequest;
import com.boot.drrr.web.dto.governance.KickMemberResponse;
import com.boot.drrr.web.dto.governance.MuteMemberRequest;
import com.boot.drrr.web.dto.governance.MuteMemberResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms/{roomId}/members/{targetUserId}")
public class GovernanceController {
    private final GovernanceService governanceService;

    public GovernanceController(GovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @PostMapping("/mute")
    public ApiResponse<MuteMemberResponse> muteMember(
            @PathVariable String roomId,
            @PathVariable String targetUserId,
            @Valid @RequestBody MuteMemberRequest request
    ) {
        return ApiResponse.success(MuteMemberResponse.from(
                governanceService.muteMember(roomId, targetUserId, request.toCommand())
        ));
    }

    @PostMapping("/kick")
    public ApiResponse<KickMemberResponse> kickMember(
            @PathVariable String roomId,
            @PathVariable String targetUserId,
            @Valid @RequestBody KickMemberRequest request
    ) {
        return ApiResponse.success(KickMemberResponse.from(
                governanceService.kickMember(roomId, targetUserId, request.toCommand())
        ));
    }

    @PostMapping("/ban")
    public ApiResponse<BanMemberResponse> banMember(
            @PathVariable String roomId,
            @PathVariable String targetUserId,
            @Valid @RequestBody BanMemberRequest request
    ) {
        return ApiResponse.success(BanMemberResponse.from(
                governanceService.banMember(roomId, targetUserId, request.toCommand())
        ));
    }
}
