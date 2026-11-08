package project.study.planner.record;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import project.study.studysession.entity.StudySessionSource;

public record PlannerRecordView(
        String key,
        StudySessionSource source,

        @Schema(description = "개인 수정본 또는 새 개인 직접 입력이면 true. 과목 유무와 무관하다")
        boolean edited,

        @Schema(description = "조회 창으로 절삭한 표시 시작 시각") Instant startedAt,
        @Schema(description = "조회 창으로 절삭한 표시 종료 시각") Instant endedAt,

        @Schema(description = "현재 개인 기록의 절삭 전 전체 시작. 수정 요청에 사용하며 측정 원본과는 다를 수 있다")
        Instant fullStartedAt,

        @Schema(description = "현재 개인 기록의 절삭 전 전체 종료. 다른 날짜의 조각도 같은 전체 범위를 제공한다")
        Instant fullEndedAt,

        long studySec,
        long focusSec,
        List<PlannerRecordSegment> subjectSegments) {}
