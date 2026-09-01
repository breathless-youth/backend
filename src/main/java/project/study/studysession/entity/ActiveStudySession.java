package project.study.studysession.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 진행중 세션의 최신 스냅샷(draft) — 세션당 1행, 매 하트비트마다 통째로 덮어쓴다 (BY-447).
 * 확정 시 StudySessionService.create로 넘겨 study_session 행이 되고 이 행은 삭제된다.
 * reportedAt(클라 시계)은 확정 시 endedAt이 되고, lastSeenAt(서버 시계)은 무응답 판정에만 쓴다 —
 * 클라 시계가 느린 유저의 공부 중 세션이 무응답으로 오판되지 않게 두 시계의 역할을 분리한다.
 * 쓰기는 전부 리포지토리의 네이티브 UPSERT로만 한다 — 이 엔티티는 읽기 전용이라 공개 생성자·수정 메서드가 없다.
 */
@Entity
@Table(name = "active_study_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActiveStudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "study_sec", nullable = false)
    private Integer studySec;

    @Column(name = "focus_sec", nullable = false)
    private Integer focusSec;

    // 이벤트 전체 스냅샷(StatusEventRequest 배열의 JSON) — status_event 자식 행은 확정 시점에만 만든다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "events", nullable = false)
    private String events;
}
