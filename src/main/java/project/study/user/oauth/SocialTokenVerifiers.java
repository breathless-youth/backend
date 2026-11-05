package project.study.user.oauth;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import project.study.user.entity.Provider;

/** 프로바이더별 검증기로 위임하는 진입점. 통합 테스트는 이 빈을 목으로 바꾼다. */
@Component
public class SocialTokenVerifiers {

    private final Map<Provider, OAuthTokenVerifier> verifiers = new EnumMap<>(Provider.class);

    public SocialTokenVerifiers(List<OAuthTokenVerifier> verifiers) {
        verifiers.forEach(v -> this.verifiers.put(v.provider(), v));
    }

    public OAuthUserInfo verify(Provider provider, String idToken) {
        OAuthTokenVerifier verifier = verifiers.get(provider);
        if (verifier == null) {
            throw new InvalidOAuthTokenException("지원하지 않는 소셜 프로바이더입니다");
        }
        return verifier.verify(idToken);
    }
}
