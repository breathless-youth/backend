package project.study.metrics.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.slack.SlackMessage.bold;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackBlocksTest {

    private static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> boldText(String text) {
        return Map.of("type", "text", "text", text, "style", Map.of("bold", true));
    }

    private static Map<String, Object> section(Object... elements) {
        return Map.of("type", "rich_text_section", "elements", List.of(elements));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> richElements(List<Map<String, Object>> blocks) {
        return (List<Map<String, Object>>) blocks.get(1).get("elements");
    }

    @Test
    void 제목은_헤더_블록이고_본문은_rich_text_블록_하나다() {
        List<Map<String, Object>> blocks =
                SlackBlocks.of(SlackMessage.builder("📊 일일 지표").item("a").build());

        assertThat(blocks).hasSize(2);
        assertThat(blocks.getFirst())
                .isEqualTo(Map.of(
                        "type", "header", "text", Map.of("type", "plain_text", "text", "📊 일일 지표", "emoji", true)));
        assertThat(blocks.get(1)).containsEntry("type", "rich_text");
    }

    @Test
    void 같은_깊이의_항목은_목록_하나로_묶고_깊이가_바뀌면_목록을_나눈다() {
        SlackMessage message = SlackMessage.builder("제목")
                .item("가입자 수 = ", bold(38), "명")
                .item("비율")
                .sub(bold("10%"), " (다음 날)")
                .sub(bold("6%"), " (7~13일 뒤)")
                .item("끝")
                .build();

        assertThat(richElements(SlackBlocks.of(message)))
                .containsExactly(
                        Map.of(
                                "type",
                                "rich_text_list",
                                "style",
                                "bullet",
                                "indent",
                                0,
                                "elements",
                                List.of(section(text("가입자 수 = "), boldText("38"), text("명")), section(text("비율")))),
                        Map.of(
                                "type",
                                "rich_text_list",
                                "style",
                                "bullet",
                                "indent",
                                1,
                                "elements",
                                List.of(
                                        section(boldText("10%"), text(" (다음 날)")),
                                        section(boldText("6%"), text(" (7~13일 뒤)")))),
                        Map.of(
                                "type",
                                "rich_text_list",
                                "style",
                                "bullet",
                                "indent",
                                0,
                                "elements",
                                List.of(section(text("끝")))));
    }

    @Test
    void 끊겼다_이어지는_번호_목록은_offset으로_번호를_잇는다() {
        SlackMessage message = SlackMessage.builder("제목")
                .numbered("첫째")
                .sub("설명")
                .numbered("둘째")
                .build();

        List<Map<String, Object>> elements = richElements(SlackBlocks.of(message));

        assertThat(elements.get(0)).containsEntry("style", "ordered").doesNotContainKey("offset");
        assertThat(elements.get(2))
                .containsEntry("style", "ordered")
                .containsEntry("indent", 0)
                .containsEntry("offset", 1);
    }

    @Test
    void 이어진_문단과_빈_줄은_줄바꿈으로_한_문단이_되고_인용은_따로_간다() {
        SlackMessage message = SlackMessage.builder("제목")
                .quote("지난주 11%")
                .blank()
                .heading("✅ 남은 8명")
                .item("곰")
                .build();

        assertThat(richElements(SlackBlocks.of(message)))
                .containsExactly(
                        Map.of("type", "rich_text_quote", "elements", List.of(text("지난주 11%"))),
                        section(text("\n"), boldText("✅ 남은 8명")),
                        Map.of(
                                "type",
                                "rich_text_list",
                                "style",
                                "bullet",
                                "indent",
                                0,
                                "elements",
                                List.of(section(text("곰")))));
    }

    @Test
    void 글자는_이스케이프하지_않고_그대로_보낸다() {
        SlackMessage message =
                SlackMessage.builder("제목").item("목표 <!channel> & <합격>").build();

        // rich_text 텍스트는 Slack이 멘션·링크로 해석하지 않는다 — 이스케이프하면 &lt;가 글자로 보인다
        assertThat(richElements(SlackBlocks.of(message)).getFirst().toString()).contains("목표 <!channel> & <합격>");
    }

    @Test
    void 제목은_헤더_한도_150자로_자른다() {
        List<Map<String, Object>> blocks =
                SlackBlocks.of(SlackMessage.builder("가".repeat(200)).item("a").build());

        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) blocks.getFirst().get("text");
        assertThat((String) header.get("text")).hasSize(150);
    }
}
