package project.study.user.oauth;

import project.study.user.entity.Provider;

/** nonce는 ID 토큰의 nonce 클레임이며, 없으면 null이다. */
public record OAuthUserInfo(Provider provider, String providerUserId, String email, String nonce) {}
