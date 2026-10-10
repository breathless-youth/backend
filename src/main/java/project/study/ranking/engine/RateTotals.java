package project.study.ranking.engine;

/** 집중률 판의 한 사용자 합계 — 참가 조건과 상관없이(미달 화면용). */
public record RateTotals(long focusSec, long studySec) {

    /** 순공 ÷ 총공부 × 100 (원값). */
    public double rate() {
        return studySec == 0 ? 0 : focusSec * 100.0 / studySec;
    }
}
