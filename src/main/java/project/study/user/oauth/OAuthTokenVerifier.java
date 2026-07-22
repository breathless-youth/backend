package project.study.user.oauth;

import project.study.user.entity.Provider;

public interface OAuthTokenVerifier {

    Provider provider();

    OAuthUserInfo verify(String idToken);
}
