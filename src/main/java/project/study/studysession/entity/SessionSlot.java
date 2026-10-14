package project.study.studysession.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 세션 조각의 시간대 구간별 순공 (BY-828) — study_session_slot 한 행. 조각 순공을 구간 경계로 배분한 서버 파생값이라
 * 세션과 함께 저장·대체·삭제된다(완료 할 일처럼 값 컬렉션).
 */
@Embeddable
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionSlot {

    @Enumerated(EnumType.STRING)
    @Column(name = "slot", nullable = false)
    private TimeSlot slot;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "focus_sec", nullable = false)
    private int focusSec;

    public SessionSlot(TimeSlot slot, LocalDate slotDate, int focusSec) {
        this.slot = slot;
        this.slotDate = slotDate;
        this.focusSec = focusSec;
    }
}
