package project.study.metrics.amplitude;

/** Export 응답이 다운로드 상한을 넘었다 — 서버 안 적재를 배치로 분리할 때가 됐다는 신호다 (ADR-0030 결정 7). */
public class ExportTooLargeException extends AmplitudeExportException {

    public ExportTooLargeException(long limitBytes) {
        super("Amplitude Export 응답이 상한(%d바이트)을 넘었다 — 배치 분리 검토 신호 (ADR-0030)".formatted(limitBytes));
    }
}
