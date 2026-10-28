package project.study.metrics.report.fact;

import java.time.Duration;
import java.util.List;

/**
 * 첫 세션 5분 푸시 실험의 설정과 배정자 — push 도메인이 채운다 (BY-1070). 리포트가 대상 문구에 실제 설정값을 쓰도록 함께 넘긴다.
 *
 * @param d0Delay 가입 뒤 D0 알림까지의 지연(`app.push.first-session-experiment.d0-delay`)
 */
public record ExperimentData(Duration d0Delay, List<ExperimentFact> facts) {}
