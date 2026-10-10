package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 본 것으로 표시할 기록 (BY-828). */
@Schema(description = "본 것으로 표시할 기록")
public record RankingRecordSeenRequest(
        @Schema(description = "records/unseen에서 받은 기록 id, 최대 100개", example = "[31, 32]") @NotNull @Size(max = 100)
        List<@NotNull Long> ids) {}
