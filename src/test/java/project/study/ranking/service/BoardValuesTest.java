package project.study.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;

import org.junit.jupiter.api.Test;

class BoardValuesTest {

    @Test
    void 집중률_차이는_화면에_보이는_두_값의_차이와_같다() {
        // 91.66 -> 91.7, 87.94 -> 87.9 : 원값 차이를 반올림한 3.7이 아니라 보이는 값의 차이 3.8이다
        assertThat(BoardValues.gap(FOCUS_RATE, 91.66, 87.94)).isEqualTo(3.8);
        assertThat(BoardValues.gap(FOCUS_RATE, 87.94, 91.66)).isEqualTo(3.8);
        assertThat(BoardValues.gap(FOCUS_RATE, 90.04, 90.0)).isEqualTo(0.0);
    }

    @Test
    void 시간_판_차이는_정수_초다() {
        assertThat(BoardValues.gap(FOCUS_TIME, 3600.0, 3000.0)).isEqualTo(600L);
        assertThat(BoardValues.gap(FOCUS_TIME, 3000.0, 3600.0)).isEqualTo(600L);
    }
}
