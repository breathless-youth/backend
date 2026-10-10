package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import project.study.config.ApiVersionConfig;

class RankingRecordListApiTest extends RankingRecordTestBase {

    private static final String RECORDS = "/api/rankings/records";
    private static final LocalDate WED = LocalDate.of(2026, 10, 7);
    private static final LocalDate THU = LocalDate.of(2026, 10, 8);

    @Test
    void 최신_마감부터_커서로_나눠_주고_마지막_페이지는_nextCursor가_없다() throws Exception {
        long me = user("me");
        long rate = record(me, "FOCUS_RATE:WEEKLY", LocalDate.of(2026, 9, 28), kst(10, 5, 0, 0), 3, "90.9");
        long daily = record(me, "FOCUS_TIME:DAILY", WED, kst(10, 8, 0, 0), 2, "2400.0");
        long slot = record(me, "TIME_SLOT:DAILY:MORNING", THU, kst(10, 9, 0, 0), 1, "11060.0");

        String first = get(RECORDS + "?size=2", me).exchange().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(first, "$.items[*].id")).containsExactly((int) slot, (int) daily);
        assertThat(JsonPath.<String>read(first, "$.items[0].type")).isEqualTo("TIME_SLOT");
        assertThat(JsonPath.<String>read(first, "$.items[0].period")).isEqualTo("DAILY");
        assertThat(JsonPath.<String>read(first, "$.items[0].slot")).isEqualTo("MORNING");
        assertThat(JsonPath.<String>read(first, "$.items[0].periodStart")).isEqualTo("2026-10-08");
        assertThat(JsonPath.<Integer>read(first, "$.items[0].rank")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(first, "$.items[0].value")).isEqualTo(11060);
        assertThat(JsonPath.<String>read(first, "$.items[0].closesAt")).isEqualTo("2026-10-08T15:00:00Z");

        String cursor = JsonPath.read(first, "$.nextCursor");
        assertThat(get(RECORDS + "?size=2&cursor=" + cursor, me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.items[0].id", v -> assertThat(v).isEqualTo((int) rate))
                .hasPathSatisfying("$.items[0].value", v -> assertThat(v).isEqualTo(90.9))
                .hasPathSatisfying("$.items[0].slot", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.nextCursor", v -> assertThat(v).isNull());
    }

    @Test
    void 같은_마감_시각의_기록이_페이지_경계에_걸려도_빠지거나_겹치지_않는다() throws Exception {
        long me = user("me");
        Instant closesAt = kst(10, 9, 0, 0);
        long a = record(me, "FOCUS_TIME:DAILY", THU, closesAt, 1, "5000.0");
        long b = record(me, "TIME_SLOT:DAILY:MORNING", THU, closesAt, 1, "3000.0");
        long c = record(me, "TIME_SLOT:DAILY:EVENING", THU, closesAt, 2, "2000.0");

        List<Integer> ids = new ArrayList<>();
        String cursor = null;
        do {
            String uri = RECORDS + "?size=1" + (cursor == null ? "" : "&cursor=" + cursor);
            String body = get(uri, me).exchange().getResponse().getContentAsString();
            ids.addAll(JsonPath.<List<Integer>>read(body, "$.items[*].id"));
            cursor = JsonPath.read(body, "$.nextCursor");
        } while (cursor != null);

        assertThat(ids).containsExactly((int) c, (int) b, (int) a);
    }

    @Test
    void 순위와_종목으로_거르고_남의_기록은_주지_않는다() {
        long me = user("me");
        long other = user("other");
        record(me, "FOCUS_TIME:DAILY", WED, kst(10, 8, 0, 0), 1, "5000.0");
        long slot2 = record(me, "TIME_SLOT:DAILY:MORNING", WED, kst(10, 8, 0, 0), 2, "3000.0");
        record(me, "FOCUS_TIME:WEEKLY", LocalDate.of(2026, 9, 28), kst(10, 5, 0, 0), 2, "40000.0");
        record(me, "TIME_SLOT:DAILY:EVENING", WED, kst(10, 8, 0, 0), 1, "2500.0"); // 종목은 맞지만 순위가 달라 빠진다
        record(other, "TIME_SLOT:DAILY:EVENING", WED, kst(10, 8, 0, 0), 2, "2000.0");

        assertThat(get(RECORDS + "?rank=2&type=TIME_SLOT", me))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.items[*].id")
                .asArray()
                .containsExactly((int) slot2);
    }

    @Test
    void 커서가_비어_있으면_첫_페이지를_준다() {
        long me = user("me");
        long daily = record(me, "FOCUS_TIME:DAILY", WED, kst(10, 8, 0, 0), 2, "2400.0");

        assertThat(get(RECORDS + "?cursor=", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.items[0].id", v -> assertThat(v).isEqualTo((int) daily))
                .hasPathSatisfying("$.nextCursor", v -> assertThat(v).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rank=0", "rank=4", "type=TOTAL_TIME", "type=NOPE", "size=0", "size=51", "cursor=bm9wZQ"})
    void 범위_밖_값은_400이다(String query) {
        assertThat(get(RECORDS + "?" + query, user("me"))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 토큰이_없으면_401이다() {
        assertThat(mvc.get().uri(RECORDS).header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
