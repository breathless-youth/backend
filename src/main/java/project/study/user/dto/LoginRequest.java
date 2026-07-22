package project.study.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import project.study.user.entity.Provider;

public record LoginRequest(
        @NotNull Provider provider, @NotBlank String idToken) {}
