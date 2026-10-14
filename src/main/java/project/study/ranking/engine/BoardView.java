package project.study.ranking.engine;

/** 요청 시각으로 올린 순위표, 내 줄을 끼운 배치, 그 계산에 쓴 진행 중 조각. */
public record BoardView(Standings standings, Placement placement, LiveSnapshot live) {}
