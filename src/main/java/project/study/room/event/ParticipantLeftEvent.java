package project.study.room.event;

import java.time.Instant;
import java.util.UUID;

public record ParticipantLeftEvent(UUID roomUid, Long userId, Instant leftAt, LeaveReason reason) {}
