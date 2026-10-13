package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.TimelineBuilder;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.Strength;

class RuleExplorerTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);
    private static final LocalDate SIGNUP = A.minusDays(20); // A·A−1·A−2 세 코호트에 모두 든다

    /** 30명 — 10명은 첫 주 방 3번(7명 남음), 20명은 안 함(2명 남음). 모두 첫 주 하루 30분만 공부. */
    private static UserTimeline[] cohort() {
        List<UserTimeline> users = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            boolean roomUser = i < 10;
            boolean retained = roomUser ? i < 7 : i < 12;
            TimelineBuilder builder = user(100 + i, SIGNUP).studyOnDays(0);
            if (roomUser) {
                builder.activity(Activity.ROOM_JOIN, 1, 3).amp(AmplitudeKeys.SIMPLE_MODE_ON, 1, 1);
            }
            if (retained) {
                builder.studyOnDays(8);
            }
            users.add(builder.build());
        }
        return users.toArray(UserTimeline[]::new);
    }

    @Test
    void 노출_조건을_통과한_규칙을_상관_순으로_고르고_제외_이유를_센다() {
        ExplorerSection section = RuleExplorer.analyze(ReportDataFixture.data(A, false, cohort()));

        assertThat(section.cohortSize()).isEqualTo(30);
        assertThat(section.retained()).isEqualTo(9);
        assertThat(section.top()).extracting(ranked -> ranked.rule().key()).containsExactly("room-1", "room-3");
        RankedRule first = section.top().getFirst();
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.days()).isEqualTo(3);
        assertThat(first.table().doers()).isEqualTo(10);
        assertThat(first.strength()).isEqualTo(Strength.STRONG);
        assertThat(section.passed()).isEqualTo(2);
        assertThat(section.ruleCount()).isEqualTo(40);
        assertThat(section.tally()).containsEntry(Exposure.NOT_COLLECTED, 13).containsEntry(Exposure.TOO_FEW, 25);
        assertThat(section.fixedRuleExposure()).isEqualTo(Exposure.TOO_FEW);
    }

    @Test
    void Amplitude가_수집됐으면_앱_행동_규칙도_겨룬다() {
        ExplorerSection section = RuleExplorer.analyze(ReportDataFixture.data(A, true, cohort()));

        assertThat(section.top())
                .extracting(ranked -> ranked.rule().key())
                .containsExactly("room-1", "room-3", "simple-mode");
        assertThat(section.tally()).doesNotContainKey(Exposure.NOT_COLLECTED);
    }

    @Test
    void 연속_일수는_1위면_1위_연속_2위_이하면_Top_3_연속이다() {
        List<List<String>> previous = List.of(List.of("x", "y", "z"), List.of("y", "x"));

        assertThat(RuleExplorer.days("x", 1, previous)).isEqualTo(2);
        assertThat(RuleExplorer.days("y", 2, previous)).isEqualTo(3);
        assertThat(RuleExplorer.days("z", 3, previous)).isEqualTo(2);
        assertThat(RuleExplorer.days("w", 1, previous)).isEqualTo(1);
    }

    @Test
    void 코호트가_비면_통과한_규칙이_없다() {
        ExplorerSection section = RuleExplorer.analyze(ReportDataFixture.data(A, true));

        assertThat(section.top()).isEmpty();
        assertThat(section.passed()).isZero();
        assertThat(section.tally()).containsEntry(Exposure.TOO_FEW, 40);
    }
}
