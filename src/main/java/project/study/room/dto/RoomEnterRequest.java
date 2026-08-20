package project.study.room.dto;

import jakarta.validation.constraints.NotNull;

public record RoomEnterRequest(@NotNull Long userId) {}
