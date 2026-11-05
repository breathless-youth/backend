package project.study.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import project.study.common.exception.NotFoundException;
import project.study.user.oauth.OAuthProperties;

/**
 * 안드로이드의 애플 웹 로그인 콜백. 이메일 스코프를 요청하면 애플이 결과를 이 URL로 form_post 한다. 로그인은 하지 않고, 받은 값을 설정에 박아 둔
 * 앱 딥링크로 303 넘기기만 한다 — 앱이 그 {@code id_token}으로 {@code POST /api/auth/link}를 부른다.
 */
@Tag(name = "Auth")
@RestController
@RequestMapping("/api/auth/apple")
@RequiredArgsConstructor
public class AppleCallbackController {

    private final OAuthProperties oAuthProperties;

    @Operation(summary = "안드로이드 애플 웹 로그인 콜백", description = """
                    애플이 form_post로 보내는 결과를 설정된 앱 딥링크로 303 넘긴다(`code`·`id_token`·`state`·`user` 중 값이 있는 것만 쿼리로). \
                    앱이 직접 부르는 API가 아니다. 로그인 처리는 하지 않으며, 넘어갈 주소는 서버 설정값으로 고정이다. \
                    설정이 없으면 404 `NOT_FOUND`.""")
    @SecurityRequirements // 애플이 인증 헤더 없이 보낸다
    @PostMapping(value = "/callback", version = "1", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(name = "id_token", required = false) String idToken,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String user) {
        String redirect =
                oAuthProperties.apple() == null ? null : oAuthProperties.apple().androidCallbackRedirect();
        if (!StringUtils.hasText(redirect)) {
            throw new NotFoundException("안드로이드 애플 로그인 콜백이 설정되지 않았습니다");
        }

        // 값은 자리표시자로 넣어 엄격하게 인코딩한다(& = + / 포함). 설정 주소의 기존 쿼리는 그대로 둔다
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("id_token", idToken);
        params.put("state", state);
        params.put("user", user);
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(redirect);
        Map<String, String> values = new LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (StringUtils.hasText(value)) {
                builder.queryParam(name, "{" + name + "}");
                values.put(name, value);
            }
        });
        URI location = builder.encode().buildAndExpand(values).toUri();

        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(location).build();
    }
}
