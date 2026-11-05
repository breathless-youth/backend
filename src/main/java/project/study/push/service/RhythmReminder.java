package project.study.push.service;

import io.sentry.Sentry;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.common.time.DefaultTimeZone;
import project.study.push.dto.PushMessage;
import project.study.push.dto.PushResult;
import project.study.push.dto.PushText;
import project.study.push.repository.PushExperimentQueries;
import project.study.push.repository.RhythmReminderQueries;
import project.study.push.repository.RhythmReminderQueries.Candidate;
import project.study.push.repository.RhythmReminderQueries.RecentStart;
import project.study.studysession.StudySessionThresholds;
import project.study.studysession.service.StudySessionService;

/**
 * 공부 리듬 기반 정기 맞춤 알림 (ADR-0042).
 *
 * <p>공부일 14일 이상은 오늘이 평일이면 평일, 주말이면 주말의 피크 2시간 시작 시각에 보낸다. 그 요일에 기록이 없으면 보내지 않는다.
 * 1~13일은 최근 2주 세션 시작 시각 평균에 3일 간격으로 보낸다. 최근 2주에 세션이 없으면 평균이 없어 자연히 멈춘다.
 * 날짜와 무관한 값(리듬 격자, 평균 시각, 시간대)은 하루 한 번 메모리에 계산해 둔다.
 * 요일, 오늘 공부했는지, 마지막 알림 뒤 며칠인지는 사람마다 현지 시각이 달라 틱마다 판단한다.
 * 재시작하면 다시 계산하고 push_send가 중복을 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RhythmReminder {

    static final String TYPE_PEAK = "rhythm_reminder_peak";
    static final String TYPE_USUAL = "rhythm_reminder_usual";
    static final String LINK = "/home";

    static final int RHYTHM_MIN_STUDY_DAYS = 14;
    static final int USUAL_INTERVAL_DAYS = 3;
    static final Duration RECENT = Duration.ofDays(14);
    static final LocalTime QUIET_FROM = LocalTime.of(21, 0);
    static final LocalTime QUIET_UNTIL = LocalTime.of(8, 0);

    /** 스케줄러 주기와 같아야 한 사람이 정확히 한 틱에 걸린다. */
    static final Duration TICK = Duration.ofMinutes(10);

    // 기록 탭 리듬과 같은 하루 시작 시각과 피크 길이
    private static final int DAY_START_HOUR = 5;
    private static final int PEAK_SPAN_HOURS = 2;

    private static final PushContent PEAK = new PushContent(
            new PushText("내 뇌가 가장 똑똑해지는 시간 🧠", "가장 집중 잘되는 시간이에요. 오늘도 달려볼까요?"),
            new PushText("Your brain's sharpest hour 🧠", "This is when you focus best. Let's make today count."));
    private static final PushContent USUAL = new PushContent(
            new PushText("딱 5분만 눈 딱 감고 켜봐요 ⏱️", "오늘 공부, 길게 할 필요 없어요. 평소 시작하던 시간에 5분만 가볍게 워밍업!"),
            new PushText(
                    "Just give it 5 minutes ⏱️",
                    "No need to study long today. Warm up for 5 minutes at your usual start time."));

    private final RhythmReminderQueries queries;
    private final PushExperimentQueries sendLog;
    private final StudySessionService studySessionService;
    private final PushSender pushSender;
    private final AtomicBoolean warnedDisabled = new AtomicBoolean();

    // 스케줄러 스레드 하나만 읽고 쓴다
    private List<Profile> profiles;
    private LocalDate profilesDate;

    public void tick(Instant now) {
        // 스케줄러가 몇 초~몇 분 늦게 돌아도 같은 10분 칸으로 맞춘다
        Instant slot =
                Instant.ofEpochSecond(Math.round(now.getEpochSecond() / (double) TICK.toSeconds()) * TICK.toSeconds());
        if (!pushSender.isEnabled()) {
            if (warnedDisabled.compareAndSet(false, true)) {
                log.warn("FCM 자격증명이 없어 정기 맞춤 알림을 멈춘다");
            }
            return;
        }
        // 다시 계산하는 시점일 뿐이라 서버 기준 날짜를 쓴다
        LocalDate refreshDate = slot.atZone(DefaultTimeZone.ZONE).toLocalDate();
        if (profiles == null || !refreshDate.equals(profilesDate)) {
            profiles = buildProfiles(slot);
            profilesDate = refreshDate;
        }
        for (Profile profile : profiles) {
            sendIfDue(profile, slot, now);
        }
    }

    private void sendIfDue(Profile profile, Instant slot, Instant now) {
        ZonedDateTime local = slot.atZone(profile.zone());
        String type = profile.grid() != null ? TYPE_PEAK : TYPE_USUAL;
        LocalTime at = profile.grid() != null ? peakStart(profile.grid(), local.getDayOfWeek()) : profile.usual();
        if (at == null || isQuiet(at) || !isDue(at, local.toLocalTime())) {
            return;
        }
        // 한 사람의 실패가 나머지를 막지 않는다
        try {
            send(profile.userId(), type, local.toLocalDate(), now);
        } catch (RuntimeException e) {
            log.error("정기 맞춤 알림 발송 실패 (userId={}, type={})", profile.userId(), type, e);
            Sentry.captureException(e);
        }
    }

    private List<Profile> buildProfiles(Instant now) {
        int minFocusSec = StudySessionThresholds.MIN_LIST_FOCUS_SEC;
        Map<Long, List<RecentStart>> recent = queries.findRecentStarts(now.minus(RECENT), minFocusSec).stream()
                .collect(Collectors.groupingBy(RecentStart::userId));
        List<Profile> built = new ArrayList<>();
        for (Candidate candidate : queries.findCandidates(minFocusSec)) {
            try {
                if (candidate.studyDays() >= RHYTHM_MIN_STUDY_DAYS) {
                    int[][] grid =
                            studySessionService.rhythm(candidate.userId()).focusSecByWeekdayHour();
                    built.add(new Profile(candidate.userId(), candidate.zone(), grid, null));
                } else {
                    LocalTime usual =
                            usualStart(averageStartMinute(recent.getOrDefault(candidate.userId(), List.of())));
                    if (usual != null) {
                        built.add(new Profile(candidate.userId(), candidate.zone(), null, usual));
                    }
                }
            } catch (RuntimeException e) {
                log.error("정기 맞춤 알림 시각 계산 실패 (userId={})", candidate.userId(), e);
                Sentry.captureException(e);
            }
        }
        return built;
    }

    /**
     * 오늘 요일 묶음(평일·주말)에서 순공 합이 가장 큰 연속 2시간의 시작 시각. 오늘 요일에 기록이 없으면 null.
     *
     * <p>기록 탭 카드와 같이 5시부터 다음 날 4시까지를 하루로 보고 4시→5시는 잇지 않으며, 합이 같으면 이른 구간을 고른다.
     */
    static LocalTime peakStart(int[][] grid, DayOfWeek today) {
        int todayRow = today.getValue() - 1;
        if (Arrays.stream(grid[todayRow]).sum() == 0) {
            return null;
        }
        boolean weekend = today == DayOfWeek.SATURDAY || today == DayOfWeek.SUNDAY;
        long[] columns = new long[24];
        for (int row = weekend ? 5 : 0; row < (weekend ? 7 : 5); row++) {
            for (int column = 0; column < 24; column++) {
                columns[column] += grid[row][(column + DAY_START_HOUR) % 24];
            }
        }
        int peak = 0;
        long peakSum = -1;
        for (int column = 0; column <= 24 - PEAK_SPAN_HOURS; column++) {
            long spanSum = 0;
            for (int i = 0; i < PEAK_SPAN_HOURS; i++) {
                spanSum += columns[column + i];
            }
            if (spanSum > peakSum) {
                peakSum = spanSum;
                peak = column;
            }
        }
        return LocalTime.of((peak + DAY_START_HOUR) % 24, 0);
    }

    /** 세션마다 그 세션 시간대의 시각을 새벽 5시부터 센 분으로 바꿔 평균한다. 세션이 없으면 null. */
    static Double averageStartMinute(List<RecentStart> starts) {
        OptionalDouble average = starts.stream()
                .mapToInt(start -> {
                    LocalTime time = start.startedAt().atZone(start.zone()).toLocalTime();
                    return (time.getHour() * 60 + time.getMinute() + (24 - DAY_START_HOUR) * 60) % (24 * 60);
                })
                .average();
        return average.isPresent() ? average.getAsDouble() : null;
    }

    /** 새벽 5시부터 센 평균 분을 10분 단위로 맞춘 시계 시각. 평균이 없으면 null. */
    static LocalTime usualStart(Double avgStartMinute) {
        if (avgStartMinute == null) {
            return null;
        }
        long rounded = Math.round(avgStartMinute / 10) * 10;
        return LocalTime.of(DAY_START_HOUR, 0).plusMinutes(rounded);
    }

    static boolean isQuiet(LocalTime time) {
        return !time.isBefore(QUIET_FROM) || time.isBefore(QUIET_UNTIL);
    }

    // 30·45분 오프셋 시간대는 현지 틱이 정각에 오지 않아 한 틱 길이의 범위로 본다. 보낼 시각은 8~21시라 범위가 자정을 넘지 않는다
    private static boolean isDue(LocalTime at, LocalTime now) {
        return !now.isBefore(at) && now.isBefore(at.plus(TICK));
    }

    // 자리를 먼저 잡아, FCM 호출이 실패해도 그날 다시 보내지 않고 다른 서버 푸시와 합쳐 하루 한 통을 지킨다
    private void send(long userId, String type, LocalDate today, Instant now) {
        if (!queries.canSend(userId, today)) {
            return;
        }
        if (TYPE_USUAL.equals(type)) {
            Optional<LocalDate> last = queries.lastSendDate(userId, TYPE_USUAL);
            if (last.isPresent() && ChronoUnit.DAYS.between(last.get(), today) < USUAL_INTERVAL_DAYS) {
                return;
            }
        }
        UUID pushId = UUID.randomUUID();
        if (!sendLog.reserveSend(pushId, userId, type, today, now)) {
            return;
        }
        PushContent content = TYPE_PEAK.equals(type) ? PEAK : USUAL;
        PushResult result =
                pushSender.send(List.of(userId), new PushMessage(content.ko(), content.en(), LINK, type, pushId));
        sendLog.recordAccepted(pushId, result.sent());
    }

    /** grid가 있으면 피크 알림, 없으면 usual 시각의 평균 알림이다. */
    private record Profile(long userId, ZoneId zone, int[][] grid, LocalTime usual) {}

    private record PushContent(PushText ko, PushText en) {}
}
