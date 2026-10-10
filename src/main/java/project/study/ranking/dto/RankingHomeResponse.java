package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 홈 랭킹 (BY-828 §7.3) — 한 줄 카드(5b)와 첫 접속 추월(5a). 다른 사용자의 userId는 싣지 않는다. */
@Schema(description = "홈 랭킹 — 한 줄 카드와 첫 접속 추월")
public record RankingHomeResponse(
        @Schema(description = "이번 주 순공 순위 — 이번 주 기록이 없으면 null")
        HomeCard card,

        @Schema(
                description =
                        "since 뒤로 나를 추월한 사람 — since가 없거나 이번 주 월요일 00:00 이전·미래이거나, since에 내 순위가 없었거나, 순위가 내려가지 않았으면 null")
        Overtaken overtaken) {

    public record HomeCard(
            int rank,
            @Schema(description = "순공(초)") long value,
            @Schema(description = "바로 위 — 1위면 null") HomeNeighbor above) {}

    public record HomeNeighbor(
            String nickname,
            @Schema(description = "순공 차이(초)") long gap) {}

    public record Overtaken(
            int fromRank,
            int toRank,

            @Schema(description = "since에 내 뒤였거나 참가 전이었다가 지금 내 앞인 사람 수")
            int count,

            @Schema(description = "그중 지금 나와 가장 가까운 2명, 가까운 순")
            List<Overtaker> nearest) {}

    public record Overtaker(
            String nickname,
            @Schema(description = "지금 순공 차이(초)") long gap,

            @Schema(description = "since 뒤에 처음 공부를 시작한 시각 — since에 공부 중이었으면 since")
            Instant studiedFrom,

            @Schema(description = "since 뒤로 쌓은 순공(초)") long studiedFocusSec) {}
}
