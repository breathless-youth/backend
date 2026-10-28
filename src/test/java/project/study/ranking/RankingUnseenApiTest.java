package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 마감 모달 — 안 본 기록의 순서와 본 것으로 표시 (BY-828). 기준 시각은 2026-10-10(토) 15:00 KST. */
class RankingUnseenApiTest extends RankingRecordTestBase {

    private static final String UNSEEN = "/api/rankings/records/unseen";
    private static final String SEEN = "/api/rankings/records/seen";
    private static final LocalDate THU = LocalDate.of(2026, 10, 8);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);

    private void assertUnseen(long userId, long... ids) {
        assertThat(get(UNSEEN, userId))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.records[*].id")
                .asArray()
                .containsExactly(
                        Arrays.stream(ids).mapToObj(id -> (Object) (int) id).toArray());
    }

    @Test
    void 순위_일주월_종목_구간_순으로_주고_total은_본_것까지_센_누적_메달_수다() {
        long me = user("me");
        long night = record(me, "TIME_SLOT:DAILY:NIGHT", FRI, kst(10, 10, 0, 0), 1, "1900.0");
        long second = record(me, "FOCUS_TIME:DAILY", FRI, kst(10, 10, 0, 0), 2, "5000.0");
        long daily = record(me, "FOCUS_TIME:DAILY", THU, kst(10, 9, 0, 0), 1, "4000.0");
        long weekly = record(me, "FOCUS_RATE:WEEKLY", LocalDate.of(2026, 9, 28), kst(10, 5, 0, 0), 1, "90.9");
        long morning = record(me, "TIME_SLOT:DAILY:MORNING", FRI, kst(10, 10, 0, 0), 1, "3000.0");
        long seen = record(me, "FOCUS_TIME:MONTHLY", LocalDate.of(2026, 9, 1), kst(10, 1, 0, 0), 1, "90000.0");
        jdbc.update("UPDATE ranking_record SET seen_at = now() WHERE id = ?", seen);

        assertUnseen(me, daily, night, morning, weekly, second);
        assertThat(get(UNSEEN, me))
                .bodyJson()
                .hasPathSatisfying("$.total", v -> assertThat(v).isEqualTo(6));
    }

    @Test
    void 마감된_안_본_기록은_시각과_상관없이_모두_준다() {
        long me = user("me");
        long saturday = record(me, "FOCUS_TIME:DAILY", FRI, kst(10, 10, 0, 0), 1, "5000.0");
        long friday = record(me, "FOCUS_TIME:DAILY", THU, kst(10, 9, 0, 0), 1, "4000.0");

        clock.set(kst(10, 10, 0, 2));
        assertUnseen(me, friday, saturday);
    }

    @Test
    void 본_것으로_표시하면_다시_주지_않고_남의_기록은_건드리지_않는다() {
        long me = user("me");
        long other = user("other");
        long mine = record(me, "FOCUS_TIME:DAILY", FRI, kst(10, 10, 0, 0), 1, "5000.0");
        long theirs = record(other, "FOCUS_TIME:DAILY", FRI, kst(10, 10, 0, 0), 2, "4000.0");

        assertThat(post(SEEN, me, "{\"ids\": [" + mine + ", " + theirs + "]}")).hasStatus(HttpStatus.NO_CONTENT);

        assertUnseen(me);
        assertUnseen(other, theirs);
        assertThat(jdbc.queryForObject("SELECT seen_at FROM ranking_record WHERE id = ?", OffsetDateTime.class, mine)
                        .toInstant())
                .isEqualTo(NOW);
    }

    @Test
    void 안_본_기록이_100개를_넘으면_100개만_주고_seen_뒤에_나머지를_준다() throws Exception {
        long me = user("me");
        insertDailyRecords(me, 101);

        List<Integer> first = unseenIds(me);
        assertThat(first).hasSize(100);
        assertThat(get(UNSEEN, me))
                .bodyJson()
                .hasPathSatisfying("$.total", v -> assertThat(v).isEqualTo(101));

        String ids = first.stream().map(String::valueOf).collect(Collectors.joining(",", "{\"ids\": [", "]}"));
        assertThat(post(SEEN, me, ids)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(unseenIds(me)).hasSize(1).doesNotContainAnyElementsOf(first);
    }

    /** 순위 1위 기록 count개 — 판은 같고 기간만 하루씩 다르다. */
    private void insertDailyRecords(long userId, int count) {
        for (int i = 0; i < count; i++) {
            LocalDate periodStart = LocalDate.of(2026, 1, 1).plusDays(i);
            Instant closesAt = periodStart.plusDays(1).atStartOfDay(KST).toInstant();
            record(userId, "FOCUS_TIME:DAILY", periodStart, closesAt, 1, "3000.0");
        }
    }

    private List<Integer> unseenIds(long userId) throws Exception {
        String body = get(UNSEEN, userId).exchange().getResponse().getContentAsString();
        return JsonPath.read(body, "$.records[*].id");
    }

    @Test
    void ids가_없거나_100개를_넘으면_400이고_빈_목록은_아무것도_하지_않는다() {
        long me = user("me");
        String tooMany = LongStream.rangeClosed(1, 101)
                .mapToObj(Long::toString)
                .collect(Collectors.joining(",", "{\"ids\": [", "]}"));

        assertThat(post(SEEN, me, "{}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(SEEN, me, "{\"ids\": [null]}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(SEEN, me, tooMany)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(SEEN, me, "{\"ids\": []}")).hasStatus(HttpStatus.NO_CONTENT);
    }
}
