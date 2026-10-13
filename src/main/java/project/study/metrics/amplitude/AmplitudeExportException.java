package project.study.metrics.amplitude;

/**
 * Export 다운로드·파싱 실패. 원인(cause)을 붙이지 않는다 — HTTP 예외 체인에 Authorization 헤더나 요청 정보가 실릴 수 있어
 * 상태 코드·예외 클래스 이름만 메시지에 쓴다 ({@code SlackNotificationException}과 같은 이유).
 */
public class AmplitudeExportException extends RuntimeException {

    public AmplitudeExportException(String message) {
        super(message);
    }
}
