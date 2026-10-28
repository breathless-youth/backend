package project.study.acquisition.repository;

import java.time.Instant;
import project.study.acquisition.entity.InstallPlatform;
import project.study.acquisition.referrer.Attribution;

/** 저장할 첫 유입 경로. 설치 정보·딥링크 값은 빈 문자열을 null로 바꾼 뒤의 값이다. */
public record NewAcquisition(
        long userId,
        InstallPlatform platform,
        String installReferrer,
        String deepLinkAd,
        Instant capturedAt,
        Attribution attribution) {}
