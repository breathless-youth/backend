package project.study.ranking.service;

import project.study.ranking.RankingBoard;
import project.study.ranking.dto.RankingRecordItem;
import project.study.ranking.dto.RankingRecordRow;

/** 기록 행 → 응답 항목 (BY-828) — board_key를 판으로 되돌리고 값을 판의 표기(집중률 소수 1자리, 나머지 정수)로 바꾼다. */
final class RecordItems {

    private RecordItems() {}

    static RankingRecordItem of(RankingRecordRow row) {
        RankingBoard board = RankingBoard.fromKey(row.boardKey());
        return new RankingRecordItem(
                row.id(),
                board.type(),
                board.period(),
                board.slot(),
                row.periodStart(),
                row.rank(),
                BoardValues.value(board.type(), row.value().doubleValue()),
                row.closesAt());
    }
}
