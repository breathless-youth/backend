package project.study.acquisition.referrer;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import project.study.acquisition.entity.AcquisitionSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 앱이 보낸 설치 정보(Play 설치 리퍼러 문자열)와 딥링크 광고 값으로 유입 경로를 판별한다. 위에서부터 먼저 맞는 규칙이 이긴다.
 *
 * <ol>
 *   <li>utm_content 안에 메타 암호문(source.data·nonce)이 있다 → META_AD. utm_source는 보지 않는다. 메타 문서가 utm_source를
 *       보장하지 않는다고 밝혔기 때문이다.
 *   <li>code가 숫자 4자리 → INVITE. 딥링크보다 앞에 두는 이유는 Play 리퍼러가 스토어에 들어가기 직전에 누른 링크(마지막 클릭)이고, 메타 딥링크는 메타가 자기 기여 기간 안에서 붙여 주는 값이기 때문이다.
 *   <li>딥링크 광고 값이 있다 → META_AD (상세 없음).
 *   <li>utm_source=google-play, utm_medium=organic → ORGANIC.
 *   <li>그 밖에 비어 있지 않은 설치 정보 → OTHER_LINK.
 *   <li>아무것도 없다 → UNKNOWN.
 * </ol>
 *
 * 입력은 서비스가 공백을 null로 정리해 넘긴다(공백도 없는 값으로 취급한다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstallReferrerClassifier {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern INVITE_CODE = Pattern.compile("^\\d{4}$");

    private final MetaReferrerDecryptor decryptor;

    public Attribution classify(String installReferrer, String deepLinkAd) {
        Map<String, String> params = parseQuery(installReferrer);
        Utm utm = new Utm(
                blankToNull(params.get("utm_source")),
                blankToNull(params.get("utm_medium")),
                blankToNull(params.get("utm_campaign")));

        Optional<String[]> cipher = metaCipher(params.get("utm_content"));
        if (cipher.isPresent()) {
            return new Attribution(AcquisitionSource.META_AD, utm, null, decryptDetail(cipher.get()));
        }
        String code = params.get("code");
        if (code != null && INVITE_CODE.matcher(code).matches()) {
            return new Attribution(AcquisitionSource.INVITE, utm, code, null);
        }
        if (!isBlank(deepLinkAd)) {
            return new Attribution(AcquisitionSource.META_AD, utm, null, null);
        }
        if ("google-play".equals(utm.source()) && "organic".equals(utm.medium())) {
            return new Attribution(AcquisitionSource.ORGANIC, utm, null, null);
        }
        AcquisitionSource rest = isBlank(installReferrer) ? AcquisitionSource.UNKNOWN : AcquisitionSource.OTHER_LINK;
        return new Attribution(rest, utm, null, null);
    }

    /** utm_content가 JSON이고 source.data·source.nonce가 비어 있지 않은 문자열일 때만 {data, nonce}. */
    private Optional<String[]> metaCipher(String utmContent) {
        if (isBlank(utmContent)) {
            return Optional.empty();
        }
        try {
            JsonNode source = MAPPER.readTree(utmContent).path("source");
            JsonNode data = source.path("data");
            JsonNode nonce = source.path("nonce");
            if (isText(data) && isText(nonce)) {
                return Optional.of(new String[] {data.asString(), nonce.asString()});
            }
        } catch (JacksonException e) {
            // JSON이 아니면 메타 값이 아니다.
        }
        return Optional.empty();
    }

    private static boolean isText(JsonNode node) {
        return node.isString() && !node.asString().isBlank();
    }

    /** 풀지 못하면 null — 메타 광고라는 사실은 유지하고 상세만 비운다. */
    private MetaAdDetail decryptDetail(String[] cipher) {
        return decryptor.decrypt(cipher[0], cipher[1]).map(this::toDetail).orElse(null);
    }

    /** 평문이 JSON 객체가 아니면 null(평문은 로그에 남기지 않는다). 필드명이 한 단계씩 어긋난 매핑은 MetaAdDetail 설명 참고. */
    private MetaAdDetail toDetail(String plain) {
        try {
            JsonNode root = MAPPER.readTree(plain);
            if (!root.isObject()) {
                log.warn("메타 설치 정보 평문이 JSON 객체가 아니다");
                return null;
            }
            return new MetaAdDetail(
                    text(root, "campaign_group_id"),
                    text(root, "campaign_group_name"),
                    text(root, "campaign_id"),
                    text(root, "campaign_name"),
                    text(root, "adgroup_id"),
                    text(root, "adgroup_name"),
                    plain);
        } catch (JacksonException e) {
            log.warn("메타 설치 정보 평문이 JSON 객체가 아니다");
            return null;
        }
    }

    /** 문자열이든 숫자든 문자열로, 없거나 null이면 null. */
    private static String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    /** &로 자르고 첫 =에서 키·값을 나눈다. 같은 키는 첫 값. 깨진 퍼센트 인코딩은 원문 그대로 둔다. */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (isBlank(query)) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.putIfAbsent(decode(key), decode(value));
        }
        return params;
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, UTF_8);
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
