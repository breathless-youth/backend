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

    /**
     * 두 값의 차이 — 집중률은 화면에 보이는(소수 1자리로 반올림한) 두 값의 차이라야 보이는 숫자끼리 뺀 값과 맞는다. 시간·일수 판은
     * 정수 차이다.
     */
    static Number gap(RankingBoardType type, double a, double b) {
        if (type == RankingBoardType.FOCUS_RATE) {
            return round1(Math.abs(round1(a) - round1(b)));
        }
        return Math.round(Math.abs(a - b));
    }

    static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
