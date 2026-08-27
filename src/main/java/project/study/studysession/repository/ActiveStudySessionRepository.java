package project.study.studysession.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import project.study.studysession.entity.ActiveStudySession;

public interface ActiveStudySessionRepository extends JpaRepository<ActiveStudySession, Long> {

    /**
     * 스냅샷 UPSERT — 한 문장으로 동시 첫 스냅샷 레이스(ON CONFLICT)와 역순 도착 가드(WHERE)를
     * 원자적으로 해결한다. 레이스 패자·과거 reportedAt 스냅샷은 조용히 0행 갱신(반환 0)으로 끝난다.
     * 조회-후-갱신 방식은 유니크 충돌 flush 실패가 트랜잭션을 오염시켜(rollback-only) 못 쓴다.
     */
    @Transactional
    @Modifying
    @Query(value = """
                    INSERT INTO active_study_session
                        (user_id, started_at, reported_at, last_seen_at, study_sec, focus_sec, events)
                    VALUES (:userId, :startedAt, :reportedAt, :lastSeenAt, :studySec, :focusSec, cast(:events as jsonb))
                    ON CONFLICT (user_id, started_at) DO UPDATE
                    SET reported_at = excluded.reported_at,
                        last_seen_at = excluded.last_seen_at,
                        study_sec = excluded.study_sec,
                        focus_sec = excluded.focus_sec,
                        events = excluded.events
                    WHERE active_study_session.reported_at < excluded.reported_at""", nativeQuery = true)
    int upsertSnapshot(
            @Param("userId") Long userId,
            @Param("startedAt") Instant startedAt,
            @Param("reportedAt") Instant reportedAt,
            @Param("lastSeenAt") Instant lastSeenAt,
            @Param("studySec") int studySec,
            @Param("focusSec") int focusSec,
            @Param("events") String events);

    // 확정 대상 draft 조회 — (userId, startedAt)이 draft의 멱등 키다
    Optional<ActiveStudySession> findByUserIdAndStartedAt(Long userId, Instant startedAt);

    // 재접속 복구 조회(BY-448) — 옛 draft(확정 대기)와 새 draft가 공존할 수 있어 마지막 보고가 최신인 것을 준다
    Optional<ActiveStudySession> findFirstByUserIdOrderByLastSeenAtDesc(Long userId);

    // 확정 스케줄러용 — 서버 시계 기준 무응답 draft. 테이블 크기가 동시 공부 세션 수라 인덱스 불필요
    List<ActiveStudySession> findByLastSeenAtBefore(Instant cutoff);

    // 최종 제출 성공 시 같은 트랜잭션에서 draft 정리 — 없으면 no-op
    void deleteByUserIdAndStartedAt(Long userId, Instant startedAt);
}
