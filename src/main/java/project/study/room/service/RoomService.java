package project.study.room.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import project.study.common.ConflictException;
import project.study.room.dto.RoomEnterResponse;
import project.study.room.dto.RoomMember;

@Service
public class RoomService {

    static final int MAX_PARTICIPANTS = 8;
    private static final long RESERVATION_TTL_SECONDS = 30;
    private static final long GRACE_PERIOD_SECONDS = 30;

    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, Participant>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> userToRoom = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sessionToUser = new ConcurrentHashMap<>();

    private final String turnSecret;
    private final int turnTtlSeconds;
    private final List<String> turnUrls;

    public RoomService(
            @Value("${app.room.turn.secret:draft-turn-secret}") String turnSecret,
            @Value("${app.room.turn.ttl-seconds:86400}") int turnTtlSeconds,
            @Value("${app.room.turn.urls:}") List<String> turnUrls) {
        this.turnSecret = turnSecret;
        this.turnTtlSeconds = turnTtlSeconds;
        this.turnUrls = turnUrls;
    }

    public record EnterResult(RoomEnterResponse response, AutoLeave autoLeave) {}

    public record AutoLeave(Long roomId, Long userId) {}

    public EnterResult enter(Long roomId, Long userId) {
        AutoLeave autoLeave = null;

        Long currentRoom = userToRoom.get(userId);
        if (currentRoom != null && !currentRoom.equals(roomId)) {
            removeParticipant(currentRoom, userId);
            autoLeave = new AutoLeave(currentRoom, userId);
        }

        ConcurrentHashMap<Long, Participant> room = rooms.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());

        Participant existing = room.get(userId);
        if (existing != null && existing.disconnectedAt != null) {
            existing.disconnectedAt = null;
            existing.stompConfirmed = false;
            existing.reservedAt = Instant.now();
            userToRoom.put(userId, roomId);
            List<RoomEnterResponse.IceServer> iceServers = generateIceServers(userId);
            return new EnterResult(
                    new RoomEnterResponse(true, existing.cameraOn, iceServers, turnTtlSeconds), autoLeave);
        }

        if (room.size() >= MAX_PARTICIPANTS) {
            throw new ConflictException("ROOM_FULL");
        }

        Participant participant = new Participant(userId);
        room.put(userId, participant);
        userToRoom.put(userId, roomId);

        List<RoomEnterResponse.IceServer> iceServers = generateIceServers(userId);
        return new EnterResult(new RoomEnterResponse(false, null, iceServers, turnTtlSeconds), autoLeave);
    }

    public boolean leave(Long roomId, Long userId) {
        return removeParticipant(roomId, userId);
    }

    public List<RoomMember> confirmStomp(Long roomId, Long userId, String stompSessionId) {
        ConcurrentHashMap<Long, Participant> room = rooms.get(roomId);
        if (room == null) return List.of();

        Participant participant = room.get(userId);
        if (participant == null) return List.of();

        participant.stompConfirmed = true;
        participant.stompSessionId = stompSessionId;
        sessionToUser.put(stompSessionId, userId);

        return room.values().stream()
                .filter(p -> p.stompConfirmed)
                .map(p -> new RoomMember(p.userId, p.cameraOn, p.focusState))
                .toList();
    }

    public void handleDisconnect(String stompSessionId) {
        Long userId = sessionToUser.remove(stompSessionId);
        if (userId == null) return;

        Long roomId = userToRoom.get(userId);
        if (roomId == null) return;

        ConcurrentHashMap<Long, Participant> room = rooms.get(roomId);
        if (room == null) return;

        Participant participant = room.get(userId);
        if (participant != null) {
            participant.disconnectedAt = Instant.now();
            participant.stompSessionId = null;
        }
    }

    public void updateCamera(Long roomId, Long userId, boolean cameraOn) {
        Participant p = getParticipant(roomId, userId);
        if (p != null) p.cameraOn = cameraOn;
    }

    public void updateFocusState(Long roomId, Long userId, String focusState) {
        Participant p = getParticipant(roomId, userId);
        if (p != null) p.focusState = focusState;
    }

    public List<RoomMember> getMembers(Long roomId) {
        ConcurrentHashMap<Long, Participant> room = rooms.get(roomId);
        if (room == null) return List.of();

        return room.values().stream()
                .filter(p -> p.stompConfirmed)
                .map(p -> new RoomMember(p.userId, p.cameraOn, p.focusState))
                .toList();
    }

    public Long getRoomIdForUser(Long userId) {
        return userToRoom.get(userId);
    }

    public List<AutoLeave> cleanupExpired(Instant now) {
        List<AutoLeave> removed = new ArrayList<>();

        rooms.forEach((roomId, room) -> {
            room.forEach((userId, participant) -> {
                boolean expired = false;

                if (!participant.stompConfirmed
                        && participant
                                .reservedAt
                                .plusSeconds(RESERVATION_TTL_SECONDS)
                                .isBefore(now)) {
                    expired = true;
                }

                if (participant.disconnectedAt != null
                        && participant
                                .disconnectedAt
                                .plusSeconds(GRACE_PERIOD_SECONDS)
                                .isBefore(now)) {
                    expired = true;
                }

                if (expired) {
                    room.remove(userId);
                    userToRoom.remove(userId);
                    removed.add(new AutoLeave(roomId, userId));
                }
            });
        });

        return removed;
    }

    private boolean removeParticipant(Long roomId, Long userId) {
        ConcurrentHashMap<Long, Participant> room = rooms.get(roomId);
        if (room == null) return false;

        Participant removed = room.remove(userId);
        if (removed != null) {
            userToRoom.remove(userId);
            if (removed.stompSessionId != null) {
                sessionToUser.remove(removed.stompSessionId);
            }
            return true;
        }
        return false;
    }

    private Participant getParticipant(Long roomId, Long userId) {
        ConcurrentHashMap<Long, Participant> room = rooms.get(roomId);
        if (room == null) return null;
        return room.get(userId);
    }

    private List<RoomEnterResponse.IceServer> generateIceServers(Long userId) {
        if (turnUrls == null || turnUrls.isEmpty() || turnUrls.getFirst().isBlank()) {
            return List.of();
        }

        long expiry = Instant.now().getEpochSecond() + turnTtlSeconds;
        String username = expiry + ":" + userId;
        String credential = hmacSha1(turnSecret, username);

        return List.of(new RoomEnterResponse.IceServer(turnUrls, username, credential));
    }

    private static String hmacSha1(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 계산 실패", e);
        }
    }

    static class Participant {
        final Long userId;
        boolean cameraOn;
        String focusState;
        Instant reservedAt;
        boolean stompConfirmed;
        Instant disconnectedAt;
        String stompSessionId;

        Participant(Long userId) {
            this.userId = userId;
            this.cameraOn = false;
            this.focusState = "FOCUS";
            this.reservedAt = Instant.now();
            this.stompConfirmed = false;
        }
    }
}
