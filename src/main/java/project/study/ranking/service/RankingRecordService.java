package project.study.ranking.service;

import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.TIME_SLOT;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.ranking.RankingBoardType;
import project.study.ranking.close.Medals;
import project.study.ranking.dto.RankingRecordPageResponse;
import project.study.ranking.dto.RankingRecordRow;
import project.study.ranking.dto.RecordCursor;
import project.study.ranking.repository.RankingRecordQueries;

/** 랭킹 마감 기록 조회 (BY-828) — 모두 보기. */
@Service
@RequiredArgsConstructor
public class RankingRecordService {

    static final int MAX_SIZE = 50;

    private static final Set<RankingBoardType> RECORDED_TYPES = EnumSet.of(FOCUS_TIME, FOCUS_RATE, TIME_SLOT);

    private final RankingRecordQueries queries;

    /** 모두 보기 — 내 기록을 (마감 시각, id) 내림차순 커서 페이지로. 달별 묶음은 FE가 한다. */
    @Transactional(readOnly = true)
    public RankingRecordPageResponse list(long userId, Integer rank, RankingBoardType type, String cursor, int size) {
        validate(rank, type, size);
        RecordCursor after = cursor == null ? null : RecordCursor.decode(cursor);
        List<RankingRecordRow> rows = queries.page(userId, rank, type, after, size + 1);
        boolean hasNext = rows.size() > size;
        List<RankingRecordRow> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext
                ? new RecordCursor(page.getLast().closesAt(), page.getLast().id()).encode()
                : null;
        return new RankingRecordPageResponse(page.stream().map(RecordItems::of).toList(), nextCursor);
    }

    private static void validate(Integer rank, RankingBoardType type, int size) {
        if (rank != null && (rank < 1 || rank > Medals.PODIUM)) {
            throw new BadRequestException("rank는 1·2·3 중 하나여야 합니다");
        }
        if (type != null && !RECORDED_TYPES.contains(type)) {
            throw new BadRequestException(type + " 종목에는 마감 기록이 없습니다");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BadRequestException("size는 1~" + MAX_SIZE + "이어야 합니다");
        }
    }
}
