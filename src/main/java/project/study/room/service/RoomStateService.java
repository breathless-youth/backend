package project.study.room.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.room.dto.RoomMember;
import project.study.room.dto.StateUpdatePayload;
import project.study.room.repository.GroupSeatRepository;
import project.study.room.repository.RoomParticipationRepository;

/**
 * 핫패스와 인가 조회 (스펙 §2.7~§2.9). 방 락 없이 단일 행 조건부 문장만 쓴다.
 * sessionId가 null이면 SQL을 보내지 않고 거절한다 — NULL 비교는 항상 거짓이라 결과는 같지만 왕복을 아낀다.
 */
@Service
@RequiredArgsConstructor
public class RoomStateService {

    /** 그룹 공부방 필드가 있는 state 보고의 결과 — 저장했는지와, 저장한 방이 그룹 공부방인지(그래야 micOn·onBreak를 알린다). */
    public record StateUpdate(boolean saved, boolean groupRoom) {
        public static final StateUpdate NONE = new StateUpdate(false, false);
    }

    private final RoomParticipationRepository participations;
    private final GroupSeatRepository groupSeats;

    /** 갱신 1행 = 인가(현재 세션의 확정 멤버) + 저장. 필드별 검증은 핸들러가 SQL 전에 한다. */
    @Transactional
    public boolean updateState(
            Long roomId, Long userId, String sessionId, Boolean cameraOn, String focusState, Integer focusSec) {
        if (sessionId == null || (cameraOn == null && focusState == null && focusSec == null)) {
            return false;
        }
        return participations.updateState(roomId, userId, sessionId, cameraOn, focusState, focusSec) == 1;
    }

    /**
     * micOn·onBreak가 있는 보고 (BY-1124). 저장과 방 종류 판정이 한 문장이다 — 그룹 공부방이면 다섯 필드를, 초대코드 방이면 기존 세 필드만
     * 반영한다. 그룹 공부방 필드가 없는 보고는 {@link #updateState(Long, Long, String, Boolean, String, Integer)}를 쓴다.
     */
    @Transactional
    public StateUpdate updateState(Long roomId, Long userId, String sessionId, StateUpdatePayload change) {
        if (sessionId == null) {
            return StateUpdate.NONE;
        }
        return groupSeats
                .updateState(roomId, userId, sessionId, change)
                .map(groupRoom -> new StateUpdate(true, groupRoom))
                .orElse(StateUpdate.NONE);
    }

    /**
     * 발신자가 현재 세션의 확정 멤버이고 수신자가 확정 멤버인지, 그룹 공부방이면 두 사람이 차단한 사이가 아닌지까지 한 번의 조회로 판정한다 (BY-1124
     * websocket.html §11.5). 차단한 사이의 SIGNAL은 인가 실패와 똑같이 조용히 버려진다.
     */
    @Transactional(readOnly = true)
    public boolean authorizeSignal(Long roomId, Long fromUserId, String sessionId, Long toUserId) {
        // 호출자(핸들러)가 이미 거르지만 여기서도 막는다 — null 비교는 항상 거짓이라 결과는 같지만 왕복을 아낀다
        if (sessionId == null || fromUserId == null || toUserId == null) {
            return false;
        }
        return groupSeats.signalAllowed(roomId, fromUserId, sessionId, toUserId);
    }

    /** 스냅샷 재요청 — 인가와 조회가 한 번의 원자 호출. 빈 목록 = 발송 금지. */
    @Transactional(readOnly = true)
    public List<RoomMember> getMembersForActiveSession(Long roomId, Long userId, String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        return participations.findConfirmedMembersForActiveSession(roomId, userId, sessionId);
    }

    @Transactional(readOnly = true)
    public List<RoomMember> getMembers(Long roomId) {
        return participations.findConfirmedMembers(roomId);
    }

    /** SUBSCRIBE 인가 — 자리 예약자(미확정 포함)만 방 토픽을 구독할 수 있다. */
    @Transactional(readOnly = true)
    public boolean hasParticipant(Long roomId, Long userId) {
        return participations.existsLive(roomId, userId);
    }

    @Transactional(readOnly = true)
    public boolean isConfirmedMember(Long roomId, Long userId) {
        return participations.isConfirmed(roomId, userId);
    }

    @Transactional(readOnly = true)
    public boolean isActiveSession(Long roomId, Long userId, String sessionId) {
        return sessionId != null && participations.isActiveSession(roomId, userId, sessionId);
    }

    /** 유저의 현재 방. 없으면 null. */
    @Transactional(readOnly = true)
    public Long getRoomIdForUser(Long userId) {
        return participations.findLiveRoomIdOfUser(userId).orElse(null);
    }
}
