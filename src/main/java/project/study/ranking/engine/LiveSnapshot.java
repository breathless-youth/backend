package project.study.ranking.engine;

import java.time.Instant;
import java.util.List;
import project.study.studysession.dto.LivePiece;

/** 한 시각(asOf)에 나눈 진행 중 조각 전체 — 판들이 10초 동안 공유한다. */
public record LiveSnapshot(List<LivePiece> pieces, Instant asOf) {}
