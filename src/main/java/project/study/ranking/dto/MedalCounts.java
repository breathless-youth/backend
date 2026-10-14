package project.study.ranking.dto;

/** 순위별 메달 수 (BY-828). */
public record MedalCounts(long first, long second, long third) {

    public long total() {
        return first + second + third;
    }
}
