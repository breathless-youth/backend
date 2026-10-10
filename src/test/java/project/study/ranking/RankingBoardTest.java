package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingBoardType.TOTAL_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.MONTHLY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import org.junit.jupiter.api.Test;
import project.study.common.exception.BadRequestException;
import project.study.studysession.entity.TimeSlot;

class RankingBoardTest {

    @Test
    void 기간_판은_지원하는_기간만_받는다() {
        assertThatThrownBy(() -> RankingBoard.of(FOCUS_RATE, DAILY, null, 0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(TIME_SLOT, MONTHLY, TimeSlot.MORNING, 0))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(FOCUS_TIME, null, null, 0)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 명예의_전당은_기간_구간_직전_기간을_받지_않는다() {
        assertThatThrownBy(() -> RankingBoard.of(TOTAL_TIME, WEEKLY, null, 0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(TOTAL_TIME, null, TimeSlot.DAWN, 0))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(TOTAL_TIME, null, null, -1)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 구간은_시간대_판에만_준다() {
        assertThatThrownBy(() -> RankingBoard.of(FOCUS_TIME, WEEKLY, TimeSlot.MORNING, 0))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(TIME_SLOT, DAILY, null, 0)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void offset은_0과_마이너스1만_된다() {
        assertThatThrownBy(() -> RankingBoard.of(FOCUS_TIME, WEEKLY, null, -2)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(FOCUS_TIME, WEEKLY, null, 1)).isInstanceOf(BadRequestException.class);
        assertThat(RankingBoard.of(FOCUS_TIME, WEEKLY, null, -1)).isEqualTo(new RankingBoard(FOCUS_TIME, WEEKLY, null));
    }

    @Test
    void 키는_종목_기간_구간을_잇는다() {
        assertThat(new RankingBoard(FOCUS_TIME, WEEKLY, null).key()).isEqualTo("FOCUS_TIME:WEEKLY");
        assertThat(new RankingBoard(TIME_SLOT, DAILY, TimeSlot.NIGHT).key()).isEqualTo("TIME_SLOT:DAILY:NIGHT");
        assertThat(new RankingBoard(TOTAL_TIME, null, null).key()).isEqualTo("TOTAL_TIME");
    }
}
