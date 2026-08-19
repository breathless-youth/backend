package project.study.notice.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.notice.dto.NoticeResponse;
import project.study.notice.repository.NoticeRepository;

@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<NoticeResponse> getActiveNotices() {
        return noticeRepository.findActive(Instant.now(clock)).stream()
                .map(NoticeResponse::from)
                .toList();
    }
}
