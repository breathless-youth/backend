package project.study.metrics.report.render;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;

/** 메시지 숫자·날짜 표기. */
public final class Fmt {

    public static final String NONE = "—";

    private static final int BAR_WIDTH = 10;
    private static final int MAX_STREAK = 60;

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
        return rate.defined() ? rate.percent() + "%" + fraction(rate) : NONE;
    }

    /** 굵게 쓴 퍼센트 뒤에 붙이는 분수 — " (8/14)", 분모가 0이면 빈 글. */
    public static String fraction(Rate rate) {
        return rate.defined() ? " (%d/%d)".formatted(rate.numerator(), rate.denominator()) : "";
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

    public static String streak(int days) {
        return days > MAX_STREAK ? MAX_STREAK + "일+" : days + "일";
    }

    /** 1위면 "3일째 1위"·"오늘 처음 1위", 2~3위면 "3일째 상위권"·"오늘 처음 상위권". */
    public static String trend(RankedRule rule) {
        String place = rule.rank() == 1 ? "1위" : "상위권";
        return rule.days() > 1 ? rule.days() + "일째 " + place : "오늘 처음 " + place;
    }
}
