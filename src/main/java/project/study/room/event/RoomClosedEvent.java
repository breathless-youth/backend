package project.study.room.event;

import java.time.Instant;
import java.util.UUID;

public record RoomClosedEvent(UUID roomUid, Instant closedAt, CloseReason reason) {}
