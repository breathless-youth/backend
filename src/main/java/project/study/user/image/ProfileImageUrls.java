package project.study.user.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 저장된 사진 키를 응답 경로로 바꾼다. base-url이 비어 있으면 웹과 같은 출처의 상대 경로가 된다. */
@Component
public class ProfileImageUrls {

    private final String baseUrl;

    public ProfileImageUrls(@Value("${app.profile-image.base-url:}") String baseUrl) {
        // 끝 슬래시가 남으면 "//avatars/..."가 되어 브라우저가 호스트 주소로 읽는다
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String of(String key) {
        return key == null ? null : baseUrl + "/" + key;
    }
}
