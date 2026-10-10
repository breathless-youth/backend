package project.study.ranking.close;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.engine.RankedEntry;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.Standings;

/**
 * 마감 메달 규칙 (BY-828, 2026-10-10 기획 변경) — 1~3위 중 순공·시간대 판은 1800초(30분) 이상만 받는다. 못 받은 자리를 다음
 * 순위로 당기지 않는다(1위 40분·2위 20분이면 1위만). 집중률은 참가 조건(주 10시간·월 30시간)이 이미 있어 따로 걸지 않는다.
 */
public final class Medals {

    public static final int PODIUM = 3;

    /** 순공·시간대 판의 메달 최소 값(초). */
    public static final long MIN_TIME_VALUE = 1800;

    private Medals() {}

    /** 메달을 받는 줄과 그 순위 — 순위는 순위표 그대로다. */
    public static List<RankedEntry> of(RankingBoard board, Standings standings) {
        List<RankingEntry> entries = standings.entries();
        List<RankedEntry> medals = new ArrayList<>(PODIUM);
        for (int i = 0; i < Math.min(PODIUM, entries.size()); i++) {
            if (qualifies(board, entries.get(i).value())) {
                medals.add(new RankedEntry(i + 1, entries.get(i)));
            }
        }
        return medals;
    }

    public static boolean qualifies(RankingBoard board, double value) {
        return board.type() == RankingBoardType.FOCUS_RATE || value >= MIN_TIME_VALUE;
    }

    /** 기록 값 — 시간 판은 초, 집중률은 %를 소수 1자리로 반올림한다(응답 표기와 같다). */
    public static BigDecimal recordValue(double value) {
        double rounded = Math.round(value * 10) / 10.0; // BoardValues.round1과 같은 반올림이라 응답 표기와 어긋나지 않는다
        return BigDecimal.valueOf(rounded).setScale(1, RoundingMode.HALF_UP);
    }
}
