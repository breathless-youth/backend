package project.study.point.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 광고 보상 숫자와 우리 광고 단위. 광고 단위가 비어 있으면 광고 보상은 꺼진 것이다(콜백 404, 받기 탭의 available=false). 기본값은 예시이고
 * 기획이 정하면 프로퍼티로 바꾼다.
 */
@Component
public class AdRewardPolicy {

    private final Set<String> units;
    private final int points;
    private final int dailyLimit;

    public AdRewardPolicy(
            @Value("${app.points.ad.units:}") List<String> units,
            @Value("${app.points.ad.points:10}") int points,
            @Value("${app.points.ad.daily-limit:3}") int dailyLimit) {
        this.units = units.stream()
                .map(AdRewardPolicy::unitNumber)
                .filter(unit -> !unit.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.points = points;
        this.dailyLimit = dailyLimit;
    }

    public boolean available() {
        return !units.isEmpty();
    }

    /** 콜백의 ad_unit은 숫자 부분만 온다. 다른 앱의 광고 단위로 받은 서명도 구글 서명이라, 우리 단위인지 따로 본다. */
    public boolean owns(String adUnit) {
        return adUnit != null && units.contains(adUnit);
    }

    public int points() {
        return points;
    }

    public int dailyLimit() {
        return dailyLimit;
    }

    /** 설정에는 "ca-app-pub-…/1234567890"처럼 전체를 적어도 된다. */
    private static String unitNumber(String configured) {
        String trimmed = configured.trim();
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }
}
