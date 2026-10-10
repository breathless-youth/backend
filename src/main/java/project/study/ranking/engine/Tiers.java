package project.study.ranking.engine;

import java.util.List;
import java.util.OptionalInt;

/** 상위 % 목표 구간 (BY-828) — "상위 5%까지 28시간", "3시간이면 상위 50%"에 쓴다. */
public final class Tiers {

    public static final List<Integer> PERCENTS = List.of(1, 5, 10, 20, 30, 50);

    private Tiers() {}

    /** 상위 percent% 안의 마지막 순위 — floor(size × percent ÷ 100). 0이면 그 구간엔 아무도 없다. */
    public static int cutoffRank(int percent, int size) {
        return size * percent / 100;
    }

    /** 내 상위 %보다 좁은 구간 중 가장 가까운 것 — 지금 참가자 수로 닿을 수 있는(컷 순위 ≥ 1) 구간만. */
    public static OptionalInt next(int myPercent, int size) {
        for (int i = PERCENTS.size() - 1; i >= 0; i--) {
            int percent = PERCENTS.get(i);
            if (percent < myPercent && cutoffRank(percent, size) >= 1) {
                return OptionalInt.of(percent);
            }
        }
        return OptionalInt.empty();
    }
}
