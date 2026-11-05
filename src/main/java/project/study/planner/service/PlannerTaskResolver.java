package project.study.planner.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.NotFoundException;
import project.study.planner.dto.PlannerTask;
import project.study.planner.repository.PlannerRule;
import project.study.planner.repository.PlannerStore;
import project.study.planner.repository.PlannerTaskRow;

@Component
@RequiredArgsConstructor
public class PlannerTaskResolver {
    public static final LocalDate FIRST_DATE = LocalDate.of(1, 1, 1);
    public static final LocalDate LAST_DATE = LocalDate.of(9999, 12, 31);
    private final PlannerStore store;

    public List<PlannerTask> tasks(Long userId, LocalDate from, LocalDate to) {
        PlannerCalendar.range(from, to);
        List<PlannerTaskRow> rows = store.rows(userId, from, to);
        List<PlannerRule> rules = store.rules(userId);
        List<PlannerTask> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) result.addAll(on(rows, rules, date));
        return result;
    }

    public PlannerTask get(Long userId, String key) {
        try {
            if (key.startsWith("t:")) {
                PlannerTaskRow row =
                        store.row(userId, Long.parseLong(key.substring(2))).orElseThrow(PlannerTaskResolver::missing);
                if (row.seriesId() != null) return occurrence(userId, row.seriesId(), row.occurrenceDate());
                if (row.deletedAt() != null) throw missing();
                return row.view(row.name());
            }
            String[] parts = key.split(":", 3);
            if (parts.length != 3 || !parts[0].equals("r")) throw new IllegalArgumentException();
            long series = Long.parseLong(parts[1]);
            LocalDate date = LocalDate.parse(parts[2]);
            PlannerCalendar.checkDate(date);
            return occurrence(userId, series, date);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new BadRequestException("할 일 key 형식이 올바르지 않습니다");
        }
    }

    public static List<PlannerTask> on(List<PlannerTaskRow> rows, List<PlannerRule> rules, LocalDate date) {
        Map<String, PlannerTaskRow> saved =
                rows.stream().collect(Collectors.toMap(PlannerTaskRow::key, Function.identity()));
        List<PlannerTask> result = new ArrayList<>();
        for (PlannerTaskRow row : rows) {
            PlannerTask view = rowOn(row, rules, date);
            if (view != null) result.add(view);
        }
        for (PlannerRule rule : rules) {
            if (rule.includes(date)) {
                PlannerTask task = virtual(rule, date);
                if (!saved.containsKey(task.key())) result.add(task);
            }
        }
        result.sort(Comparator.comparing(PlannerTask::subjectId).thenComparing(PlannerTask::key));
        return result;
    }

    private PlannerTask occurrence(Long userId, long series, LocalDate date) {
        PlannerTaskRow saved = store.occurrence(userId, series, date).orElse(null);
        PlannerRule rule = ruleOn(store.rules(userId), series, date);
        if (saved == null) {
            if (rule == null) throw missing();
            return virtual(rule, date);
        }
        PlannerTask view = rowOn(saved, store.rules(userId), saved.date());
        if (view == null) throw missing();
        return view;
    }

    private static PlannerTask rowOn(PlannerTaskRow row, List<PlannerRule> rules, LocalDate date) {
        if (!date.equals(row.date()) || row.deletedAt() != null) return null;
        PlannerRule rule = row.seriesId() == null ? null : ruleOn(rules, row.seriesId(), row.occurrenceDate());
        if (row.seriesId() != null && rule == null && !row.customized() && row.doneAt() == null) return null;
        return row.view(row.customized() || rule == null ? row.name() : rule.name());
    }

    public static PlannerRule ruleOn(List<PlannerRule> rules, long series, LocalDate date) {
        return rules.stream()
                .filter(r -> r.seriesId() == series && r.includes(date))
                .findFirst()
                .orElse(null);
    }

    public static PlannerTask virtual(PlannerRule rule, LocalDate date) {
        return new PlannerTask(
                "r:" + rule.seriesId() + ":" + date,
                null,
                rule.subjectId(),
                rule.name(),
                date,
                false,
                null,
                rule.seriesId(),
                date,
                false);
    }

    private static NotFoundException missing() {
        return new NotFoundException("할 일을 찾을 수 없습니다");
    }
}
