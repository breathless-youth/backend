package project.study.metrics.amplitude;

/** Amplitude Export 창 하나를 받아 온다. 인터페이스로 두어 적재 테스트에서 HTTP 없이 대체한다. */
public interface AmplitudeExportSource {

    /** API 키·시크릿 키가 모두 설정돼 있는지. 비활성이면 적재를 건너뛴다. */
    boolean isEnabled();

    /** 창 하나의 이벤트를 받아 모은다. 데이터가 없으면(404) 빈 결과, 실패면 {@link AmplitudeExportException}. */
    WindowAggregate fetch(ExportWindow window);
}
