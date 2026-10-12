package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static project.study.metrics.amplitude.ExportZipFixture.event;
import static project.study.metrics.amplitude.ExportZipFixture.zip;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AmplitudeEventParserTest {

    private static final LocalDate OCT_12 = LocalDate.of(2026, 10, 12);

    private static WindowAggregate parse(Map<String, List<String>> entries) throws IOException {
        return AmplitudeEventParser.parse(new ByteArrayInputStream(zip(entries)));
    }

    private static WindowAggregate parse(List<String> lines) throws IOException {
        return parse(Map.of("123/123_2026-10-12_3#0.json.gz", lines));
    }

    @Test
    void 유저_발생일_이벤트별로_센다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "app_launched", "2026-10-12 01:00:00.000000"),
                event("7", "app_launched", "2026-10-12 02:00:00.000000"),
                event("8", "planner_opened", "2026-10-12 03:00:00.000000")));

        assertThat(result.counts())
                .containsOnly(
                        Map.entry(new DailyEventKey(7, OCT_12, "app_launched"), 2),
                        Map.entry(new DailyEventKey(8, OCT_12, "planner_opened"), 1));
        assertThat(result.eventCount()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
    }

    @Test
    void 발생_시각이_UTC_15시_이후면_KST_다음_날로_센다() throws IOException {
        WindowAggregate result = parse(List.of(event("7", "app_launched", "2026-10-12 15:00:00.000000")));

        assertThat(result.counts()).containsOnlyKeys(new DailyEventKey(7, OCT_12.plusDays(1), "app_launched"));
    }

    @Test
    void user_id가_없거나_숫자가_아니거나_줄이_깨지면_건너뛰고_센다() throws IOException {
        WindowAggregate result = parse(List.of(
                event(null, "invite_link_opened", "2026-10-12 01:00:00.000000"),
                event("abc", "app_launched", "2026-10-12 01:00:00.000000"),
                "{not json",
                event("7", "app_launched", "2026-10-12 01:00:00.000000"),
                event("7", "app_launched", "2026-10-12 02:00:00.000000"),
                event("8", "app_launched", "2026-10-12 03:00:00.000000")));

        assertThat(result.eventCount()).isEqualTo(3);
        assertThat(result.skipped()).isEqualTo(3);
    }

    @Test
    void 켜기_가로모드_이벤트는_속성값_키를_함께_센다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "session_simple_mode_toggled", "2026-10-12 01:00:00.000000", "{\"on\":true}", "{}"),
                event("7", "session_simple_mode_toggled", "2026-10-12 01:01:00.000000", "{\"on\":false}", "{}"),
                event("7", "social_room_camera_toggled", "2026-10-12 01:02:00.000000", "{\"on\":true}", "{}"),
                event(
                        "7",
                        "session_orientation_changed",
                        "2026-10-12 01:03:00.000000",
                        "{\"orientation\":\"landscape\"}",
                        "{}"),
                event(
                        "7",
                        "session_orientation_changed",
                        "2026-10-12 01:04:00.000000",
                        "{\"orientation\":\"portrait\"}",
                        "{}")));

        assertThat(result.counts())
                .containsEntry(new DailyEventKey(7, OCT_12, "session_simple_mode_toggled"), 2)
                .containsEntry(new DailyEventKey(7, OCT_12, "session_simple_mode_toggled:on"), 1)
                .containsEntry(new DailyEventKey(7, OCT_12, "social_room_camera_toggled:on"), 1)
                .containsEntry(new DailyEventKey(7, OCT_12, "session_orientation_changed"), 2)
                .containsEntry(new DailyEventKey(7, OCT_12, "session_orientation_changed:landscape"), 1);
    }

    @Test
    void 광고_클릭_ID와_UTM은_EMPTY를_없음으로_본다() throws IOException {
        WindowAggregate result = parse(List.of(
                event(
                        "7",
                        "app_launched",
                        "2026-10-12 01:00:00.000000",
                        "{}",
                        "{\"initial_fbclid\":\"IwAR0abc\",\"initial_utm_source\":\"instagram\"}"),
                event(
                        "8",
                        "app_launched",
                        "2026-10-12 01:00:00.000000",
                        "{}",
                        "{\"initial_fbclid\":\"EMPTY\",\"initial_utm_source\":\"EMPTY\"}")));

        assertThat(result.attributes().get(7L))
                .returns(true, UserAttributeObservation::adClick)
                .returns("instagram", UserAttributeObservation::utmSource)
                .returns("ios", UserAttributeObservation::osName);
        assertThat(result.attributes().get(8L))
                .returns(false, UserAttributeObservation::adClick)
                .returns(null, UserAttributeObservation::utmSource);
    }

    @Test
    void 같은_유저의_속성은_늦게_본_값이_이기고_처음_마지막_시각을_남긴다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "app_launched", "2026-10-12 05:00:00.000000", "{}", "{\"initial_utm_source\":\"late\"}"),
                event("7", "app_launched", "2026-10-12 01:00:00.000000", "{}", "{\"initial_utm_source\":\"early\"}")));

        assertThat(result.attributes().get(7L))
                .returns("late", UserAttributeObservation::utmSource)
                .returns(Instant.parse("2026-10-12T01:00:00Z"), UserAttributeObservation::firstSeenAt)
                .returns(Instant.parse("2026-10-12T05:00:00Z"), UserAttributeObservation::lastSeenAt);
    }

    @Test
    void 여러_항목을_모두_읽고_gz가_아닌_항목은_무시한다() throws IOException {
        Map<String, List<String>> entries = new LinkedHashMap<>();
        entries.put("123/", List.of());
        entries.put("123/a#0.json.gz", List.of(event("7", "app_launched", "2026-10-12 01:00:00.000000")));
        entries.put("123/b#0.json.gz", List.of(event("7", "app_launched", "2026-10-12 02:00:00.000000")));
        entries.put("123/readme.txt", List.of("ignore me"));

        assertThat(parse(entries).counts()).containsEntry(new DailyEventKey(7, OCT_12, "app_launched"), 2);
    }

    @Test
    void zip이_아닌_본문이면_예외다() {
        // 200인데 zip이 아닌 본문(프록시 오류 페이지 등)을 0건으로 처리 기록하면 그날 행동이 영영 0이 된다
        byte[] html = "<html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> AmplitudeEventParser.parse(new ByteArrayInputStream(html)))
                .isInstanceOf(AmplitudeExportException.class);
    }

    @Test
    void 항목_경계에서_잘린_zip이면_일부만_세지_않고_예외다() {
        Map<String, List<String>> entries = new LinkedHashMap<>();
        entries.put("123/a#0.json.gz", List.of(event("7", "app_launched", "2026-10-12 01:00:00.000000")));
        entries.put("123/b#0.json.gz", List.of(event("7", "app_launched", "2026-10-12 02:00:00.000000")));
        byte[] whole = zip(entries);
        // 두 번째 항목의 로컬 헤더 직전에서 자른다 — 순차 읽기로는 정상 종료처럼 보이는 지점이다
        byte[] truncated = Arrays.copyOf(whole, secondLocalHeaderOffset(whole));

        assertThatThrownBy(() -> AmplitudeEventParser.parse(new ByteArrayInputStream(truncated)))
                .isInstanceOf(AmplitudeExportException.class);
    }

    @Test
    void gz_항목이_없는_zip이면_예외다() {
        // 데이터가 없는 기간은 Export가 404로 알린다 — 200인데 읽을 항목이 없으면 0건으로 기록하지 않는다
        byte[] noGz = zip(Map.of("123/readme.txt", List.of("x")));

        assertThatThrownBy(() -> AmplitudeEventParser.parse(new ByteArrayInputStream(noGz)))
                .isInstanceOf(AmplitudeExportException.class);
    }

    @Test
    void 깨진_줄이_적재한_줄보다_많으면_형식이_바뀐_것으로_보고_예외다() {
        List<String> lines = List.of(
                event("7", "app_launched", "2026-10-12T01:00:00Z"),
                event("7", "app_launched", "2026-10-12T02:00:00Z"),
                event("7", "app_launched", "2026-10-12 03:00:00.000000"));

        assertThatThrownBy(() -> parse(lines)).isInstanceOf(AmplitudeExportException.class);
    }

    @Test
    void 익명_이벤트가_많아도_형식_변경으로_보지_않는다() throws IOException {
        WindowAggregate result = parse(List.of(
                event(null, "invite_link_opened", "2026-10-12 01:00:00.000000"),
                event(null, "invite_link_opened", "2026-10-12 01:00:00.000000"),
                event(null, "store_link_redirected", "2026-10-12 01:00:00.000000"),
                event("7", "app_launched", "2026-10-12 01:00:00.000000")));

        assertThat(result.eventCount()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(3);
    }

    @Test
    void 널_문자는_지운다() throws IOException {
        String line = """
                {"user_id":"7","event_type":"app\\u0000_launched","event_time":"2026-10-12 01:00:00",\
                "os_name":"i\\u0000os","user_properties":{"initial_utm_source":"ins\\u0000ta"}}""";

        WindowAggregate result = parse(List.of(line));

        assertThat(result.counts()).containsOnlyKeys(new DailyEventKey(7, OCT_12, "app_launched"));
        assertThat(result.attributes().get(7L))
                .returns("insta", UserAttributeObservation::utmSource)
                .returns("ios", UserAttributeObservation::osName);
    }

    @Test
    void 속성은_값마다_가장_늦게_본_non_null이_이긴다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "app_launched", "2026-10-12 01:00:00.000000", "{}", "{\"initial_utm_source\":\"A\"}"),
                event("7", "app_launched", "2026-10-12 05:00:00.000000", "{}", "{}"),
                event("7", "app_launched", "2026-10-12 03:00:00.000000", "{}", "{\"initial_utm_source\":\"B\"}")));

        assertThat(result.attributes().get(7L))
                .returns("B", UserAttributeObservation::utmSource)
                .returns(Instant.parse("2026-10-12T03:00:00Z"), UserAttributeObservation::utmSeenAt)
                .returns(Instant.parse("2026-10-12T05:00:00Z"), UserAttributeObservation::lastSeenAt);
    }

    @Test
    void 같은_시각의_속성은_먼저_읽은_값을_둔다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "app_launched", "2026-10-12 01:00:00.000000", "{}", "{\"initial_utm_source\":\"A\"}"),
                event("7", "app_launched", "2026-10-12 01:00:00.000000", "{}", "{\"initial_utm_source\":\"B\"}")));

        assertThat(result.attributes().get(7L).utmSource()).isEqualTo("A");
    }

    @Test
    void 긴_UTM_소스와_OS는_컬럼_길이로_자른다() throws IOException {
        String longUtm = "u".repeat(250);
        String longOs = "o".repeat(80);
        String line = """
                {"user_id":"7","event_type":"app_launched","event_time":"2026-10-12 01:00:00","os_name":"%s",\
                "user_properties":{"initial_utm_source":"%s"}}""".formatted(longOs, longUtm);

        WindowAggregate result = parse(List.of(line));

        assertThat(result.attributes().get(7L))
                .returns("u".repeat(200), UserAttributeObservation::utmSource)
                .returns("o".repeat(50), UserAttributeObservation::osName);
    }

    private static int secondLocalHeaderOffset(byte[] zip) {
        int found = 0;
        for (int i = 0; i + 3 < zip.length; i++) {
            if (zip[i] == 'P' && zip[i + 1] == 'K' && zip[i + 2] == 3 && zip[i + 3] == 4 && ++found == 2) {
                return i;
            }
        }
        throw new IllegalStateException("두 번째 로컬 헤더가 없다");
    }

    @Test
    void event_time은_소수점이_없어도_읽는다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "app_launched", "2026-10-12 01:00:00"),
                event("7", "app_launched", "2026-10-12 01:00:00.123")));

        assertThat(result.counts()).containsEntry(new DailyEventKey(7, OCT_12, "app_launched"), 2);
    }

    @Test
    void 이벤트_키는_120자로_자른다() throws IOException {
        String longName = "x".repeat(130);

        WindowAggregate result = parse(List.of(event("7", longName, "2026-10-12 01:00:00.000000")));

        assertThat(result.counts()).containsOnlyKeys(new DailyEventKey(7, OCT_12, "x".repeat(120)));
    }
}
