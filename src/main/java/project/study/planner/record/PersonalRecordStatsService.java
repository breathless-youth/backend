package project.study.planner.record;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.planner.service.PlannerCalendar;
import project.study.studysession.dto.DailyStudyStat;
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.dto.StudyRhythmResponse;
import project.study.studysession.dto.SubjectLookup;
import project.study.studysession.service.RhythmSplitter;
import project.study.studysession.service.SubjectLookupProvider;
import project.study.user.service.UserTimeZones;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalRecordStatsService {
    private final PersonalRecordProjection projection;
    private final SubjectLookupProvider subjects;
    private final UserTimeZones timeZones;
    private final Clock clock;

    public PersonalRecordListResponse day(Long userId, LocalDate date, String timeZone) {
        PlannerCalendar.checkDate(date);
        ZoneId zone = UserTimeZones.parse(timeZone).orElseGet(() -> timeZones.zoneOf(userId));
        YearMonth month = YearMonth.from(date);
        List<PersonalRecordPiece> all = projection.read(userId, month.atDay(1), month.atEndOfMonth(), zone);
        List<PersonalRecordPiece> day = all.stream()
                .filter(p -> p.date().equals(date))
                .sorted(Comparator.comparing((PersonalRecordPiece p) -> p.view().startedAt())
                        .reversed()
                        .thenComparing(PersonalRecordPiece::displayKey))
                .toList();
        Set<Long> subjectIds = day.stream()
                .flatMap(p -> p.view().subjectSegments().stream())
                .map(PlannerRecordSegment::subjectId)
                .collect(Collectors.toSet());
        Set<Long> taskIds = day.stream().flatMap(p -> p.taskIds().stream()).collect(Collectors.toSet());
        SubjectLookup lookup = subjects.lookup(subjectIds, taskIds);
        List<PersonalRecordPiece> measured =
                day.stream().filter(PersonalRecordPiece::measured).toList();
        return new PersonalRecordListResponse(
                day.stream().map(p -> p.summary(lookup)).toList(),
                day.size(),
                study(day),
                focus(day),
                measured.stream()
                        .mapToLong(PersonalRecordStatistics::longest)
                        .max()
                        .orElse(0),
                PersonalRecordStatistics.rate(focus(measured), study(measured)),
                PersonalRecordStatistics.counts(
                        day.stream().flatMap(p -> p.events().stream()).toList()),
                all.stream().map(PersonalRecordPiece::date).distinct().sorted().toList(),
                subjects.daySubjects(userId, date, lookup.subjects().keySet()));
    }

    private static long study(List<PersonalRecordPiece> pieces) {
        return pieces.stream().mapToLong(p -> p.view().studySec()).sum();
    }

    private static long focus(List<PersonalRecordPiece> pieces) {
        return pieces.stream().mapToLong(p -> p.view().focusSec()).sum();
    }

    public StudyPeriodStatsResponse period(
            Long userId, LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo, String timeZone) {
        PlannerCalendar.range(from, to);
        if ((compareFrom == null) != (compareTo == null)) throw new BadRequestException("비교 기간의 양 끝 날짜가 필요합니다");
        if (compareFrom != null) PlannerCalendar.range(compareFrom, compareTo);
        ZoneId zone = UserTimeZones.parse(timeZone).orElseGet(() -> timeZones.zoneOf(userId));
        LocalDate left = compareFrom == null || from.isBefore(compareFrom) ? from : compareFrom;
        LocalDate right = compareTo == null || to.isAfter(compareTo) ? to : compareTo;
        List<PersonalRecordPiece> pieces = projection.read(userId, left, right, zone);
        return new StudyPeriodStatsResponse(
                from,
                to,
                compareFrom,
                compareTo,
                daily(pieces, from, to),
                compareFrom == null ? List.of() : daily(pieces, compareFrom, compareTo));
    }

    private static List<DailyStudyStat> daily(List<PersonalRecordPiece> pieces, LocalDate from, LocalDate to) {
        var study = pieces.stream()
                .collect(Collectors.groupingBy(
                        PersonalRecordPiece::date,
                        Collectors.summingLong(p -> p.view().studySec())));
        var focus = pieces.stream()
                .collect(Collectors.groupingBy(
                        PersonalRecordPiece::date,
                        Collectors.summingLong(p -> p.view().focusSec())));
        List<DailyStudyStat> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            result.add(new DailyStudyStat(date, study.getOrDefault(date, 0L), focus.getOrDefault(date, 0L)));
        }
        return result;
    }

    public StudyRhythmResponse rhythm(Long userId, String timeZone) {
        ZoneId zone = UserTimeZones.parse(timeZone).orElseGet(() -> timeZones.zoneOf(userId));
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        List<PersonalRecordPiece> measured = projection.all(userId, zone).stream()
                .filter(PersonalRecordPiece::measured)
                .toList();
        long days = measured.stream()
                .map(PersonalRecordPiece::date)
                .filter(date -> !date.isAfter(today))
                .distinct()
                .count();
        int[][] grid = RhythmSplitter.emptyGrid();
        for (PersonalRecordPiece piece : measured) {
            if (!piece.rhythmEligible()) continue;
            RhythmSplitter.accumulate(
                    grid,
                    piece.view().startedAt(),
                    piece.view().endedAt(),
                    Math.toIntExact(piece.view().focusSec()),
                    piece.events(),
                    piece.zone());
        }
        return new StudyRhythmResponse(days, grid);
    }
}
