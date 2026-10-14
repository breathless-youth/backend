package project.study.metrics.slack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Slack에 보낼 메시지 — 제목 하나와 들여쓴 줄들 (BY-910). 발송기가 Block Kit {@code rich_text}로 바꿔 Slack 기본 목록으로 보이게 한다.
 *
 * <p>들여쓰기는 두 단계(항목·하위 항목)까지만 둔다. 글자는 Slack이 해석하지 않는 rich_text 텍스트로 나가서 목표 같은 사용자 입력이
 * 멘션·링크가 되지 않는다.
 */
public record SlackMessage(String title, List<Line> lines) {

    static final int MAX_INDENT = 1;
    private static final String INDENT = "    ";
    private static final String CAPPED = "…(길이 상한으로 잘림)";
    private static final String CONTINUED = " (이어서)";

    public SlackMessage {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Slack 메시지 제목이 비었다");
        }
        lines = List.copyOf(lines);
    }

    /** 한 줄 안의 글 조각 — 굵게 쓸 숫자만 따로 나눈다. */
    public record Span(String text, boolean bold) {}

    public sealed interface Line permits Text, Item, Quote, Blank {}

    /** 묶음 제목 같은 문단 한 줄. */
    public record Text(List<Span> spans) implements Line {}

    /** 목록 항목 — indent 0이 항목, 1이 하위 항목. */
    public record Item(int indent, boolean ordered, List<Span> spans) implements Line {}

    public record Quote(List<Span> spans) implements Line {}

    public record Blank() implements Line {}

    public static Span bold(Object value) {
        return new Span(String.valueOf(value), true);
    }

    /** 줄만 따로 그린다 — 그리다 실패하면 통째로 버릴 수 있게(요약의 항목 묶음). */
    public static List<Line> lines(Consumer<Builder> writer) {
        Builder builder = new Builder("lines");
        writer.accept(builder);
        return List.copyOf(builder.lines);
    }

    public static Builder builder(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Slack 메시지 제목이 비었다");
        }
        return new Builder(title);
    }

    /**
     * 알림·검색에 쓰는 대체 글(mrkdwn). 하위 항목은 공백 네 칸, 굵은 글은 별표로 감싼다. 번호 목록은 하위 항목을 건너뛰어 이어지고,
     * 같은 깊이에 다른 줄이 끼면 1부터 다시 센다.
     */
    public String text() {
        List<String> out = new ArrayList<>();
        out.add(title);
        int[] numbers = new int[MAX_INDENT + 1];
        for (Line line : lines) {
            out.add(textLine(line, numbers));
        }
        return String.join("\n", out);
    }

    /** 대체 글이 maxChars를 넘으면 뒤쪽 줄을 버리고 잘렸다고 끝에 쓴다. */
    public SlackMessage cap(int maxChars) {
        if (text().length() <= maxChars) {
            return this;
        }
        List<Line> kept = new ArrayList<>(lines);
        Line notice = new Text(List.of(new Span(CAPPED, false)));
        while (!kept.isEmpty()
                && new SlackMessage(title, append(kept, notice)).text().length() > maxChars) {
            kept.removeLast();
        }
        return new SlackMessage(title, append(kept, notice));
    }

    /**
     * 대체 글이 maxChars를 넘으면 맨 위 줄(항목·문단·인용) 경계에서 여러 메시지로 나눈다. 하위 항목은 위 항목과 떼지 않고, 뒤
     * 메시지 제목에는 "(이어서)"를 붙인다. 한 묶음이 혼자 넘으면 그 메시지 안에서 자른다.
     */
    public List<SlackMessage> split(int maxChars) {
        if (text().length() <= maxChars) {
            return List.of(this);
        }
        List<SlackMessage> parts = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        for (List<Line> group : groups()) {
            List<Line> candidate = append(current, group);
            if (!current.isEmpty() && part(parts.size(), candidate).text().length() > maxChars) {
                parts.add(part(parts.size(), current).cap(maxChars));
                current = new ArrayList<>();
                candidate = new ArrayList<>(group);
            }
            current = candidate;
        }
        if (!current.isEmpty()) {
            parts.add(part(parts.size(), current).cap(maxChars));
        }
        return parts;
    }

    // 맨 위 줄 하나와 그 아래 하위 항목들
    private List<List<Line>> groups() {
        List<List<Line>> groups = new ArrayList<>();
        for (Line line : lines) {
            if (line instanceof Item item && item.indent() > 0 && !groups.isEmpty()) {
                groups.getLast().add(line);
            } else {
                groups.add(new ArrayList<>(List.of(line)));
            }
        }
        return groups;
    }

    // 나눈 조각 — 앞뒤 빈 줄은 메시지 경계에서 의미가 없어 버린다
    private SlackMessage part(int index, List<Line> lines) {
        List<Line> trimmed = new ArrayList<>(lines);
        while (!trimmed.isEmpty() && trimmed.getFirst() instanceof Blank) {
            trimmed.removeFirst();
        }
        while (!trimmed.isEmpty() && trimmed.getLast() instanceof Blank) {
            trimmed.removeLast();
        }
        return new SlackMessage(index == 0 ? title : title + CONTINUED, trimmed);
    }

    private static List<Line> append(List<Line> lines, List<Line> more) {
        List<Line> joined = new ArrayList<>(lines);
        joined.addAll(more);
        return joined;
    }

    private static List<Line> append(List<Line> lines, Line last) {
        List<Line> joined = new ArrayList<>(lines);
        joined.add(last);
        return joined;
    }

    private static String textLine(Line line, int[] numbers) {
        return switch (line) {
            case Item item -> {
                int number = number(numbers, item);
                yield INDENT.repeat(item.indent()) + (item.ordered() ? number + ". " : "• ") + join(item.spans());
            }
            case Text text -> {
                reset(numbers, 0);
                yield join(text.spans());
            }
            case Quote quote -> {
                reset(numbers, 0);
                yield "> " + join(quote.spans());
            }
            case Blank blank -> {
                reset(numbers, 0);
                yield "";
            }
        };
    }

    /** 번호 목록이면 이번 번호, 글머리표면 0. 더 깊은 목록은 끝난 것으로 보고, 글머리표는 같은 깊이의 번호를 끊는다. */
    static int number(int[] numbers, Item item) {
        reset(numbers, item.indent() + 1);
        if (!item.ordered()) {
            numbers[item.indent()] = 0;
            return 0;
        }
        return ++numbers[item.indent()];
    }

    static void reset(int[] numbers, int fromIndent) {
        for (int i = fromIndent; i < numbers.length; i++) {
            numbers[i] = 0;
        }
    }

    private static String join(List<Span> spans) {
        return spans.stream()
                .map(span -> span.bold() ? "*" + span.text() + "*" : span.text())
                .collect(Collectors.joining());
    }

    public static final class Builder {

        private final String title;
        private final List<Line> lines = new ArrayList<>();

        private Builder(String title) {
            this.title = title;
        }

        /** 문단 한 줄. 조각은 문자열·숫자(그대로)와 {@link SlackMessage#bold}(굵게)다. */
        public Builder text(Object... parts) {
            lines.add(new Text(spans(parts)));
            return this;
        }

        /** 굵은 묶음 제목. */
        public Builder heading(String text) {
            return text(bold(text));
        }

        public Builder item(Object... parts) {
            return add(0, false, parts);
        }

        public Builder sub(Object... parts) {
            return add(1, false, parts);
        }

        public Builder numbered(Object... parts) {
            return add(0, true, parts);
        }

        public Builder subNumbered(Object... parts) {
            return add(1, true, parts);
        }

        public Builder quote(Object... parts) {
            lines.add(new Quote(spans(parts)));
            return this;
        }

        public Builder blank() {
            lines.add(new Blank());
            return this;
        }

        public Builder addAll(List<Line> more) {
            lines.addAll(more);
            return this;
        }

        public SlackMessage build() {
            return new SlackMessage(title, lines);
        }

        private Builder add(int indent, boolean ordered, Object... parts) {
            lines.add(new Item(indent, ordered, spans(parts)));
            return this;
        }

        // 빈 조각은 Slack이 거절하므로 버리고, 남는 게 없으면 렌더링 버그다
        private static List<Span> spans(Object... parts) {
            List<Span> spans = new ArrayList<>();
            for (Object part : parts) {
                Span span = part instanceof Span given ? given : new Span(String.valueOf(part), false);
                if (!span.text().isEmpty()) {
                    spans.add(span);
                }
            }
            if (spans.isEmpty()) {
                throw new IllegalArgumentException("Slack 메시지에 빈 줄을 항목으로 넣을 수 없다 — blank()를 쓴다");
            }
            return List.copyOf(spans);
        }
    }
}
