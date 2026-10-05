package project.study.notice.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.appconfig.service.AppConfigService;
import project.study.interview.InterviewConfigKeys;
import project.study.interview.InterviewGroup;
import project.study.interview.service.InterviewGroupService;
import project.study.notice.dto.NoticeResponse;
import project.study.notice.entity.Notice;
import project.study.notice.repository.NoticeRepository;

@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;
    private final AppConfigService appConfigService;
    private final InterviewGroupService interviewGroupService;
    private final Clock clock;

    /**
     * 전원 공지와, 사용자 그룹에 맞는 인터뷰 공지만 내려준다 (ADR-0027). 인터뷰 전체가 꺼져 있으면 인터뷰 공지는 모두 빠진다.
     * 그룹 판정은 활성 인터뷰 공지가 있을 때만 한다 — 대부분의 요청은 세션 쿼리 없이 끝난다.
     */
    @Transactional(readOnly = true)
    public List<NoticeResponse> getActiveNotices(Long userId) {
        List<Notice> active = noticeRepository.findActive(Instant.now(clock));
        boolean interviewOn =
                active.stream().anyMatch(notice -> notice.getAudience().isInterview())
                        && appConfigService.isEnabled(InterviewConfigKeys.ENABLED);
        InterviewGroup group = interviewOn ? interviewGroupService.judge(userId) : InterviewGroup.NONE;

        return active.stream()
                .filter(notice -> !notice.getAudience().isInterview()
                        || (interviewOn && notice.getAudience().includes(group)))
                .map(NoticeResponse::from)
                .toList();
    }
}
