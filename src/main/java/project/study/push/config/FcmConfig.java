package project.study.push.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import project.study.push.repository.PushTokenRepository;
import project.study.push.service.PushSender;

/**
 * 서비스 계정 키(JSON 본문)로 FCM 클라이언트를 만든다. 키가 없거나 읽을 수 없으면 발송만 끈 채 기동한다 — 알림 설정 하나 때문에
 * API 전체가 뜨지 못하는 쪽이 더 나쁘다(Slack 발송기와 같은 판단).
 *
 * <p>읽은 키의 project_id를 로그에 남긴다. 토큰은 발급한 Firebase 프로젝트의 키로만 보낼 수 있어서, dev 백엔드에 운영 키를
 * 넣으면 모든 발송이 SENDER_ID_MISMATCH로 실패한다 — 로그 한 줄로 바로 알아보게 하려는 것이다. project_id는 앱 설정 파일에도
 * 들어 있는 공개 값이고, private_key는 어디에도 남기지 않는다.
 */
@Slf4j
@Configuration
public class FcmConfig {

    private static final String APP_NAME = "push";

    @Bean
    PushSender pushSender(
            PushTokenRepository pushTokenRepository,
            @Value("${app.push.fcm.service-account-json:}") String serviceAccountJson) {
        return new PushSender(pushTokenRepository, createMessaging(serviceAccountJson));
    }

    private static FirebaseMessaging createMessaging(String serviceAccountJson) {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.warn("app.push.fcm.service-account-json이 비어 있어 푸시를 보내지 않는다");
            return null;
        }
        GoogleCredentials credentials;
        try {
            credentials = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            // 예외 메시지에 키 일부가 섞일 수 있어 종류만 남긴다
            log.error("FCM 서비스 계정 키를 읽지 못해 푸시를 보내지 않는다: {}", e.getClass().getSimpleName());
            return null;
        }
        FirebaseApp app = FirebaseApp.initializeApp(
                FirebaseOptions.builder().setCredentials(credentials).build(), APP_NAME);
        String projectId = credentials instanceof ServiceAccountCredentials account ? account.getProjectId() : "알 수 없음";
        log.info("FCM 발송 활성 — Firebase 프로젝트: {}", projectId);
        return FirebaseMessaging.getInstance(app);
    }
}
