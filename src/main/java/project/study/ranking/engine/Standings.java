package project.study.ranking.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** 한 판·한 기간의 정렬된 참가자 목록 (BY-828) — 불변. asOf는 값의 기준 시각이다. */
public record Standings(List<RankingEntry> entries, Instant asOf) {

    public Standings {
        entries = List.copyOf(entries);
    }

    public static Standings of(Collection<RankingEntry> entries, Instant asOf) {
        List<RankingEntry> sorted = new ArrayList<>(entries);
        sorted.sort(RankingEntry.ORDER);
        return new Standings(sorted, asOf);
    }

    /** 집중 중인 줄을 now 값으로 올려 다시 정렬한다. 집중 중인 줄이 없으면 기준 시각만 바꾼다. */
    public Standings advancedTo(Instant now) {
        if (entries.stream().noneMatch(RankingEntry::focusing)) {
            return new Standings(entries, now);
        }
        return of(entries.stream().map(entry -> entry.advancedTo(asOf, now)).toList(), now);
    }

    /** me를 끼운 배치 — 순위표에 남아 있는 내 옛 줄은 빼고 새로 읽은 me로 대신한다. me가 null이면 남들만이다. */
    public Placement place(RankingEntry me) {
        List<RankingEntry> merged = new ArrayList<>(entries.size() + 1);
        for (RankingEntry entry : entries) {
            if (me == null || entry.userId() != me.userId()) {
                merged.add(entry);
            }
        }
        if (me == null) {
            return new Placement(Collections.unmodifiableList(merged), -1);
        }
        int index = Collections.binarySearch(merged, me, RankingEntry.ORDER);
        int insertion = index >= 0 ? index : -index - 1;
        merged.add(insertion, me);
        return new Placement(Collections.unmodifiableList(merged), insertion);
    }

    /** 가정한 줄이 들어가면 받을 순위(1부터) — 같은 userId의 줄은 빼고 센다. */
    public int rankOf(RankingEntry hypothetical) {
        return place(hypothetical).myRank();
    }
}
