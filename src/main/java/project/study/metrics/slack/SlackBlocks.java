package project.study.metrics.slack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import project.study.metrics.slack.SlackMessage.Blank;
import project.study.metrics.slack.SlackMessage.Item;
import project.study.metrics.slack.SlackMessage.Line;
import project.study.metrics.slack.SlackMessage.Quote;
import project.study.metrics.slack.SlackMessage.Span;
import project.study.metrics.slack.SlackMessage.Text;

/**
 * {@link SlackMessage} → Block Kit 블록. 제목은 header 블록, 본문은 rich_text 블록 하나다.
 *
 * <p>같은 깊이·같은 종류로 이어진 항목은 rich_text_list 하나로 묶고, 하위 항목에 끊긴 번호 목록은 offset으로 번호를 잇는다(대체 글과 같은
 * 번호). 이어진 문단·빈 줄은 줄바꿈으로 이은 rich_text_section 하나가 된다.
 */
final class SlackBlocks {

    // header 블록 plain_text 상한
    static final int MAX_TITLE = 150;

    private SlackBlocks() {}

    static List<Map<String, Object>> of(SlackMessage message) {
        if (message.lines().isEmpty()) {
            // 요소가 없는 rich_text는 Slack이 invalid_blocks로 거절한다
            return List.of(header(message.title()));
        }
        return List.of(header(message.title()), map("type", "rich_text", "elements", elements(message.lines())));
    }

    private static Map<String, Object> header(String title) {
        String text = title.codePointCount(0, title.length()) <= MAX_TITLE
                ? title
                : title.substring(0, title.offsetByCodePoints(0, MAX_TITLE));
        return map("type", "header", "text", map("type", "plain_text", "text", text, "emoji", true));
    }

    private static List<Map<String, Object>> elements(List<Line> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        int[] numbers = new int[SlackMessage.MAX_INDENT + 1];
        int start = 0;
        while (start < lines.size()) {
            int end = runEnd(lines, start);
            List<Line> run = lines.subList(start, end);
            if (run.getFirst() instanceof Item) {
                out.add(list(run, numbers));
            } else {
                SlackMessage.reset(numbers, 0);
                out.add(run.getFirst() instanceof Quote ? quote(run) : section(run));
            }
            start = end;
        }
        return out;
    }

    // 같은 블록 요소로 묶을 줄의 끝(제외) — 같은 깊이·종류의 항목, 이어진 인용, 이어진 문단·빈 줄
    private static int runEnd(List<Line> lines, int start) {
        Line first = lines.get(start);
        int end = start + 1;
        while (end < lines.size() && sameRun(first, lines.get(end))) {
            end++;
        }
        return end;
    }

    private static boolean sameRun(Line first, Line next) {
        return switch (first) {
            case Item item ->
                next instanceof Item other && other.indent() == item.indent() && other.ordered() == item.ordered();
            case Quote quote -> next instanceof Quote;
            case Text text -> next instanceof Text || next instanceof Blank;
            case Blank blank -> next instanceof Text || next instanceof Blank;
        };
    }

    private static Map<String, Object> list(List<Line> run, int[] numbers) {
        Item first = (Item) run.getFirst();
        List<Map<String, Object>> sections = new ArrayList<>();
        int firstNumber = 0;
        for (Line line : run) {
            Item item = (Item) line;
            int number = SlackMessage.number(numbers, item);
            if (sections.isEmpty()) {
                firstNumber = number;
            }
            sections.add(itemSection(item.spans()));
        }
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("type", "rich_text_list");
        list.put("style", first.ordered() ? "ordered" : "bullet");
        list.put("indent", first.indent());
        if (firstNumber > 1) {
            list.put("offset", firstNumber - 1);
        }
        list.put("elements", sections);
        return list;
    }

    private static Map<String, Object> quote(List<Line> run) {
        return map("type", "rich_text_quote", "elements", joined(run));
    }

    private static Map<String, Object> section(List<Line> run) {
        List<Map<String, Object>> elements = joined(run);
        return map("type", "rich_text_section", "elements", elements.isEmpty() ? List.of(text("\n")) : elements);
    }

    private static Map<String, Object> itemSection(List<Span> spans) {
        return map(
                "type",
                "rich_text_section",
                "elements",
                spans.stream().map(SlackBlocks::span).toList());
    }

    // 줄 사이에 줄바꿈 글자를 끼운다 — 빈 줄은 조각이 없어 줄바꿈만 남는다
    private static List<Map<String, Object>> joined(List<Line> run) {
        List<Map<String, Object>> elements = new ArrayList<>();
        for (int i = 0; i < run.size(); i++) {
            spans(run.get(i)).forEach(span -> elements.add(span(span)));
            if (i < run.size() - 1) {
                elements.add(text("\n"));
            }
        }
        return elements;
    }

    private static List<Span> spans(Line line) {
        return switch (line) {
            case Text text -> text.spans();
            case Quote quote -> quote.spans();
            case Item item -> item.spans();
            case Blank blank -> List.of();
        };
    }

    private static Map<String, Object> span(Span span) {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("type", "text");
        element.put("text", span.text());
        if (span.bold()) {
            element.put("style", Map.of("bold", true));
        }
        return element;
    }

    private static Map<String, Object> text(String text) {
        return span(new Span(text, false));
    }

    // JSON 키 순서를 고정한다 — Slack은 순서와 무관하지만 요청 본문을 읽고 테스트하기 쉽게
    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
