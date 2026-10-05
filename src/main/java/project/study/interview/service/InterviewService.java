package project.study.interview.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.appconfig.service.AppConfigService;
import project.study.interview.InterviewConfigKeys;
import project.study.interview.InterviewGroup;
import project.study.interview.dto.InterviewStatusResponse;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private final AppConfigService appConfigService;
    private final InterviewGroupService interviewGroupService;

    /**
     * 켜져 있고 링크가 등록된 입구만 true로 내린다 — 링크 없이 켜면 앱이 열 곳이 없다.
     * 그룹 판정은 카드가 켜져 있을 때만 한다.
     */
    @Transactional(readOnly = true)
    public InterviewStatusResponse status(Long userId) {
        if (!appConfigService.isEnabled(InterviewConfigKeys.ENABLED)) {
            return new InterviewStatusResponse(false, null, false, null);
        }

        Optional<String> cardUrl = urlIfEnabled(InterviewConfigKeys.CARD_ENABLED, InterviewConfigKeys.CARD_URL)
                .filter(url -> interviewGroupService.judge(userId) == InterviewGroup.G3_ACTIVE);
        Optional<String> settingsUrl =
                urlIfEnabled(InterviewConfigKeys.SETTINGS_ENABLED, InterviewConfigKeys.SETTINGS_URL);

        return new InterviewStatusResponse(
                cardUrl.isPresent(), cardUrl.orElse(null), settingsUrl.isPresent(), settingsUrl.orElse(null));
    }

    private Optional<String> urlIfEnabled(String enabledKey, String urlKey) {
        return appConfigService.isEnabled(enabledKey) ? appConfigService.getString(urlKey) : Optional.empty();
    }
}
