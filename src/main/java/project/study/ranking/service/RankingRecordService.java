package project.study.ranking.service;

import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.TIME_SLOT;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.ranking.close.Medals;
import project.study.ranking.dto.RankingRecordItem;
import project.study.ranking.dto.RankingRecordPageResponse;
import project.study.ranking.dto.RankingRecordRow;
import project.study.ranking.dto.RankingRecordSeenRequest;
import project.study.ranking.dto.RankingUnseenResponse;
import project.study.ranking.dto.RecordCursor;
import project.study.ranking.repository.RankingCloseRepository;
import project.study.ranking.repository.RankingRecordQueries;
import project.study.studysession.entity.TimeSlot;

/** 랭킹 마감 기록 조회 (BY-828) — 모두 보기, 마감 모달(04시 보류), 본 것으로 표시. */
@Service
@RequiredArgsConstructor
public class RankingRecordService {

    static final int MAX_SIZE = 50;

    private static final Set<RankingBoardType> RECORDED_TYPES = EnumSet.of(FOCUS_TIME, FOCUS_RATE, TIME_SLOT);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime NIGHT_CLOSE = LocalTime.of(4, 0);
    private static final String DAILY_NIGHT = new RankingBoard(TIME_SLOT, RankingPeriod.DAILY, TimeSlot.NIGHT).key();
    private static final String WEEKLY_NIGHT = new RankingBoard(TIME_SLOT, RankingPeriod.WEEKLY, TimeSlot.NIGHT).key();

    /** 마감 모달 순서 — 순위 → 일·주·월 → 순공·집중률·시간대(명세 §4-5) → 구간 → 마감 시각 → id. */
    private static final Comparator<RankingRecordItem> MODAL_ORDER = Comparator.comparingInt(RankingRecordItem::rank)
            .thenComparing(RankingRecordItem::period)
            .thenComparing(RankingRecordItem::type)
            .thenComparing(RankingRecordItem::slot, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(RankingRecordItem::closesAt)
            .thenComparingLong(RankingRecordItem::id);

    private final RankingRecordQueries queries;
    private final RankingCloseRepository closes;
    private final Clock clock;

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

    /**
     * 마감 모달 — 안 본 기록을 모달 순서로. 04시 보류: 가장 최근 04:00 심야 마감(월요일이면 심야 주간까지)이 표시되기 전에는 그 전날
     * 04:00까지 마감된 기록만 준다 — 00시 메달을 그날 04시 심야 메달과 묶어 04시 이후 처음 앱을 열 때 한 번에 보여주기 위해서다.
     * 한 번에 최대 100개(seen이 받는 수와 같다) — 나머지는 seen으로 표시한 뒤 다음 호출에 준다.
     */
    @Transactional(readOnly = true)
    public RankingUnseenResponse unseen(long userId) {
        List<RankingRecordItem> records = queries.unseen(userId, unseenCutoff(clock.instant())).stream()
                .map(RecordItems::of)
                .sorted(MODAL_ORDER)
                .limit(RankingRecordSeenRequest.MAX_IDS)
                .toList();
        return new RankingUnseenResponse(queries.counts(userId).total(), records);
    }

    /** 내 기록만 본 것으로 표시한다. 빈 목록이면 아무것도 하지 않는다. */
    @Transactional
    public void markSeen(long userId, List<Long> ids) {
        if (!ids.isEmpty()) {
            queries.markSeen(userId, ids, clock.instant());
        }
    }

    /** 안 본 기록을 줄 마감 시각 상한 — 가장 최근 04:00 심야 마감이 (건너뛴 것 포함) 표시됐으면 그 시각, 아니면 하루 전 04:00. */
    Instant unseenCutoff(Instant now) {
        LocalDate day = TimeSlot.slotDateOf(now);
        List<String> nightKeys =
                day.getDayOfWeek() == DayOfWeek.MONDAY ? List.of(DAILY_NIGHT, WEEKLY_NIGHT) : List.of(DAILY_NIGHT);
        Instant latestNightClose = nightClose(day);
        return closes.countClosedAt(nightKeys, latestNightClose) == nightKeys.size()
                ? latestNightClose
                : nightClose(day.minusDays(1));
    }

    private static Instant nightClose(LocalDate day) {
        return day.atTime(NIGHT_CLOSE).atZone(KST).toInstant();
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
