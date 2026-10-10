package project.study.ranking;

import java.util.StringJoiner;
import project.study.common.exception.BadRequestException;
import project.study.studysession.entity.TimeSlot;

/** 랭킹판 하나(종목·기간·시간대 구간) (BY-828). 없는 조합은 of에서 400으로 막는다. */
public record RankingBoard(RankingBoardType type, RankingPeriod period, TimeSlot slot) {

    /** offset은 0(지금 기간)·-1(직전 기간)만, 직전 기간은 기간 판에만 있다. 시간대 판의 slot은 호출자가 먼저 정한다. */
    public static RankingBoard of(RankingBoardType type, RankingPeriod period, TimeSlot slot, int offset) {
        if (offset != 0 && offset != -1) {
            throw new BadRequestException("offset은 0 또는 -1이어야 합니다");
        }
        if (type.hallOfFame()) {
            if (period != null || slot != null || offset != 0) {
                throw new BadRequestException("명예의 전당은 period·slot·offset을 받지 않습니다");
            }
            return new RankingBoard(type, null, null);
        }
        if (period == null || !type.supports(period)) {
            throw new BadRequestException(type + " 랭킹판에 " + period + " 기간은 없습니다");
        }
        if ((type == RankingBoardType.TIME_SLOT) != (slot != null)) {
            throw new BadRequestException("slot은 시간대 랭킹판에만, 반드시 줍니다");
        }
        return new RankingBoard(type, period, slot);
    }

    /** 캐시·마감 기록 키 — 예: FOCUS_TIME:WEEKLY, TIME_SLOT:DAILY:NIGHT, TOTAL_TIME */
    public String key() {
        StringJoiner key = new StringJoiner(":").add(type.name());
        if (period != null) {
            key.add(period.name());
        }
        if (slot != null) {
            key.add(slot.name());
        }
        return key.toString();
    }
}
