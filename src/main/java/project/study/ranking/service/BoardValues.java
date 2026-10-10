package project.study.ranking.service;

import project.study.ranking.RankingBoardType;

/** 응답 값 표기 — 집중률은 % 소수 1자리, 나머지는 정수(초·일). */
final class BoardValues {

    private BoardValues() {}

    static Number value(RankingBoardType type, double raw) {
        if (type == RankingBoardType.FOCUS_RATE) {
            return round1(raw);
        }
        return Math.round(raw);
    }

    static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
