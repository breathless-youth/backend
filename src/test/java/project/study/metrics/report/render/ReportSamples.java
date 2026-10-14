package project.study.metrics.report.render;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.Rate;
import project.study.metrics.report.SectionResult;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.analyzer.RetentionAnalyzer.Window;
import project.study.metrics.report.analyzer.RetentionSection;
import project.study.metrics.report.analyzer.RetentionSection.RetentionRow;
import project.study.metrics.report.analyzer.RosterCard;
import project.study.metrics.report.analyzer.RosterCard.Habits;
import project.study.metrics.report.analyzer.RosterCard.StudyFlow;
import project.study.metrics.report.analyzer.RosterSection;
import project.study.metrics.report.analyzer.RosterSection.RosterGroup;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.analyzer.SegmentSection.BehaviorRow;
import project.study.metrics.report.analyzer.SegmentSection.Comparison;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.analyzer.SegmentSection.ShareRow;
import project.study.metrics.report.analyzer.SignupSection;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;
import project.study.metrics.report.analyzer.SignupSection.Share;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.RuleCatalog;
import project.study.metrics.report.rule.SegmentRule;
import project.study.metrics.report.rule.TwoByTwo;

/** 테스트용 — 스펙 부록 A 숫자로 채운 섹션들. 기준일 A = 2026-10-12(월). */
public final class ReportSamples {

    public static final LocalDate A = LocalDate.of(2026, 10, 12);
    public static final LocalDate COHORT_FROM = A.minusDays(40);
    public static final LocalDate COHORT_TO = A.minusDays(13);

    private ReportSamples() {}

