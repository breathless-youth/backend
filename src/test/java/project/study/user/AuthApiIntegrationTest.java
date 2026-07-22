package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import project.study.TestcontainersConfiguration;
import project.study.user.dto.LoginResponse;
import project.study.user.dto.TokenResponse;
import project.study.user.entity.Provider;
import project.study.user.oauth.GoogleTokenVerifier;
import project.study.user.oauth.InvalidOAuthTokenException;
import project.study.user.oauth.OAuthUserInfo;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GoogleTokenVerifier googleTokenVerifier;

    @BeforeEach
    void setUp() {
        when(googleTokenVerifier.provider()).thenReturn(Provider.GOOGLE);
    }

    @Test
    void 첫_로그인은_유저를_생성하고_isNewUser가_true_재로그인은_false다() throws Exception {
        stubVerifier("sub-new");

        mockMvc.perform(loginRequest("sub-new"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.isNewUser").value(true));

        mockMvc.perform(loginRequest("sub-new"))
                .andExpect(jsonPath("$.isNewUser").value(false));
    }

    @Test
    void 액세스_토큰으로_보호된_API에_접근할_수_있다() throws Exception {
        LoginResponse tokens = login("sub-access");

        mockMvc.perform(logoutRequest(tokens.accessToken(), tokens.refreshToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void 로그아웃하면_해당_refresh_토큰으로_재발급할_수_없다() throws Exception {
        LoginResponse tokens = login("sub-logout");

        mockMvc.perform(logoutRequest(tokens.accessToken(), tokens.refreshToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(refreshRequest(tokens.refreshToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰_없이_보호된_API에_접근하면_401이다() throws Exception {
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_토큰으로는_보호된_API에_접근할_수_없다() throws Exception {
        LoginResponse tokens = login("sub-category");

        mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh는_새_토큰쌍을_발급하고_구_토큰_재사용_시_전체를_폐기한다() throws Exception {
        LoginResponse tokens = login("sub-rotate");

        String body = mockMvc.perform(refreshRequest(tokens.refreshToken()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        TokenResponse rotated = objectMapper.readValue(body, TokenResponse.class);
        assertThat(rotated.refreshToken()).isNotEqualTo(tokens.refreshToken());

        // 회전된 구 토큰 재사용 → 거부 + 탈취 의심으로 전체 폐기
        mockMvc.perform(refreshRequest(tokens.refreshToken())).andExpect(status().isUnauthorized());
        mockMvc.perform(refreshRequest(rotated.refreshToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void access_토큰으로는_refresh할_수_없다() throws Exception {
        LoginResponse tokens = login("sub-wrong-category");

        mockMvc.perform(refreshRequest(tokens.accessToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void 구글이_거부한_ID_토큰이면_401이다() throws Exception {
        when(googleTokenVerifier.verify("bad-token")).thenThrow(new InvalidOAuthTokenException("검증 실패"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GOOGLE\",\"idToken\":\"bad-token\"}"))
                .andExpect(status().isUnauthorized());
    }

    private void stubVerifier(String sub) {
        when(googleTokenVerifier.verify("id-token-" + sub))
                .thenReturn(new OAuthUserInfo(Provider.GOOGLE, sub, sub + "@test.com"));
    }

    private org.springframework.test.web.servlet.RequestBuilder loginRequest(String sub) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"GOOGLE\",\"idToken\":\"id-token-" + sub + "\"}");
    }

    private org.springframework.test.web.servlet.RequestBuilder refreshRequest(String refreshToken) {
        return post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private org.springframework.test.web.servlet.RequestBuilder logoutRequest(String accessToken, String refreshToken) {
        return post("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private LoginResponse login(String sub) throws Exception {
        stubVerifier(sub);
        String body = mockMvc.perform(loginRequest(sub))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readValue(body, LoginResponse.class);
    }
}
