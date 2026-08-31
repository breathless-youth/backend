package project.study.metrics.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.dto.UserSessionDetail.SessionEntry;

class UserSessionDetailTest {

    @Test
    void 유저별로_묶고_순공_합_내림차순으로_정렬한다() {
        List<QualifyingSession> sessions = List.of(
                new QualifyingSession(9L, 5280, false), // 88분
                new QualifyingSession(12L, 1800, false), // 30분
                new QualifyingSession(7L, 7080, false), // 118분
                new QualifyingSession(12L, 8520, true), // 142분
                new QualifyingSession(9L, 1320, true)); // 22분

        List<UserSessionDetail> result = UserSessionDetail.rank(sessions);

        // 합: 12 = 172분, 7 = 118분, 9 = 110분
        assertThat(result).extracting(UserSessionDetail::userId).containsExactly(12L, 7L, 9L);
    }

    @Test
    void 한_유저의_세션은_순공_내림차순이고_소셜여부를_유지한다() {
        List<QualifyingSession> sessions = List.of(
                new QualifyingSession(12L, 1800, false), // 30분 싱글
                new QualifyingSession(12L, 8520, true)); // 142분 소셜

        List<UserSessionDetail> result = UserSessionDetail.rank(sessions);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sessions()).containsExactly(new SessionEntry(142, true), new SessionEntry(30, false));
    }

    @Test
    void 랭킹은_반올림_분이_아니라_초_단위_순공_합으로_정한다() {
        // 반올림 분 합으로 정렬하면 순서가 뒤바뀌는 경계 케이스(codex P2)
        List<QualifyingSession> sessions = List.of(
                new QualifyingSession(1L, 629, false), // 10분29초
                new QualifyingSession(1L, 629, false), // 합 1258초(분 합 20, 실제 20.97분)
                new QualifyingSession(2L, 1231, false)); // 20분31초 = 1231초(반올림 21분)

        List<UserSessionDetail> result = UserSessionDetail.rank(sessions);

        // 초 합: #1=1258 > #2=1231 → #1이 먼저 (반올림 분 합이면 #2=21 > #1=20으로 뒤바뀜)
        assertThat(result).extracting(UserSessionDetail::userId).containsExactly(1L, 2L);
    }

    @Test
    void 순공_합이_같으면_userId_오름차순이다() {
        List<QualifyingSession> sessions = List.of(
                new QualifyingSession(20L, 1200, false), // 20분
                new QualifyingSession(5L, 1200, true)); // 20분

        List<UserSessionDetail> result = UserSessionDetail.rank(sessions);

        assertThat(result).extracting(UserSessionDetail::userId).containsExactly(5L, 20L);
    }
}
