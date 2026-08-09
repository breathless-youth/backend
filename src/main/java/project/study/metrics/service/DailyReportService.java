package project.study.metrics.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.metrics.dto.DailyReport;
import project.study.metrics.repository.DailyReportLogRepository;
import project.study.metrics.slack.SlackNotifier;
import project.study.studysession.service.StudySessionMetricsService;
import project.study.user.service.UserService;

/**
 * 일일 지표 리포트를 조립해 Slack으로 발송한다.
 *
 * <p>지표는 각자 소유 도메인의 서비스에서 가져온다 — 이 클래스가 users·study_session 테이블을
 * 직접 조회하지 않는다. 특히 헤비유저 판정 기준(ADR-0009)은 StudySessionMetricsService 안에만 두어
 * 앱 스트릭과 같은 잣대를 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DailyReportLogRepository dailyReportLogRepository;
    private final UserService userService;
    private final StudySessionMetricsService studySessionMetricsService;
    private final SlackNotifier slackNotifier;
    private final Clock clock;

    public void sendDailyReport() {
        if (!slackNotifier.isEnabled()) {
            log.warn("Slack 발송기가 비활성이라 일일 리포트를 건너뛴다 — 날짜를 선점하지 않으므로 설정 후 재시도할 수 있다");
            return;
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
        if (dailyReportLogRepository.claim(today) == 0) {
            log.info("일일 리포트가 이미 발송됐다 (발송일={}) — 배포 중 태스크 중복으로 보고 건너뛴다", today);
            return;
        }

        // 발송 시각이 오전 10시라 오늘은 부분 집계다. 완결된 하루인 어제를 기준일로 쓴다
        LocalDate anchorDate = today.minusDays(1);
        DailyReport report = new DailyReport(
                anchorDate,
                userService.countTotal(),
                userService.countRegisteredOn(anchorDate),
                studySessionMetricsService.findHeavyUsers(anchorDate),
                studySessionMetricsService.countQualifyingSessionsOn(anchorDate));

        slackNotifier.send(report.toSlackMessage());
        log.info("일일 지표 리포트를 발송했다 (기준일={})", anchorDate);
    }
}
