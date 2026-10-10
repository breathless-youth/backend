package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import project.study.common.exception.BadRequestException;
import project.study.ranking.dto.RecordCursor;

class RecordCursorTest {

    @Test
    void 인코딩한_커서를_되돌린다() {
        RecordCursor cursor = new RecordCursor(Instant.parse("2026-10-09T15:00:00Z"), 31);

        assertThat(RecordCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    // 깨진 base64, "nope", "123", "a:b", "9223372036854775807:1"·"9999999999999:1"(범위 밖 시각)
    @ParameterizedTest
    @ValueSource(strings = {"!!!", "bm9wZQ", "MTIz", "YTpi", "OTIyMzM3MjAzNjg1NDc3NTgwNzox", "OTk5OTk5OTk5OTk5OTox"})
    void 형식이_틀리면_400이다(String value) {
        assertThatThrownBy(() -> RecordCursor.decode(value)).isInstanceOf(BadRequestException.class);
    }
}
