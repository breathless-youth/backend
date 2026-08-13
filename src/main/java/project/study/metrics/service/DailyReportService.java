package project.study.metrics.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.metrics.dto.DailyReport;
import project.study.metrics.slack.SlackNotifier;
import project.study.studysession.service.StudySessionMetricsService;
import project.study.user.service.UserService;

/**
 * 일일 지표 리포트를 조립해 Slack으로 발송한다.
 *
 * <p>지표는 각자 소유 도메인의 서비스에서 가져온다 — 이 클래스가 users·study_session 테이블을
 * 직접 조회하지 않는다. 특히 헤비유저 판정 기준(ADR-0009)은 StudySessionMetricsService 안에만 두어
 * 앱 스트릭과 같은 잣대를 유지한다.
 *
 * <p>하루 1회 발송을 DB로 보장하지 않는다. 예전에는 날짜를 선점하는 이력 테이블을 두었지만,
 * 그 가드가 막으려던 문제(무중단 배포 중 태스크가 잠깐 2개가 되어 같은 메시지가 두 번
 * 오는 것)보다 가드 자체가 만드는 문제가 더 컸다 — 날짜를 선점한 뒤 발송이 실패하면 그날
 * 리포트가 영구히 유실되고, 이력 테이블은 "보냈다"고 거짓 상태를 남긴다. 중복 발송은
 * 시끄럽지만 무해한 반면, 조용한 유실과 거짓 상태는 나중에 조사를 오도한다. 원본 데이터
 * (users, study_session)는 그대로 남아 있어 리포트는 언제든 재조회할 수 있으므로, 유실을
 * 막는 것보다 가드 자체를 없애는 쪽이 낫다고 판단했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserService userService;
    private final StudySessionMetricsService studySessionMetricsService;
    private final SlackNotifier slackNotifier;
    private final Clock clock;

    public void sendDailyReport() {
        if (!slackNotifier.isEnabled()) {
            log.warn("Slack 발송기가 비활성이라 일일 리포트를 건너뛴다");
            return;
        }

        // 발송 시각이 오전 10시라 오늘은 부분 집계다. 완결된 하루인 어제를 기준일로 쓴다
        LocalDate anchorDate = LocalDate.ofInstant(clock.instant(), KST).minusDays(1);
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