    public static SegmentRule rule(String key) {
        return RuleCatalog.all().stream()
                .filter(rule -> rule.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    public static SignupSection signup() {
        List<HourBand> bands = List.of(
                new HourBand(0, 10, 1),
                new HourBand(3, 2, 0),
                new HourBand(6, 5, 2),
                new HourBand(9, 9, 1),
                new HourBand(12, 12, 1),
                new HourBand(15, 14, 2),
                new HourBand(18, 20, 3),
                new HourBand(21, 26, 5));
        List<Share> categories = List.of(
                new Share("수능", 30),
                new Share("공무원", 22),
                new Share("자격증", 14),
                new Share("취업", 8),
                new Share("어학", 5),
                new Share("전문직", 2),
                new Share("미입력", 17));
        Attribution attribution = new Attribution(
                98, 37, List.of(new Share("instagram", 24), new Share("tiktok", 9), new Share("naver", 3)), 4);
        return new SignupSection(A, 38, 503L, 540L, Rate.of(6, 38), 98, bands, categories, attribution);
    }

    public static RetentionSection retention() {
        return new RetentionSection(
                List.of(
                        new RetentionRow(
                                Window.NEXT_DAY, Rate.of(27, 268), Rate.of(30, 270), A.minusDays(28), A.minusDays(1)),
                        new RetentionRow(Window.WEEK_TWO, Rate.of(16, 252), Rate.of(12, 240), COHORT_FROM, COHORT_TO),
                        new RetentionRow(
                                Window.WEEK_FIVE, Rate.of(6, 190), Rate.of(6, 200), A.minusDays(61), A.minusDays(34))),
                Rate.of(199, 265),
                A.minusDays(33),
                A.minusDays(6));
    }

    public static SegmentSection segment() {
        List<BehaviorRow> behaviors = List.of(
                new BehaviorRow("소셜 방 3번 이상 참여", Rate.of(8, 14), Rate.of(2, 238)),
                new BehaviorRow("D-day 설정", Rate.of(7, 14), Rate.of(23, 238)));
        List<Comparison> who = List.of(
                new Comparison(
                        "분야",
                        List.of(
                                new ShareRow("공무원", Rate.of(5, 14), Rate.of(50, 238)),
                                new ShareRow("미입력", Rate.of(1, 14), Rate.of(69, 238)))),
                new Comparison("광고 유입", List.of(new ShareRow("", Rate.of(4, 14), Rate.of(98, 238)))));
        return new SegmentSection(
                COHORT_FROM,
                COHORT_TO,
                252,
                List.of(
                        new GroupRow("3일 이상", 14, Rate.of(8, 14)),
                        new GroupRow("1~2일", 46, Rate.of(6, 46)),
                        new GroupRow("0일", 192, Rate.of(2, 192))),
                new TwoByTwo(8, 6, 8, 230),
                true,
                true,
                behaviors,
                new BehaviorRow("프로필(분야·목표) 입력", Rate.of(13, 14), Rate.of(176, 238)),
                who);
    }

    public static RosterCard bearCard() {
        Instant signup = Instant.parse("2026-09-29T13:00:00Z"); // 9/29(화) 22시 KST
        return new RosterCard(
                true,
                418,
                "공부하는곰",
                signup,
                "공무원(7급 합격)",
                "iOS",
                "유입: 광고(instagram)",
                new StudyFlow(5, 11 * 3600 + 20 * 60, 4, 4, 8 * 3600 + 5 * 60, A, false, 3),
                List.of("소셜 방 7회", "과목 4개", "할 일 완료 12개", "D-day", "간단 모드", "가로 화면", "백색소음", "랭킹", "앱 실행 6일"),
                false,
                new Habits(
                        OptionalDouble.of(48 * 60),
                        OptionalInt.of(21),
                        OptionalInt.of(62),
                        OptionalDouble.of(0.5),
                        OptionalDouble.of(1.2)));
    }

    public static RosterCard leftCard() {
        Instant signup = Instant.parse("2026-09-29T04:00:00Z"); // 9/29(화) 13시 KST
        return new RosterCard(
                true,
                421,
                "자격증따자",
                signup,
                "자격증",
                "Android",
                "유입: 표시 없음",
                new StudyFlow(3, 110 * 60, 3, 0, 0, LocalDate.of(2026, 10, 5), true, 0),
                List.of("과목 1개", "앱 실행 3일"),
                false,
                new Habits(
                        OptionalDouble.of(18 * 60),
                        OptionalInt.of(12),
                        OptionalInt.of(0),
                        OptionalDouble.of(2.4),
                        OptionalDouble.of(6.5)));
    }

    public static RosterSection roster() {
        return new RosterSection(
                A,
                COHORT_FROM,
                COHORT_TO,
                new RosterGroup(1, List.of(bearCard()), null),
                new RosterGroup(1, List.of(leftCard()), null));
    }

    public static ExplorerSection explorer() {
        Map<Exposure, Integer> tally = new EnumMap<>(Exposure.class);
        tally.put(Exposure.PASS, 6);
        tally.put(Exposure.TOO_FEW, 14);
        tally.put(Exposure.TOO_COMMON, 2);
        tally.put(Exposure.WEAK, 15);
        tally.put(Exposure.NOT_COLLECTED, 3);
        return new ExplorerSection(
                COHORT_FROM,
                COHORT_TO,
                252,
                16,
                List.of(
                        new RankedRule(rule("room-3"), new TwoByTwo(7, 3, 9, 233), 1, 3),
                        new RankedRule(rule(RuleCatalog.FIXED_RULE_KEY), new TwoByTwo(8, 6, 8, 230), 2, 3)),
                tally,
                Exposure.PASS);
    }

    public static ActiveUserRow morningRow() {
        return new ActiveUserRow(531, "아침형인간", 0, 1, 160 * 60, false, 2, "수능", 1);
    }

    public static ActiveUsersSection active() {
        Map<Stage, List<ActiveUserRow>> byStage = new EnumMap<>(Stage.class);
        byStage.put(Stage.NEW, rows(morningRow(), 6));
        byStage.put(Stage.LOVERS, rows(new ActiveUserRow(418, "공부하는곰", 13, 3, 210 * 60, true, 4, "공무원(7급 합격)", 6), 5));
        byStage.put(Stage.COMEBACK, rows(new ActiveUserRow(212, "다시시작", 31, 1, 50 * 60, false, 1, "어학(토익 900)", 2), 2));
        byStage.put(Stage.SOMETIMES, rows(new ActiveUserRow(300, "가끔이", 20, 1, 20 * 60, false, 1, "분야 미입력", 2), 2));
        return new ActiveUsersSection(15, byStage, 9);
    }

    private static List<ActiveUserRow> rows(ActiveUserRow first, int count) {
        List<ActiveUserRow> rows = new ArrayList<>();
        rows.add(first);
        for (int i = 1; i < count; i++) {
            rows.add(new ActiveUserRow(
                    first.userId() + i, first.nickname() + i, first.daysSinceSignup(), 1, 600, false, 1, "수능", null));
        }
        return rows;
    }

    public static DailyReport report() {
        return new DailyReport(
                A,
                SectionResult.of(signup()),
                SectionResult.of(retention()),
                SectionResult.of(segment()),
                SectionResult.of(roster()),
                SectionResult.of(explorer()),
                SectionResult.of(active()));
    }
}
