package project.study.mission.service;

import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.mission.dto.HomeMissionResponse;
import project.study.subject.repository.StudySubjectLockRepository;

@Service
public class HomeMissionService {
    static final String VERSION = "home-v3";
    // 예전 서버도 기존 진행 행을 안전하게 읽을 수 있게 3단계는 다른 행에 보존한다.
    private static final String ACCESSORY_PROGRESS_VERSION = "home-v3-accessory";
    private static final int LEGACY_STEP_COUNT = 2;
    private static final int ACCESSORY_STEP_COUNT = 3;
    private final JdbcClient jdbc;
    private final StudySubjectLockRepository locks;

    private final LocalDate startsOn;

    public HomeMissionService(
            JdbcClient jdbc,
            StudySubjectLockRepository locks,
            @Value("${app.home-mission.starts-on:2026-11-07}") LocalDate startsOn) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.startsOn = startsOn;
    }

    static int stepCount(boolean includeAccessory) {
        return includeAccessory ? ACCESSORY_STEP_COUNT : LEGACY_STEP_COUNT;
    }

    @Transactional(readOnly = true)
    public HomeMissionResponse get(long userId, boolean includeAccessory) {
        return response(userId, completedSteps(userId, includeAccessory), stepCount(includeAccessory));
    }

    /** 완료 확인한 단계 수. 옛 앱은 기존 두 단계만 센다. */
    @Transactional(readOnly = true)
    public int completedSteps(long userId, boolean includeAccessory) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(completed_steps), 0)::int
                FROM home_mission_progress WHERE user_id = :u AND mission_version IN (:legacy, :accessory)
                """)
                .param("u", userId)
                .param("legacy", VERSION)
                .param("accessory", includeAccessory ? ACCESSORY_PROGRESS_VERSION : VERSION)
                .query(Integer.class)
                .single();
    }

    @Transactional
    public HomeMissionResponse complete(long userId, int step, boolean includeAccessory) {
        int stepCount = stepCount(includeAccessory);
        if (step < 1 || step > stepCount) throw new BadRequestException("미션 단계는 1부터 " + stepCount + "까지입니다");
        locks.lockUser(userId);
        jdbc.sql("SELECT id FROM users WHERE id = :u FOR UPDATE")
                .param("u", userId)
                .query(Long.class)
                .single();
        int completed = completedSteps(userId, includeAccessory);
        if (step <= completed) return response(userId, completed, stepCount);
        if (step != completed + 1 || !response(userId, completed, stepCount).eligible())
            throw new BadRequestException("현재 단계의 완료 조건을 충족하지 않았습니다");
        boolean accessory = step == ACCESSORY_STEP_COUNT;
        jdbc.sql("""
                INSERT INTO home_mission_progress (user_id, mission_version, completed_steps) VALUES (:u, :v, :s)
                ON CONFLICT (user_id, mission_version) DO UPDATE SET completed_steps = EXCLUDED.completed_steps
                """)
                .param("u", userId)
                .param("v", accessory ? ACCESSORY_PROGRESS_VERSION : VERSION)
                .param("s", accessory ? 1 : step)
                .update();
        return response(userId, step, stepCount);
    }

    private HomeMissionResponse response(long userId, int completed, int stepCount) {
        long seconds = jdbc.sql("""
                SELECT COALESCE(MAX(daily_seconds), 0) FROM (
                    SELECT SUM(focus_sec) AS daily_seconds FROM study_session
                    WHERE user_id = :u AND source = 'MEASURED' AND stat_date >= :start
                    GROUP BY stat_date
                ) days
                """)
                .param("u", userId)
                .param("start", startsOn)
                .query(Long.class)
                .single();
        Integer current = completed == stepCount ? null : completed + 1;
        boolean eligible = current != null
                && switch (current) {
                    case 1 ->
                        jdbc.sql(
                                        "SELECT EXISTS (SELECT 1 FROM study_subject WHERE user_id = :u AND deleted_at IS NULL)")
                                .param("u", userId)
                                .query(Boolean.class)
                                .single();
                    case 2 -> seconds >= 300;
                    case 3 -> purchasedAccessory(userId);
                    default -> false;
                };
        return new HomeMissionResponse(VERSION, startsOn, completed, current, eligible, seconds, 0, 0, List.of());
    }

    private boolean purchasedAccessory(long userId) {
        // 보유만 한 소품과 타이머 배경을 제외하며, 판매 중지 전 포인트로 산 소품도 인정한다.
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM point_ledger ledger
                    JOIN shop_item item ON ledger.ref_key = CONCAT('item:', item.id)
                    WHERE ledger.user_id = :u AND ledger.reason = 'PURCHASE' AND ledger.amount < 0
                        AND item.category = 'MASCOT_ACCESSORY'
                )
                """).param("u", userId).query(Boolean.class).single();
    }
}
