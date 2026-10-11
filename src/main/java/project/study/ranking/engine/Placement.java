package project.study.ranking.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 순위표에 나를 끼운 결과 (BY-828). myIndex가 -1이면 나는 참가자가 아니다. merged는 읽기 전용이다. tied면 공동 순위다 — 같은 값은
 * 같은 순위이고 다음 순위는 그 인원만큼 건너뛴다(1, 1, 3). 줄 순서는 둘 다 같은 정렬 키를 따른다.
 */
public record Placement(List<RankingEntry> merged, int myIndex, boolean tied) {

    /** 내 주변 리스트 줄 수 — 앞 2 · 나 · 뒤 2. */
    public static final int WINDOW = 5;

    public Placement(List<RankingEntry> merged, int myIndex) {
        this(merged, myIndex, false);
    }

    public Placement withTies(boolean tied) {
        return new Placement(merged, myIndex, tied);
    }

    public boolean present() {
        return myIndex >= 0;
    }

    public int size() {
        return merged.size();
    }

    public RankingEntry me() {
        return present() ? merged.get(myIndex) : null;
    }

    /** 내 순위 — 참가자가 아니면 0. */
    public int myRank() {
        return present() ? rankAt(myIndex) : 0;
    }

    /** index 자리의 순위 — 공동 순위면 같은 값 묶음의 첫 자리 순위. */
    public int rankAt(int index) {
        return (tied ? groupStart(index) : index) + 1;
    }

    /** 이 값을 가진 줄이 들어오면 받을 공동 순위 — 그보다 값이 큰 사람 수 + 1. */
    public int tiedRankOf(double value) {
        int above = 0;
        while (above < size() && merged.get(above).value() > value) {
            above++;
        }
        return above + 1;
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

    /** 바로 위 — 공동 순위면 나와 값이 다른 가장 가까운 사람(위 묶음의 마지막). */
    public RankingEntry above() {
        if (!present()) {
            return null;
        }
        int index = (tied ? groupStart(myIndex) : myIndex) - 1;
        return index >= 0 ? merged.get(index) : null;
    }

    /** 바로 아래 — 공동 순위면 나와 값이 다른 가장 가까운 사람(아래 묶음의 첫 사람). */
    public RankingEntry below() {
        if (!present()) {
            return null;
        }
        int index = tied ? groupEnd(myIndex) : myIndex + 1;
        return index < size() ? merged.get(index) : null;
    }

    /** max(1, ceil(순위 × 100 ÷ 참가자 수)) — 정수로 올림한다. 공동 순위면 공동 순위로 센다. */
    public int topPercent() {
        return Math.max(1, (myRank() * 100 + size() - 1) / size());
    }

    /** size칸 목록에서 center를 가운데 두는 다섯 칸 창의 시작 — 앞이 모자라면 뒤를, 뒤가 모자라면 앞을 더 채운다. */
    public static int windowStart(int size, int center) {
        int start = Math.max(0, center - 2);
        int end = Math.min(size, start + WINDOW);
        return Math.max(0, end - WINDOW);
    }

    /** index와 값이 같은 묶음의 첫 자리 — 정렬돼 있어 같은 값은 붙어 있다. */
    private int groupStart(int index) {
        int start = index;
        while (start > 0 && merged.get(start - 1).value() == merged.get(index).value()) {
            start--;
        }
        return start;
    }

    /** index와 값이 같은 묶음 바로 다음 자리. */
    private int groupEnd(int index) {
        int end = index + 1;
        while (end < size() && merged.get(end).value() == merged.get(index).value()) {
            end++;
        }
        return end;
    }

    private List<RankedEntry> ranked(int from, int to) {
        List<RankedEntry> rows = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            rows.add(new RankedEntry(rankAt(i), merged.get(i)));
        }
        return rows;
    }
}
