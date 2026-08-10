package project.study.user.oauth;

import project.study.user.entity.Provider;

public record OAuthUserInfo(Provider provider, String providerUserId) {}
