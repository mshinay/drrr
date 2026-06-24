package com.boot.drrr.web.controller;

import com.boot.drrr.common.api.ApiResponse;
import com.boot.drrr.service.room.RoomService;
import com.boot.drrr.web.dto.room.CreateRoomRequest;
import com.boot.drrr.web.dto.room.CreateRoomResponse;
import com.boot.drrr.web.dto.room.JoinRoomRequest;
import com.boot.drrr.web.dto.room.JoinRoomResponse;
import com.boot.drrr.web.dto.room.LeaveRoomRequest;
import com.boot.drrr.web.dto.room.LeaveRoomResponse;
import com.boot.drrr.web.dto.room.UpdateRoomRequest;
import com.boot.drrr.web.dto.room.UpdateRoomResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {
    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping
    public ApiResponse<CreateRoomResponse> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        return ApiResponse.success(CreateRoomResponse.from(roomService.createRoom(request.toCommand())));
    }

    @PostMapping("/{roomId}/join")
    public ApiResponse<JoinRoomResponse> joinRoom(
            @PathVariable String roomId,
            @Valid @RequestBody JoinRoomRequest request
    ) {
        return ApiResponse.success(JoinRoomResponse.from(roomService.joinRoom(roomId, request.toCommand())));
    }

    @PostMapping("/{roomId}/leave")
    public ApiResponse<LeaveRoomResponse> leaveRoom(
            @PathVariable String roomId,
            @Valid @RequestBody LeaveRoomRequest request
    ) {
        return ApiResponse.success(LeaveRoomResponse.from(roomService.leaveRoom(roomId, request.toCommand())));
    }

    @PatchMapping("/{roomId}")
    public ApiResponse<UpdateRoomResponse> updateRoom(
            @PathVariable String roomId,
            @Valid @RequestBody UpdateRoomRequest request
    ) {
        return ApiResponse.success(UpdateRoomResponse.from(roomService.updateRoom(roomId, request.toCommand())));
    }
}
