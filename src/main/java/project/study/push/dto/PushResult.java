package project.study.push.dto;

/** 발송 결과 개수. sent는 FCM이 접수한 수일 뿐 기기 도착 수가 아니다. removed는 UNREGISTERED로 지운 토큰 수다. */
public record PushResult(int sent, int failed, int removed) {

    public static final PushResult NONE = new PushResult(0, 0, 0);
}
