package project.study.room.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.room.entity.CloseReason;
import project.study.room.entity.LeaveReason;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.ExpiryWindow;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;

/**
 * 자리 하나를 비운다 (스펙 §2.3). 명시 퇴장·방 전환·만료 공통. 호출자가 방 행 락을 쥔 상태여야 한다.
 * 확정된 적 없는 예약은 이력 없이 삭제, 확정된 참가자는 left_at·사유를 남긴다. 마지막 자리면 방을 닫는다.
 * 최종 문장이 라이브(+만료) 조건을 품고 있어 같은 행에 두 번 불려도 두 번째는 NONE이다.
 */
@Component
@RequiredArgsConstructor
public class ParticipantRemover {

    public record Removed(boolean removed, boolean roomClosed) {
        public static final Removed NONE = new Removed(false, false);
    }

    private final RoomRepository rooms;
    private final RoomParticipationRepository participations;

    public Removed remove(Row row, RoomRow room, LeaveReason reason, Instant now, ExpiryWindow window) {
        int changed = row.joinedAt() == null
                ? participations.deleteUnconfirmed(row.id(), window)
                : participations.markLeft(row.id(), reason, now, window);
        if (changed == 0) {
            return Removed.NONE;
        }
        boolean closed = rooms.closeIfEmpty(room.id(), room.inviteCode(), CloseReason.LAST_LEFT, now);
        return new Removed(true, closed);
    }
}
