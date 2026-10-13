package project.study.metrics.report.render;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;

/** 메시지 숫자·날짜 표기. 표·막대는 코드 블록 안에서 한글을 두 칸으로 세어 맞춘다. */
public final class Fmt {

    public static final String NONE = "—";

    private static final int BAR_WIDTH = 10;
    private static final int MAX_STREAK = 60;
    // 한글 자모·한글 호환 자모·한자·한글 음절·전각 — 고정폭 글꼴에서 두 칸
    private static final int[][] WIDE_RANGES = {
        {0x1100, 0x115F}, {0x3130, 0x318F}, {0x4E00, 0x9FFF}, {0xAC00, 0xD7A3}, {0xFF01, 0xFF60}
    };

    private Fmt() {}

    public static String monthDay(LocalDate date) {
        return date.getMonthValue() + "/" + date.getDayOfMonth();
    }

    public static String monthDayWeekday(LocalDate date) {
        return monthDay(date) + "(" + date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN) + ")";
    }

    public static String range(LocalDate from, LocalDate to) {
        return monthDay(from) + "~" + monthDay(to);
    }

    public static String percent(Rate rate) {
        return rate.defined() ? rate.percent() + "%" : NONE;
    }

    public static String rate(Rate rate) {
        return rate.defined() ? "%d%% (%d/%d)".formatted(rate.percent(), rate.numerator(), rate.denominator()) : NONE;
    }

    public static String duration(int seconds) {
        int minutes = seconds / 60;
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + "분";
        }
        return rest == 0 ? hours + "시간" : hours + "시간 " + rest + "분";
    }

    public static String phi(double phi) {
        return String.format(Locale.ROOT, "%.2f", phi);
    }

    public static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 10칸 막대. 값이 0이 아니면 반올림이 0이어도 한 칸은 채운다. */
    public static String bar(int value, int max) {
        int filled = max == 0 ? 0 : (int) Math.round((double) BAR_WIDTH * value / max);
        if (value > 0 && filled == 0) {
            filled = 1;
        }
        return "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled);
    }

    /** 막대 라벨 — 21 → "21–24". */
    public static String bandDash(int startHour) {
        return "%02d–%02d".formatted(startHour, startHour + 3);
    }

    public static String streak(int days) {
        return days > MAX_STREAK ? MAX_STREAK + "일+" : days + "일";
    }

    /** 고정폭 글꼴에서 한글·한자·전각은 두 칸이다. */
    public static int width(String text) {
        return text.codePoints().map(Fmt::cellWidth).sum();
    }

    public static String padRight(String text, int width) {
        return text + " ".repeat(Math.max(0, width - width(text)));
    }

    public static String padLeft(String text, int width) {
        return " ".repeat(Math.max(0, width - width(text))) + text;
    }

    public static String code(List<String> lines) {
        return "```\n" + String.join("\n", lines) + "\n```";
    }

    /** 1위면 "3일째 1위"·"오늘 처음 1위", 2~3위면 "3일째 상위권"·"오늘 처음 상위권". */
    public static String trend(RankedRule rule) {
        String place = rule.rank() == 1 ? "1위" : "상위권";
        return rule.days() > 1 ? rule.days() + "일째 " + place : "오늘 처음 " + place;
    }

    private static int cellWidth(int codePoint) {
        for (int[] range : WIDE_RANGES) {
            if (codePoint >= range[0] && codePoint <= range[1]) {
                return 2;
            }
        }
        return 1;
    }
}
