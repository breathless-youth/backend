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

        @Schema(description = "호환용 과목 구간. 새 화면은 pieces를 쓴다") List<PlannerRecordSegment> subjectSegments,

        @Schema(description = "수정 충돌 확인용 기록 버전. 수정본이 없는 기록은 0. EDIT_RANGES에 그대로 보낸다", example = "0")
        long revision,

        @Schema(description = "조회 창으로 자른 실제 존재 조각. 시작 순·겹침 없음. 조각 사이 빈칸은 삭제했거나 기록이 없는 시간")
        List<PlannerRecordPiece> pieces) {}
