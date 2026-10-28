package project.study.metrics.report.analyzer;

import java.time.Duration;
import project.study.metrics.report.Rate;

/**
 * 🔔 첫 세션 5분 푸시 실험 (BY-1070). 집단은 알림(push)과 미발송(control)이다.
 *
 * @param d0Delay 가입 뒤 D0 알림까지의 지연(설정값)
 * @param targetPerArm 판정에 필요한 집단당 인원(Bounded D7 분모)
 */
public record ExperimentSection(Duration d0Delay, ArmStats push, ArmStats control, int targetPerArm) {

    /**
     * @param started 기준일까지 첫 측정 세션을 시작한 비율
     * @param firstFiveMinutes 기준일까지 첫 측정 세션 순공 5분 이상인 비율 (주 지표)
     * @param daySeven 가입 7일 뒤 그날 세션을 시작한 비율 — 그날이 집계된 사람만 분모 (판정)
     * @param notificationsOff 토큰이 남은 사람 중 알림 권한이 켜진 토큰이 없는 비율 (부작용)
     * @param tokenGone 토큰이 사라진 사람 수 — 발송이 UNREGISTERED로 지운 경우라 알림 집단에서만 드러난다
     */
    public record ArmStats(
            int assigned, Rate started, Rate firstFiveMinutes, Rate daySeven, Rate notificationsOff, int tokenGone) {}

    public boolean empty() {
        return push.assigned() + control.assigned() == 0;
    }

    /** 판정 인원이 적은 쪽 기준 진행 인원. */
    public int judgedPerArm() {
        return Math.min(push.daySeven().denominator(), control.daySeven().denominator());
    }
}
