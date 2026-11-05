package project.study.planner.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.planner.dto.PlannerTask;
import project.study.planner.repository.PlannerRule;
import project.study.planner.repository.PlannerStore;
import project.study.planner.repository.PlannerTaskRow;

@Component
@RequiredArgsConstructor
public class PlannerCarryCandidates {
    private final PlannerStore store;

    public List<PlannerTask> candidates(Long userId, LocalDate today) {
        List<PlannerTaskRow> rows = store.rows(userId, PlannerTaskResolver.FIRST_DATE, PlannerTaskResolver.LAST_DATE);
        List<PlannerRule> rules = store.rules(userId);
        Set<Long> todaySeries = rules.stream()
                .filter(r -> r.includes(today))
                .map(PlannerRule::seriesId)
                .collect(Collectors.toSet());
        rows.stream()
                .filter(r -> r.date().equals(today) && r.deletedAt() == null && r.seriesId() != null)
                .filter(r -> r.customized()
                        || r.doneAt() != null
                        || PlannerTaskResolver.ruleOn(rules, r.seriesId(), r.occurrenceDate()) != null)
                .forEach(r -> todaySeries.add(r.seriesId()));
        List<PlannerTask> result = new ArrayList<>();
        Map<Long, PlannerTask> latest = new HashMap<>();
        Map<String, PlannerTaskRow> saved = rows.stream().collect(Collectors.toMap(PlannerTaskRow::key, r -> r));
        addSaved(rows, rules, today, todaySeries, result, latest);
        for (PlannerRule rule : rules) {
            if (todaySeries.contains(rule.seriesId())) continue;
            PlannerTask task = latestForRule(rule, saved, today);
            if (task != null) latest.merge(rule.seriesId(), task, PlannerCarryCandidates::later);
        }
        latest.values().stream().filter(t -> !t.dismissed()).forEach(result::add);
        result.sort(Comparator.comparing(PlannerTask::date).thenComparing(PlannerTask::key));
        return result;
    }

    private static void addSaved(
            List<PlannerTaskRow> rows,
            List<PlannerRule> rules,
            LocalDate today,
            Set<Long> todaySeries,
            List<PlannerTask> result,
            Map<Long, PlannerTask> latest) {
        for (PlannerTaskRow row : rows) {
            PlannerTask task = eligibleSaved(row, rules, today);
            if (task == null) continue;
            if (row.seriesId() == null) {
                if (!row.dismissed()) result.add(task);
            } else if (!todaySeries.contains(row.seriesId()))
                latest.merge(row.seriesId(), task, PlannerCarryCandidates::later);
        }
    }

    private static PlannerTask eligibleSaved(PlannerTaskRow row, List<PlannerRule> rules, LocalDate today) {
        if (row.deletedAt() != null || row.doneAt() != null || !row.date().isBefore(today)) return null;
        if (row.seriesId() == null) return row.view(row.name());
        PlannerRule rule = PlannerTaskResolver.ruleOn(rules, row.seriesId(), row.occurrenceDate());
        if (!row.customized() && rule == null) return null;
        return row.view(row.customized() || rule == null ? row.name() : rule.name());
    }

    private static PlannerTask latestForRule(PlannerRule rule, Map<String, PlannerTaskRow> saved, LocalDate today) {
        LocalDate date = today.minusDays(1);
        if (rule.endDate() != null && rule.endDate().isBefore(date)) date = rule.endDate();
        while (!date.isBefore(rule.startDate())) {
            if (rule.includes(date)) {
                PlannerTask task = PlannerTaskResolver.virtual(rule, date);
                PlannerTaskRow row = saved.get(task.key());
                if (row == null) return task;
                if (row.deletedAt() == null
                        && row.doneAt() == null
                        && row.date().isBefore(today)) return row.view(row.customized() ? row.name() : rule.name());
            }
            date = date.minusDays(1);
        }
        return null;
    }

    private static PlannerTask later(PlannerTask a, PlannerTask b) {
        return a.date().isAfter(b.date()) ? a : b;
    }
}
