package project.study.mission.service;

import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.mission.dto.HomeMissionResponse;
import project.study.subject.repository.StudySubjectLockRepository;

@Service
public class HomeMissionService {
    private static final String VERSION = "home-v3";
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

    @Transactional(readOnly = true)
    public HomeMissionResponse get(long userId) {
        int completed = jdbc.sql(
                        "SELECT completed_steps FROM home_mission_progress WHERE user_id = :u AND mission_version = :v")
                .param("u", userId)
                .param("v", VERSION)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return response(userId, completed);
    }

    @Transactional
    public HomeMissionResponse complete(long userId, int step) {
        if (step < 1 || step > 2) throw new BadRequestException("미션 단계는 1 또는 2여야 합니다");
        locks.lockUser(userId);
        jdbc.sql("SELECT id FROM users WHERE id = :u FOR UPDATE")
                .param("u", userId)
                .query(Long.class)
                .single();
        int completed = jdbc.sql(
                        "SELECT completed_steps FROM home_mission_progress WHERE user_id = :u AND mission_version = :v")
                .param("u", userId)
                .param("v", VERSION)
                .query(Integer.class)
                .optional()
                .orElse(0);
        if (step <= completed) return response(userId, completed);
        if (step != completed + 1 || !response(userId, completed).eligible())
            throw new BadRequestException("현재 단계의 완료 조건을 충족하지 않았습니다");
        jdbc.sql("""
                INSERT INTO home_mission_progress (user_id, mission_version, completed_steps) VALUES (:u, :v, :s)
                ON CONFLICT (user_id, mission_version) DO UPDATE SET completed_steps = EXCLUDED.completed_steps
                """).param("u", userId).param("v", VERSION).param("s", step).update();
        return response(userId, step);
    }

    private HomeMissionResponse response(long userId, int completed) {
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
        boolean hasSubject = jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM study_subject WHERE user_id = :u AND deleted_at IS NULL)")
                .param("u", userId)
                .query(Boolean.class)
                .single();
        Integer current = completed == 2 ? null : completed + 1;
        boolean eligible = current != null && (current == 1 ? hasSubject : seconds >= 300);
        return new HomeMissionResponse(VERSION, startsOn, completed, current, eligible, seconds);
    }
}
