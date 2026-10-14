package project.study.ranking.engine;

/** 순위(1부터)가 붙은 줄. */
public record RankedEntry(int rank, RankingEntry entry) {}
