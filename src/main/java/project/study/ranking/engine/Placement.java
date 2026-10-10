package project.study.ranking.engine;

import java.util.ArrayList;
import java.util.List;

/** 순위표에 나를 끼운 결과 (BY-828). myIndex가 -1이면 나는 참가자가 아니다. merged는 읽기 전용이다. */
public record Placement(List<RankingEntry> merged, int myIndex) {

    /** 내 주변 리스트 줄 수 — 앞 2 · 나 · 뒤 2. */
    public static final int WINDOW = 5;

    public boolean present() {
        return myIndex >= 0;
    }

    public int size() {
        return merged.size();
    }

    public RankingEntry me() {
        return present() ? merged.get(myIndex) : null;
    }

    public int myRank() {
        return myIndex + 1;
    }

    public List<RankedEntry> podium() {
        return ranked(0, Math.min(3, size()));
    }

    public List<RankedEntry> around() {
        return present() ? windowAround(myIndex) : List.of();
    }

    /** index를 가운데 둔 다섯 줄 — 내가 없을 때 예상 자리 주변을 보여줄 때도 쓴다. */
    public List<RankedEntry> windowAround(int index) {
        int start = windowStart(size(), index);
        return ranked(start, Math.min(size(), start + WINDOW));
    }

    public RankingEntry above() {
        return myIndex > 0 ? merged.get(myIndex - 1) : null;
    }

    public RankingEntry below() {
        return present() && myIndex < size() - 1 ? merged.get(myIndex + 1) : null;
    }

    /** max(1, ceil(순위 × 100 ÷ 참가자 수)) — 정수로 올림한다. */
    public int topPercent() {
        return Math.max(1, (myRank() * 100 + size() - 1) / size());
    }

    /** size칸 목록에서 center를 가운데 두는 다섯 칸 창의 시작 — 앞이 모자라면 뒤를, 뒤가 모자라면 앞을 더 채운다. */
    public static int windowStart(int size, int center) {
        int start = Math.max(0, center - 2);
        int end = Math.min(size, start + WINDOW);
        return Math.max(0, end - WINDOW);
    }

    private List<RankedEntry> ranked(int from, int to) {
        List<RankedEntry> rows = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            rows.add(new RankedEntry(i + 1, merged.get(i)));
        }
        return rows;
    }
}
