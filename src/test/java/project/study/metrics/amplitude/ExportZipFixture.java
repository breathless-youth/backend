package project.study.metrics.amplitude;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Export 응답과 같은 모양(zip 안에 gzip JSON 줄 파일)을 만든다. 항목 순서는 넘긴 Map 순서. */
final class ExportZipFixture {

    private ExportZipFixture() {}

    static byte[] zip(Map<String, List<String>> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, List<String>> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getKey().endsWith(".gz") ? gzip(entry.getValue()) : join(entry.getValue()));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static String event(String userId, String eventType, String eventTime, String props, String userProps) {
        String user = userId == null ? "null" : "\"" + userId + "\"";
        return """
                {"user_id":%s,"event_type":"%s","event_time":"%s","os_name":"ios","event_properties":%s,\
                "user_properties":%s}""".formatted(user, eventType, eventTime, props, userProps);
    }

    static String event(String userId, String eventType, String eventTime) {
        return event(userId, eventType, eventTime, "{}", "{}");
    }

    private static byte[] gzip(List<String> lines) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(join(lines));
        }
        return bytes.toByteArray();
    }

    private static byte[] join(List<String> lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }
}
