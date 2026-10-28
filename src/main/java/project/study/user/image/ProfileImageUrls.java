package project.study.user.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 저장된 사진 키를 응답 경로로 바꾼다. base-url이 비어 있으면 웹과 같은 출처의 상대 경로가 된다. */
@Component
public class ProfileImageUrls {

    private final String baseUrl;

    public ProfileImageUrls(@Value("${app.profile-image.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String of(String key) {
        return key == null ? null : baseUrl + "/" + key;
    }
}
