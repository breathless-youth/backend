package project.study.acquisition.referrer;

import java.util.regex.Pattern;

/**
 * 구글 광고 그룹마다 넣은 지연 딥링크 값(fm_ad=g_소재)이 라벨 형식인지 본다. 형식이 틀려도 구글 광고라는 판별은 그대로고(원문도 저장한다)
 * 응답의 라벨만 비운다. 웹이 이 라벨을 Amplitude 속성으로 쓰기 때문에, 광고 설정 실수로 생긴 값이 분석 값으로 퍼지지 않게 한다.
 */
public final class GoogleAdLabel {

    private static final Pattern FORMAT = Pattern.compile("^g_[a-z0-9_]{1,30}$");

    private GoogleAdLabel() {}

    /** 형식에 맞으면 그 값, 아니면 null. */
    public static String of(String googleDeepLinkAd) {
        return googleDeepLinkAd != null && FORMAT.matcher(googleDeepLinkAd).matches() ? googleDeepLinkAd : null;
    }
}
