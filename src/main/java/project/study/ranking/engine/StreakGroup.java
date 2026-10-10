package project.study.ranking.engine;

import java.util.ArrayList;
import java.util.List;

/** 연속 공부 일수 리스트의 한 줄 — 같은 일수의 연속 구간 [startIndex, startIndex + count) (BY-828). */
public record StreakGroup(int days, int startIndex, int count) {

    public boolean contains(int index) {
        return index >= startIndex && index < startIndex + count;
    }

    /** 일수가 같은 연속 줄을 묶는다 — 순위표가 정렬돼 있어 같은 값은 붙어 있다. */
    public static List<StreakGroup> of(List<RankingEntry> sorted) {
        List<StreakGroup> groups = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= sorted.size(); i++) {
            if (i == sorted.size() || sorted.get(i).value() != sorted.get(start).value()) {
                groups.add(new StreakGroup((int) sorted.get(start).value(), start, i - start));
                start = i;
            }
        }
        return groups;
    }

    public static int indexContaining(List<StreakGroup> groups, int index) {
        for (int g = 0; g < groups.size(); g++) {
            if (groups.get(g).contains(index)) {
                return g;
            }
        }
        return -1;
    }
}
