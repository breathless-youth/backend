package project.study.room.event;

import java.time.Instant;
import java.util.UUID;

public record ParticipantJoinedEvent(UUID roomUid, Long userId, Instant joinedAt) {}
