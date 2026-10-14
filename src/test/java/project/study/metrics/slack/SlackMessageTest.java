package project.study.metrics.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static project.study.metrics.slack.SlackMessage.bold;

import java.util.List;
import org.junit.jupiter.api.Test;

class SlackMessageTest {

    @Test
    void 대체_글은_제목_아래에_들여쓴_목록과_인용을_쓰고_굵은_글은_별표로_감싼다() {
        SlackMessage message = SlackMessage.builder("📊 10/12(월) 일일 지표")
                .heading("가입")
                .item("가입자 수 = ", bold(38), "명")
                .item("다시 와서 공부한 비율")
                .sub(bold("10%"), " (가입 다음 날)")
                .quote("지난주 11%")
                .blank()
                .text("끝")
                .build();

        assertThat(message.text()).isEqualTo("""
                        📊 10/12(월) 일일 지표
                        *가입*
                        • 가입자 수 = *38*명
                        • 다시 와서 공부한 비율
                            • *10%* (가입 다음 날)
                        > 지난주 11%

                        끝""");
    }

    @Test
    void 번호_목록은_하위_항목을_건너뛰어_이어지고_다른_줄이_끼면_1부터_다시_센다() {
        SlackMessage message = SlackMessage.builder("제목")
                .numbered("첫째")
                .sub("설명")
                .numbered("둘째")
                .item("끊김")
                .numbered("다시 첫째")
                .item("상위")
                .subNumbered("하위 첫째")
                .subNumbered("하위 둘째")
                .build();

        assertThat(message.text().lines())
                .containsExactly(
                        "제목", "1. 첫째", "    • 설명", "2. 둘째", "• 끊김", "1. 다시 첫째", "• 상위", "    1. 하위 첫째", "    2. 하위 둘째");
    }

    @Test
    void 빈_항목은_만들_수_없다() {
        assertThatThrownBy(() -> SlackMessage.builder("제목").item("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlackMessage.builder(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 상한을_넘으면_뒤쪽_줄을_버리고_잘렸다고_쓴다() {
        SlackMessage.Builder builder = SlackMessage.builder("제목");
        for (int i = 0; i < 100; i++) {
            builder.item("항목 " + i);
        }
        SlackMessage message = builder.build();

        SlackMessage capped = message.cap(200);

        assertThat(capped.text().length()).isLessThanOrEqualTo(200);
        assertThat(capped.text()).startsWith("제목\n• 항목 0\n").endsWith("\n…(길이 상한으로 잘림)");
        assertThat(message.cap(10_000)).isSameAs(message);
    }

    @Test
    void 길면_맨_위_항목_경계에서_나누고_하위_항목은_떼지_않는다() {
        SlackMessage.Builder builder = SlackMessage.builder("🙋 명단");
        for (int i = 0; i < 30; i++) {
            builder.item("사람 " + i).sub("공부 = " + "가".repeat(20)).sub("습관 = " + "나".repeat(20));
        }
        SlackMessage message = builder.build();

        List<SlackMessage> parts = message.split(400);

        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts).allSatisfy(part -> {
            assertThat(part.text().length()).isLessThanOrEqualTo(400);
            assertThat(part.lines().getFirst()).isInstanceOf(SlackMessage.Item.class);
            assertThat(part.lines().getLast()).isNotInstanceOfAny(SlackMessage.Blank.class);
        });
        assertThat(parts.getFirst().title()).isEqualTo("🙋 명단");
        assertThat(parts.subList(1, parts.size()))
                .extracting(SlackMessage::title)
                .containsOnly("🙋 명단 (이어서)");
        // 하위 항목이 앞 사람과 떨어져 다음 메시지 첫 줄이 되지 않는다
        assertThat(parts)
                .allSatisfy(part -> assertThat(((SlackMessage.Item) part.lines().getFirst()).indent())
                        .isZero());
        assertThat(parts.stream().mapToInt(part -> part.lines().size()).sum()).isEqualTo(90);
    }

    @Test
    void 짧으면_나누지_않고_한_묶음이_혼자_넘으면_그_안에서_자른다() {
        SlackMessage small = SlackMessage.builder("제목").item("a").build();
        SlackMessage.Builder huge = SlackMessage.builder("제목").item("한 사람");
        for (int i = 0; i < 50; i++) {
            huge.sub("하위 " + i);
        }

        assertThat(small.split(400)).containsExactly(small);
        assertThat(huge.build().split(200))
                .singleElement()
                .satisfies(part -> assertThat(part.text()).endsWith("…(길이 상한으로 잘림)"));
    }
}
