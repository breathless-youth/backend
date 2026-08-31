package project.study.metrics.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RoomVsSingleFocusTest {

    @Test
    void 소셜과_싱글을_나눠_건수_평균_중앙값을_낸다() {
        List<QualifyingSession> sessions = List.of(
                new QualifyingSession(1L, 600, true), // 소셜 10분
                new QualifyingSession(2L, 1800, true), // 소셜 30분
                new QualifyingSession(3L, 1200, false)); // 싱글 20분

        RoomVsSingleFocus result = RoomVsSingleFocus.from(sessions);

        assertThat(result.social().sessionCount()).isEqualTo(2);
        assertThat(result.social().avgFocusMin()).isEqualTo(20); // (600+1800)/2 = 1200초 = 20분
        assertThat(result.social().medianFocusMin()).isEqualTo(20); // (600+1800)/2 = 1200초 = 20분
        assertThat(result.single().sessionCount()).isEqualTo(1);
        assertThat(result.single().avgFocusMin()).isEqualTo(20);
        assertThat(result.single().medianFocusMin()).isEqualTo(20);
    }

    @Test
    void 홀수_개면_가운데_값이_중앙값이다() {
        List<QualifyingSession> sessions = List.of(
                new QualifyingSession(1L, 600, true), // 10분
                new QualifyingSession(2L, 1200, true), // 20분
                new QualifyingSession(3L, 3000, true)); // 50분

        RoomVsSingleFocus result = RoomVsSingleFocus.from(sessions);

        assertThat(result.social().medianFocusMin()).isEqualTo(20); // 가운데 1200초 = 20분
        assertThat(result.social().avgFocusMin()).isEqualTo(27); // (600+1200+3000)/3 = 1600초 ≈ 26.67분 → 반올림 27
    }

    @Test
    void 한쪽_무리가_비면_건수와_평균_중앙값이_0이다() {
        List<QualifyingSession> onlySocial = List.of(new QualifyingSession(1L, 600, true));

        RoomVsSingleFocus result = RoomVsSingleFocus.from(onlySocial);

        assertThat(result.single().sessionCount()).isZero();
        assertThat(result.single().avgFocusMin()).isZero();
        assertThat(result.single().medianFocusMin()).isZero();
    }
}
