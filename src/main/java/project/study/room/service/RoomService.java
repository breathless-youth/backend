package project.study.room.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import project.study.common.BadRequestException;
import project.study.common.ConflictException;
import project.study.common.NotFoundException;
import project.study.room.dto.RoomCreateResponse;
import project.study.room.dto.RoomJoinResponse;
import project.study.room.dto.RoomMember;
import project.study.room.event.CloseReason;
import project.study.room.event.LeaveReason;
import project.study.room.event.ParticipantJoinedEvent;
import project.study.room.event.ParticipantLeftEvent;
import project.study.room.event.RoomClosedEvent;
import project.study.room.event.RoomCreatedEvent;

/**
 * 인메모리 룸 상태 관리 (단일 인스턴스 전제).
 *
 * <p>동시성 전략: 상태를 바꾸거나 읽는 public 메서드 전부를 인스턴스 락(synchronized)으로 직렬화한다.
 * 방 몇 개 × 최대 6명 규모에서 연산은 마이크로초 단위라 경합이 무의미하고, 세밀한 락으로 생기는
 * 레이스(동시 1룸 위반, 정리-확정 경쟁, 빈 방 소멸-입장 경쟁, 세션 교체 유실)를 원천 차단한다.
 */
@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    static final int MAX_PARTICIPANTS = 6;
    static final int EMPTY_ROOM_TTL_SECONDS = 600;
    private static final long RESERVATION_TTL_SECONDS = 30;
    private static final long GRACE_PERIOD_SECONDS = 30;
    private static final int INVITE_CODE_MAX_ATTEMPTS = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<Long, Room> roomById = new HashMap<>();
    private final Map<String, Room> roomByCode = new HashMap<>();
    private final Map<Long, Long> userToRoomId = new HashMap<>();
    private final Map<String, Long> sessionToUser = new HashMap<>();
    private long roomIdSequence = 0;

    private final String turnSecret;
    private final int turnTtlSeconds;
    private final List<String> turnUrls;
    private final ApplicationEventPublisher eventPublisher;

    public RoomService(
            @Value("${app.room.turn.secret:draft-turn-secret}") String turnSecret,
            @Value("${app.room.turn.ttl-seconds:86400}") int turnTtlSeconds,
            @Value("${app.room.turn.urls:}") List<String> turnUrls,
            ApplicationEventPublisher eventPublisher) {
        this.turnSecret = turnSecret;
        this.turnTtlSeconds = turnTtlSeconds;
        this.turnUrls = turnUrls;
        this.eventPublisher = eventPublisher;
    }

    public record JoinResult(RoomJoinResponse response, AutoLeave autoLeave) {}

    public record AutoLeave(Long roomId, Long userId) {}

    // 생성만으로는 입장 상태가 아니다 — 생성자도 join으로만 입장한다
    public synchronized RoomCreateResponse create(Long userId) {
        for (int attempt = 0; attempt < INVITE_CODE_MAX_ATTEMPTS; attempt++) {
            String code = String.format("%04d", RANDOM.nextInt(10000));
            if (roomByCode.containsKey(code)) {
                continue;
            }
            Room room = new Room(++roomIdSequence, code, Instant.now());
            roomByCode.put(code, room);
            roomById.put(room.id, room);
            // @Async 리스너 전제라 안전. 동기 리스너 추가 금지 (RoomHistoryRecorder 참고)
            publish(new RoomCreatedEvent(room.uid, userId, room.createdAt));
            return new RoomCreateResponse(room.id, code, EMPTY_ROOM_TTL_SECONDS);
        }
        throw new ConflictException("사용 가능한 초대코드가 없습니다");
    }

    // nickname/goal은 호출자(컨트롤러)가 락 밖에서 DB 조회해 넘긴다 — 글로벌 락 안에서 I/O 금지
    public synchronized JoinResult join(Long userId, String inviteCode, String nickname, String goal, String category) {
        if (inviteCode == null || !inviteCode.matches("\\d{4}")) {
            throw new BadRequestException("초대코드는 숫자 4자리여야 합니다");
        }

        Room room = roomByCode.get(inviteCode);
        if (room == null) {
            throw new NotFoundException("코드를 다시 확인해 주세요");
        }

        Participant existing = room.participants.get(userId);
        if (existing != null && existing.disconnectedAt != null) {
            // 끊김 유예 내 재입장 — 같은 자리 복원 (프리뷰 생략, 카메라 상태 유지)
            existing.disconnectedAt = null;
            existing.stompConfirmed = false;
            existing.reservedAt = Instant.now();
            existing.nickname = nickname;
            existing.goal = goal;
            existing.category = category;
            userToRoomId.put(userId, room.id);
            log.debug("재입장(유예 중 복원): roomId={}, userId={}", room.id, userId);
            return new JoinResult(
                    new RoomJoinResponse(room.id, true, existing.cameraOn, generateIceServers(userId), turnTtlSeconds),
                    null);
        }

        // 정원 검사를 기존 방 퇴장보다 먼저 한다 — 대상 방이 가득이면 기존 방 자리를 잃지 않아야 한다
        if (existing == null && room.participants.size() >= MAX_PARTICIPANTS) {
            throw new ConflictException("방이 가득 찼어요");
        }

        AutoLeave autoLeave = leaveCurrentRoomIfDifferent(userId, room.id);

        if (existing != null) {
            // 미확정 예약의 재시도 — 예약 시각을 갱신해 직후 정리 틱에 쓸려나가지 않게 한다
            existing.reservedAt = Instant.now();
            existing.nickname = nickname;
            existing.goal = goal;
            existing.category = category;
        } else {
            room.participants.put(userId, new Participant(userId, nickname, goal, category));
        }
        userToRoomId.put(userId, room.id);
        log.debug("신규 입장 예약: roomId={}, userId={}, autoLeave={}", room.id, userId, autoLeave);
        return new JoinResult(
                new RoomJoinResponse(room.id, false, null, generateIceServers(userId), turnTtlSeconds), autoLeave);
    }

    public synchronized boolean leave(Long roomId, Long userId) {
        boolean left = removeParticipant(roomId, userId, LeaveReason.EXPLICIT);
        log.debug("퇴장 요청: roomId={}, userId={}, 처리됨={}", roomId, userId, left);
        return left;
    }

    public synchronized List<RoomMember> confirmStomp(Long roomId, Long userId, String stompSessionId) {
        Room room = roomById.get(roomId);
        if (room == null) {
            log.debug("STOMP 확정 실패(방 없음): roomId={}, userId={}", roomId, userId);
            return List.of();
        }

        Participant participant = room.participants.get(userId);
        if (participant == null) {
            log.debug("STOMP 확정 실패(참가자 없음): roomId={}, userId={}", roomId, userId);
            return List.of();
        }

        participant.stompConfirmed = true;
        participant.stompSessionId = stompSessionId;
        // 유예 중이던 참가자가 새 세션으로 확정되면 유예를 해제한다 — 남겨두면 cleanupExpired가
        // 살아 있는 참가자를 유예 만료로 제거한다
        participant.disconnectedAt = null;
        sessionToUser.put(stompSessionId, userId);

        if (participant.firstConfirmedAt == null) {
            participant.firstConfirmedAt = Instant.now();
            publish(new ParticipantJoinedEvent(room.uid, userId, participant.firstConfirmedAt));
        }

        log.debug("STOMP 확정: roomId={}, userId={}, stompSessionId={}", roomId, userId, stompSessionId);
        return confirmedMembers(room);
    }

    public synchronized void handleDisconnect(String stompSessionId) {
        Long userId = sessionToUser.remove(stompSessionId);
        if (userId == null) {
            log.debug("연결 해제(알 수 없는 세션): stompSessionId={}", stompSessionId);
            return;
        }

        Participant participant = findParticipantOfUser(userId);
        // 재접속이 옛 세션의 끊김 이벤트보다 먼저 도착할 수 있다 — 지금 세션의 끊김일 때만 유예를 시작한다
        if (participant != null && stompSessionId.equals(participant.stompSessionId)) {
            participant.disconnectedAt = Instant.now();
            participant.stompSessionId = null;
            log.debug("연결 해제(유예 시작): userId={}, stompSessionId={}", userId, stompSessionId);
        } else {
            log.debug("연결 해제(이미 교체된 옛 세션이라 무시): userId={}, stompSessionId={}", userId, stompSessionId);
        }
    }

    public synchronized boolean updateCamera(Long roomId, Long userId, boolean cameraOn) {
        Participant p = getParticipant(roomId, userId);
        if (p == null || !p.stompConfirmed) {
            log.debug("카메라 상태 변경 거부(미확정 참가자): roomId={}, userId={}", roomId, userId);
            return false;
        }
        p.cameraOn = cameraOn;
        log.debug("카메라 상태 변경: roomId={}, userId={}, cameraOn={}", roomId, userId, cameraOn);
        return true;
    }

    public synchronized boolean updateFocusState(Long roomId, Long userId, String focusState) {
        Participant p = getParticipant(roomId, userId);
        if (p == null || !p.stompConfirmed) {
            log.debug("집중 상태 변경 거부(미확정 참가자): roomId={}, userId={}", roomId, userId);
            return false;
        }
        p.focusState = focusState;
        log.debug("집중 상태 변경: roomId={}, userId={}, focusState={}", roomId, userId, focusState);
        return true;
    }

    // 마지막 순공시간을 보관해 SNAPSHOT에 싣는다 (새 입장자가 다음 틱까지 빈 값 안 봄)
    public synchronized boolean updateStudyTime(Long roomId, Long userId, int studySeconds) {
        Participant p = getParticipant(roomId, userId);
        if (p == null || !p.stompConfirmed) {
            log.debug("순공시간 변경 거부(미확정 참가자): roomId={}, userId={}", roomId, userId);
            return false;
        }
        p.studySeconds = studySeconds;
        log.debug("순공시간 변경: roomId={}, userId={}, studySeconds={}", roomId, userId, studySeconds);
        return true;
    }

    public synchronized List<RoomMember> getMembers(Long roomId) {
        Room room = roomById.get(roomId);
        if (room == null) return List.of();
        return confirmedMembers(room);
    }

    // 스냅샷 재요청 인가+조회(BY-442) — 한 번의 원자 호출로 처리한다. 인가(isActiveSession)와
    // 조회(getMembers)를 따로 부르면 그 사이 퇴장이 끼어들어 비멤버에게 스냅샷이 나갈 수 있다.
    // 요청자의 "현재" 세션만 인정해 옛 세션·사칭 세션의 재요청을 차단하고, 빈 목록 = 발송 금지
    // (활성 확정 멤버라면 목록에 자신이 반드시 포함되므로 빈 목록과 구분 불가한 경우가 없다)
    public synchronized List<RoomMember> getMembersForActiveSession(Long roomId, Long userId, String stompSessionId) {
        if (!isActiveSession(roomId, userId, stompSessionId)) return List.of();
        return confirmedMembers(roomById.get(roomId));
    }

    public synchronized boolean isConfirmedMember(Long roomId, Long userId) {
        Participant p = getParticipant(roomId, userId);
        return p != null && p.stompConfirmed;
    }

    // inbound 메시지 인가 — principal userId만이 아니라 그 유저의 "현재" STOMP 세션에서 온 메시지인지 검사한다.
    // 재접속으로 세션이 교체된 뒤에도 살아 있는 옛 세션이 같은 userId로 발신하는 것을 차단
    public synchronized boolean isActiveSession(Long roomId, Long userId, String stompSessionId) {
        Participant p = getParticipant(roomId, userId);
        return p != null && p.stompConfirmed && stompSessionId != null && stompSessionId.equals(p.stompSessionId);
    }

    // SUBSCRIBE 인가 — 자리 예약자(미확정 포함)만 방 토픽을 구독할 수 있다 (구독 자체가 확정 절차)
    public synchronized boolean hasParticipant(Long roomId, Long userId) {
        return getParticipant(roomId, userId) != null;
    }

    public synchronized Long getRoomIdForUser(Long userId) {
        return userToRoomId.get(userId);
    }

    public synchronized boolean roomExists(Long roomId) {
        return roomById.containsKey(roomId);
    }

    public synchronized List<AutoLeave> cleanupExpired(Instant now) {
        List<AutoLeave> removed = new ArrayList<>();

        for (Room room : List.copyOf(roomById.values())) {
            for (Participant participant : List.copyOf(room.participants.values())) {
                if (isExpired(participant, now)
                        && removeParticipant(room.id, participant.userId, LeaveReason.DISCONNECT_TIMEOUT)) {
                    removed.add(new AutoLeave(room.id, participant.userId));
                }
            }

            // 생성 후 아무도 입장하지 않은 빈 방은 10분 뒤 소멸한다
            // (입장 이력이 있는 방은 마지막 퇴장 때 즉시 소멸하므로 여기 잡히지 않는다)
            if (room.participants.isEmpty()
                    && roomById.containsKey(room.id)
                    && room.createdAt.plusSeconds(EMPTY_ROOM_TTL_SECONDS).isBefore(now)) {
                destroyRoom(room, CloseReason.EMPTY_EXPIRED);
            }
        }

        if (!removed.isEmpty()) {
            log.debug("만료 정리: 자동 퇴장 {}건 — {}", removed.size(), removed);
        }
        return removed;
    }

    // 예약 30초 미확정 또는 끊김 30초 유예 만료
    private static boolean isExpired(Participant participant, Instant now) {
        if (!participant.stompConfirmed
                && participant.reservedAt.plusSeconds(RESERVATION_TTL_SECONDS).isBefore(now)) {
            return true;
        }
        return participant.disconnectedAt != null
                && participant.disconnectedAt.plusSeconds(GRACE_PERIOD_SECONDS).isBefore(now);
    }

    // 동시 1룸 제한 — 다른 방에 있으면 자동 퇴장시키고 그 사실을 반환한다 (호출자가 MEMBER_LEFT 브로드캐스트)
    private AutoLeave leaveCurrentRoomIfDifferent(Long userId, Long targetRoomId) {
        Long currentRoomId = userToRoomId.get(userId);
        if (currentRoomId != null
                && !currentRoomId.equals(targetRoomId)
                && removeParticipant(currentRoomId, userId, LeaveReason.SWITCHED_ROOM)) {
            return new AutoLeave(currentRoomId, userId);
        }
        return null;
    }

    // 참가자 제거 — 마지막 1명이 빠지면(명시적 퇴장·예약 만료·유예 만료 공통) 방과 코드가 소멸한다
    private boolean removeParticipant(Long roomId, Long userId, LeaveReason reason) {
        Room room = roomById.get(roomId);
        if (room == null) return false;

        Participant removed = room.participants.remove(userId);
        if (removed == null) return false;

        userToRoomId.remove(userId, roomId);
        if (removed.stompSessionId != null) {
            sessionToUser.remove(removed.stompSessionId);
        }
        // 확정된 적 없는 예약자는 참여 이력이 없으므로 퇴장 이벤트도 없다
        if (removed.firstConfirmedAt != null) {
            publish(new ParticipantLeftEvent(room.uid, userId, Instant.now(), reason, removed.studySeconds));
        }
        if (room.participants.isEmpty()) {
            destroyRoom(room, CloseReason.LAST_LEFT);
        }
        return true;
    }

    private void destroyRoom(Room room, CloseReason reason) {
        roomById.remove(room.id);
        roomByCode.remove(room.inviteCode);
        publish(new RoomClosedEvent(room.uid, Instant.now(), reason));
    }

    private Participant findParticipantOfUser(Long userId) {
        Long roomId = userToRoomId.get(userId);
        if (roomId == null) return null;
        return getParticipant(roomId, userId);
    }

    private static List<RoomMember> confirmedMembers(Room room) {
        return room.participants.values().stream()
                .filter(p -> p.stompConfirmed)
                .map(p -> new RoomMember(
                        p.userId, p.nickname, p.goal, p.category, p.cameraOn, p.focusState, p.studySeconds))
                .toList();
    }

    private Participant getParticipant(Long roomId, Long userId) {
        Room room = roomById.get(roomId);
        if (room == null) return null;
        return room.participants.get(userId);
    }

    // 발행 실패가 룸 동작으로 전파되면 안 된다 (best-effort, 스펙 불변식)
    private void publish(Object event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException e) {
            log.warn("룸 이력 이벤트 발행 실패: {}", event, e);
        }
    }

    private List<RoomJoinResponse.IceServer> generateIceServers(Long userId) {
        if (turnUrls == null || turnUrls.isEmpty() || turnUrls.getFirst().isBlank()) {
            return List.of();
        }

        long expiry = Instant.now().getEpochSecond() + turnTtlSeconds;
        String username = expiry + ":" + userId;
        String credential = hmacSha1(turnSecret, username);

        return List.of(new RoomJoinResponse.IceServer(turnUrls, username, credential));
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
}
