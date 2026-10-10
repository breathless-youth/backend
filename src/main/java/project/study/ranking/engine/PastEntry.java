package project.study.ranking.engine;

import java.time.Duration;
import java.time.Instant;
import project.study.studysession.dto.LivePiece;

/**
 * 기간 판의 since 시점 값 한 줄 (BY-828 §7.3, 첫 접속 추월). valueAt·achievedAt은 since까지, studiedFrom은 since 뒤에 처음 공부한
 * 시각이다(없으면 null). 진행 중 조각만 있는 사람은 닉네임을 나중에 채운다.
 */
public record PastEntry(long userId, String nickname, long valueAt, Instant achievedAt, Instant studiedFrom) {

    /** 진행 중 조각 하나를 since로 되돌린다 — 끝났으면 전부, 걸쳐 있으면 시간 비율만큼, since 뒤에 시작했으면 0. */
    static PastEntry of(LivePiece piece, Instant since) {
        Instant start = piece.startedAt();
        Instant end = piece.endedAt();
        long value;
        if (!end.isAfter(since)) {
            value = piece.focusSec();
        } else if (!start.isBefore(since)) {
            value = 0;
        } else {
            value = piece.focusSec()
                    * Duration.between(start, since).toSeconds()
                    / Duration.between(start, end).toSeconds();
        }
        Instant achievedAt = start.isBefore(since) ? earlier(end, since) : null;
        Instant studiedFrom = end.isAfter(since) ? later(start, since) : null;
        return new PastEntry(piece.userId(), null, value, achievedAt, studiedFrom);
    }

    PastEntry plus(PastEntry other) {
        return new PastEntry(
                userId,
                nickname != null ? nickname : other.nickname,
                valueAt + other.valueAt,
                later(achievedAt, other.achievedAt),
                earlier(studiedFrom, other.studiedFrom));
    }

    PastEntry withNickname(String newNickname) {
        return new PastEntry(userId, newNickname, valueAt, achievedAt, studiedFrom);
    }

    /** since 시점 순위표의 줄 — 값이 0이면 그때 참가자가 아니다(호출자가 거른다). */
    public RankingEntry toEntry() {
        return RankingEntry.of(userId, nickname, valueAt, achievedAt);
    }

    private static Instant earlier(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
