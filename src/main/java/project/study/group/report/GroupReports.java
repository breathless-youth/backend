package project.study.group.report;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.common.text.Graphemes;
import project.study.group.report.ReportAlert.Report;
import project.study.group.repository.ReportRepository;

/**
 * 신고 한 건을 남기고 운영 채널에 알린다 (BY-1124) — 신청 신고와 공부방 멤버 신고가 함께 쓴다. 권한·상태 검사와 신고 뒤 처리(거절·차단)는
 * 호출하는 서비스가 하고, 여기서는 기록과 알림만 한다. 호출자의 트랜잭션 안에서 부르고, 알림은 그 트랜잭션이 커밋된 뒤에만 나간다.
 */
@Component
@RequiredArgsConstructor
public class GroupReports {

    private final ReportRepository reports;
    private final ReportAlert alert;

    /** 사유는 NFC 합성·앞뒤 공백을 다듬어 남기고, 보이는 글자가 없으면 사유 없음(null)이다. 글자 수는 요청 검증이 이미 봤다. */
    public void record(
            String type,
            long reporterId,
            long targetUserId,
            long groupId,
            Long applicationId,
            String rawReason,
            Instant now) {
        String reason = normalize(rawReason);
        reports.insert(type, reporterId, targetUserId, groupId, applicationId, reason, now);
        alert.send(new Report(type, reporterId, targetUserId, groupId, applicationId, reason, now));
    }

    private static String normalize(String raw) {
        String normalized = Graphemes.normalize(raw);
        if (normalized == null || Graphemes.isVisuallyBlank(normalized)) {
            return null;
        }
        return normalized;
    }
}
