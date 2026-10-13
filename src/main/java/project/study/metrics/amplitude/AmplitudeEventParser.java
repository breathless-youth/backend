package project.study.metrics.amplitude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Export 응답(zip 안에 시간별 gzip JSON 줄 파일)을 줄 단위로 읽어 {@link WindowAggregate}로 모은다. 원본은 메모리에 올리지
 * 않는다 — 임시 파일로 받아 한 줄씩 읽고 횟수만 남긴 뒤 파일을 지운다 (스펙 §7.3).
 */
public final class AmplitudeEventParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    // Export의 event_time은 UTC `yyyy-MM-dd HH:mm:ss.SSSSSS`. 소수점 자릿수가 달라도 읽는다
    private static final DateTimeFormatter EVENT_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();
    // 광고 클릭 ID가 하나라도 있으면 광고를 눌러 들어온 유저다. Amplitude는 값이 없으면 "EMPTY"를 넣는다
    private static final List<String> AD_CLICK_IDS = List.of(
            "initial_fbclid",
            "initial_gclid",
            "initial_gbraid",
            "initial_wbraid",
            "initial_dclid",
            "initial_msclkid",
            "initial_ttclid",
            "initial_twclid",
            "initial_li_fat_id",
            "initial_rdt_cid",
            "initial_ko_click_id");
    private static final String EMPTY = "EMPTY";
    private static final String NUL = "\0";
    // V28 컬럼 길이 — 넘으면 창 전체가 롤백되고 재시도마다 같은 값으로 실패하므로 자른다
    private static final int MAX_EVENT_KEY_LENGTH = 120;
    private static final int MAX_UTM_SOURCE_LENGTH = 200;
    private static final int MAX_OS_NAME_LENGTH = 50;

    private AmplitudeEventParser() {}

    public static WindowAggregate parse(InputStream body) throws IOException {
        // 응답을 임시 파일로 받은 뒤 ZipFile로 연다. ZipFile은 끝의 중앙 디렉터리를 읽어야 열리므로, 순차 읽기
        // (ZipInputStream)로는 정상 종료처럼 보이는 "항목 경계에서 잘린 zip"도 실패로 드러난다
        Path spool = Files.createTempFile("amplitude-export-", ".zip");
        try {
            Files.copy(body, spool, StandardCopyOption.REPLACE_EXISTING);
            return parseZip(spool);
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    private static WindowAggregate parseZip(Path spool) throws IOException {
        WindowAggregate aggregate = WindowAggregate.empty();
        int gzEntries = 0;
        try (ZipFile zip = open(spool)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.getName().endsWith(".gz")) {
                    gzEntries++;
                    readLines(zip, entry, aggregate);
                }
            }
        }
        // 데이터가 없는 기간은 Export가 404로 알린다. 200인데 읽을 것이 없거나 대부분 못 읽었으면 0건으로 기록하지 않고
        // 실패시킨다 — 처리 기록이 남은 창은 다시 받지 않아, 리포트가 그날 행동을 영영 0으로 센다 (ADR-0030 결정 6)
        if (gzEntries == 0) {
            throw new AmplitudeExportException("Amplitude Export zip에 읽을 .gz 항목이 없다");
        }
        if (aggregate.malformed() > aggregate.eventCount()) {
            throw new AmplitudeExportException("Amplitude Export 줄 형식이 바뀐 것 같다 (깨진 줄 %d, 적재 %d)"
                    .formatted(aggregate.malformed(), aggregate.eventCount()));
        }
        return aggregate;
    }

    private static ZipFile open(Path spool) {
        try {
            return new ZipFile(spool.toFile());
        } catch (IOException e) {
            throw new AmplitudeExportException("Amplitude Export 응답이 온전한 zip이 아니다");
        }
    }

    private static void readLines(ZipFile zip, ZipEntry entry, WindowAggregate aggregate) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(zip.getInputStream(entry)), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank()) {
                    addLine(line, aggregate);
                }
            }
        }
    }

    private static void addLine(String line, WindowAggregate aggregate) {
        JsonNode event;
        try {
            event = JSON.readTree(line);
        } catch (JacksonException e) {
            aggregate.skipMalformed();
            return;
        }
        JsonNode userNode = event.path("user_id");
        if (userNode.isMissingNode() || userNode.isNull()) {
            aggregate.skipAnonymous();
            return;
        }
        Long userId = userId(userNode);
        String eventType = text(event, "event_type");
        Instant eventTime = eventTime(text(event, "event_time"));
        if (userId == null || eventType == null || eventTime == null) {
            aggregate.skipMalformed();
            return;
        }
        aggregate.addEvent(
                userId,
                LocalDate.ofInstant(eventTime, ExportWindow.KST),
                eventKeys(eventType, event.path("event_properties")),
                attribute(event, eventTime));
    }

    /** event_type과, 켜기·가로 모드처럼 값이 의미를 가르는 이벤트는 "event_type:값" 키를 함께 센다 (스펙 §7.3). */
    private static List<String> eventKeys(String eventType, JsonNode properties) {
        String type = truncate(eventType, MAX_EVENT_KEY_LENGTH);
        String derived =
                switch (eventType) {
                    case "session_simple_mode_toggled", "social_room_camera_toggled" ->
                        isTrue(properties.path("on")) ? eventType + ":on" : null;
                    case "session_orientation_changed" ->
                        "landscape".equals(text(properties, "orientation")) ? eventType + ":landscape" : null;
                    default -> null;
                };
        return derived == null ? List.of(type) : List.of(type, derived);
    }

    private static UserAttributeObservation attribute(JsonNode event, Instant eventTime) {
        JsonNode userProperties = event.path("user_properties");
        boolean adClick = AD_CLICK_IDS.stream().anyMatch(key -> present(text(userProperties, key)));
        String utmSource = text(userProperties, "initial_utm_source");
        // 앱이 웹뷰라 웹 SDK의 os_name은 브라우저("Mobile Safari" 등)다 — 운영체제는 device_family(iOS·Android)에 있다
        String deviceFamily = text(event, "device_family");
        String osName = present(deviceFamily) ? deviceFamily : text(event, "os_name");
        return UserAttributeObservation.observed(
                eventTime,
                present(utmSource) ? truncate(utmSource, MAX_UTM_SOURCE_LENGTH) : null,
                adClick,
                osName == null ? null : truncate(osName, MAX_OS_NAME_LENGTH));
    }

    private static Long userId(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        if (!node.isString()) {
            return null;
        }
        try {
            return Long.parseLong(node.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant eventTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, EVENT_TIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // 널 문자는 지운다 — PostgreSQL 문자열에 들어갈 수 없어 창 전체가 롤백되고 재시도마다 실패한다
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString().replace(NUL, "") : null;
    }

    private static boolean isTrue(JsonNode node) {
        return node.isBoolean() && node.booleanValue();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank() && !EMPTY.equals(value);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
