package project.study.push.dto;

import java.util.Objects;

/**
 * 보낼 알림 한 건. link는 스킴 없는 경로(`/records`)로 쓴다 — dev 백엔드 하나가 dev·staging 두 빌드에 보내므로 앱 스킴을
 * 박으면 한쪽 빌드에서 안 열린다. type은 알림 종류이자 FCM analytics_label이라 영문·숫자·`-_.~%` 50자 이내여야 한다.
 */
public record PushMessage(String title, String body, String link, String type) {

    public PushMessage {
        Objects.requireNonNull(title);
        Objects.requireNonNull(body);
        Objects.requireNonNull(link);
        Objects.requireNonNull(type);
    }
}
