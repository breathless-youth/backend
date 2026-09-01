package project.study.room.dto;

public record SignalPayload(Long toUserId, String kind, Object payload) {}
