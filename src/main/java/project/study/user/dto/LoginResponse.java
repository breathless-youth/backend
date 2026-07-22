package project.study.user.dto;

public record LoginResponse(String accessToken, String refreshToken, boolean isNewUser) {}
