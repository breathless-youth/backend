package project.study.planner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public final class PlannerTaskRequests {
    private PlannerTaskRequests() {}

    public enum Scope {
        THIS,
        FUTURE
    }

    public record Repeat(@NotNull List<@NotNull DayOfWeek> weekdays, LocalDate endDate) {}

    public record Create(
            @NotNull Long subjectId,
            @NotNull LocalDate date,

            @NotBlank @Size(max = 25) @Schema(example = "수학 3단원 풀기")
            String name,

            @Valid Repeat repeat) {}

    public record Update(@Size(max = 25) String name, Boolean done, Scope scope) {
        public Scope effectiveScope() {
            return scope == null ? Scope.THIS : scope;
        }
    }

    public record Move(@NotNull LocalDate date) {}

    public record Order(@NotNull @Size(max = 20) List<@NotNull Long> subjectIds) {}

    public record Carry(
            @NotNull @Size(max = 1000) List<@NotBlank String> offeredKeys,
            @NotNull @Size(max = 1000) List<@NotBlank String> selectedKeys) {}
}
