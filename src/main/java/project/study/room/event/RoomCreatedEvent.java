package project.study.room.event;

import java.time.Instant;
import java.util.UUID;

public record RoomCreatedEvent(UUID roomUid, Long createdBy, Instant createdAt) {}
