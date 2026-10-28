package project.study.push.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * 보낼 알림 한 건. link는 스킴 없는 경로(`/records`)로 쓴다 — dev 백엔드 하나가 dev·staging 두 빌드에 보내므로 앱 스킴을
 * 박으면 한쪽 빌드에서 안 열린다. type은 알림 종류이자 FCM analytics_label이라 영문·숫자·`-_.~%` 50자 이내여야 한다.
 * pushId는 발송 기록(push_send)의 id다. 앱 열림 이벤트에 실려 발송과 열림을 잇는다. 기록 없는 발송(테스트)은 null이다.
 */
public record PushMessage(String title, String body, String link, String type, UUID pushId) {

    public PushMessage {
        Objects.requireNonNull(title);
        Objects.requireNonNull(body);
        Objects.requireNonNull(link);
        Objects.requireNonNull(type);
    }

    public PushMessage(String title, String body, String link, String type) {
        this(title, body, link, type, null);
    }
}
