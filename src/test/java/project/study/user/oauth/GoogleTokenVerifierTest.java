package project.study.user.oauth;
// AUTH-DISABLED: 로그인 MVP 제외 (ADR-0004) — 인증 재도입 시 이 파일 전체 주석 해제
//
// import static org.assertj.core.api.Assertions.assertThat;
// import static org.assertj.core.api.Assertions.assertThatThrownBy;
// import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
// import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
// import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
//
// import org.junit.jupiter.api.Test;
// import org.springframework.http.MediaType;
// import org.springframework.test.web.client.MockRestServiceServer;
// import org.springframework.web.client.RestClient;
// import project.study.user.entity.Provider;
//
// class GoogleTokenVerifierTest {
//
//     private static final String CLIENT_ID = "my-client-id";
//     private static final String TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=id-token";
//
//     private final RestClient.Builder builder = RestClient.builder();
//     private final MockRestServiceServer server =
//             MockRestServiceServer.bindTo(builder).build();
//     private final GoogleTokenVerifier verifier = new GoogleTokenVerifier(builder, CLIENT_ID);
//
//     @Test
//     void 유효한_ID_토큰이면_유저정보를_반환한다() {
//         server.expect(requestTo(TOKEN_INFO_URL))
//                 .andRespond(withSuccess(
//                         "{\"aud\":\"my-client-id\",\"sub\":\"12345\",\"email\":\"a@b.com\"}",
//                         MediaType.APPLICATION_JSON));
//
//         OAuthUserInfo userInfo = verifier.verify("id-token");
//
//         assertThat(userInfo.provider()).isEqualTo(Provider.GOOGLE);
//         assertThat(userInfo.providerUserId()).isEqualTo("12345");
//     }
//
//     @Test
//     void aud가_다른_토큰은_거부한다() {
//         server.expect(requestTo(TOKEN_INFO_URL))
//                 .andRespond(withSuccess(
//                         "{\"aud\":\"other-app-client-id\",\"sub\":\"12345\",\"email\":\"a@b.com\"}",
//                         MediaType.APPLICATION_JSON));
//
//         assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(InvalidOAuthTokenException.class);
//     }
//
//     @Test
//     void 구글이_거부한_토큰은_예외를_던진다() {
//         server.expect(requestTo(TOKEN_INFO_URL)).andRespond(withBadRequest());
//
//         assertThatThrownBy(() -> verifier.verify("id-token")).isInstanceOf(InvalidOAuthTokenException.class);
//     }
// }
