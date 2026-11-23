package project.study.push.service;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.push.dto.PushTokenRequest;
import project.study.push.repository.PointsShopReminderQueries;
import project.study.push.repository.PushTokenRepository;

/** 기기 토큰 등록. 앱이 실행·토큰 갱신·사용자 변경 때마다 부르므로 멱등이어야 한다. */
@Service
public class PushTokenService {

    private final PushTokenRepository pushTokenRepository;
    private final PointsShopReminderQueries pointsShopReminders;
    private final Clock clock;
    private final boolean pointsShopReminderEnabled;

    public PushTokenService(
            PushTokenRepository pushTokenRepository,
            PointsShopReminderQueries pointsShopReminders,
            Clock clock,
            @Value("${app.push.points-shop-reminder.enabled:false}") boolean pointsShopReminderEnabled) {
        this.pushTokenRepository = pushTokenRepository;
        this.pointsShopReminders = pointsShopReminders;
        this.clock = clock;
        this.pointsShopReminderEnabled = pointsShopReminderEnabled;
    }

    /** 다른 사용자에게 붙어 있던 토큰이면 지금 사용자로 옮긴다 — 같은 기기에서 다른 계정으로 로그인한 경우다. */
    @Transactional
    public void register(Long userId, PushTokenRequest request) {
        pushTokenRepository.upsert(
                userId, request.token(), request.platform().name(), request.notificationsEnabled(), locale(request));
        if (pointsShopReminderEnabled && request.notificationsEnabled()) {
            pointsShopReminders.enroll(userId, clock.instant());
        }
    }

    // 언어를 보내지 않는 구 앱은 지금까지처럼 한국어 알림을 받아야 한다
    private static String locale(PushTokenRequest request) {
        String locale = request.locale();
        return locale == null || locale.isBlank() ? "ko" : locale.strip();
    }
}
