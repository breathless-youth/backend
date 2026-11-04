package project.study.group.support;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.v1Get;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.group.dto.PublicGroupDetailResponse;
import project.study.group.dto.PublicGroupPageResponse;
import project.study.group.dto.PublicGroupPageResponse.PublicGroupCard;

/**
 * 공개 그룹 목록·보기 API 테스트가 함께 쓰는 도구 (BY-1124) — 사용자·그룹·멤버를 만들고, 요청을 보내고, 끝나면 만든 사용자의 공부 기록을 지운다.
 * 같은 컨텍스트의 다른 테스트가 만든 공개 그룹이 목록에 섞이므로, 그룹 이름에 이 도구마다 고유한 {@link #token 표지}를 붙여 {@code query}로 자기
 * 그룹만 고른다.
 */
public class PublicGroupKit {

    /** 이번 주 월요일(10/12) 정오 KST — 이번 주 안이다. */
    public static final Instant MONDAY_NOON =
            ZonedDateTime.of(2026, 10, 12, 12, 0, 0, 0, GroupDeskTestConfig.KST).toInstant();

    /** 지난 주 일요일 23시 KST — 이번 주가 아니다. */
    public static final Instant LAST_SUNDAY =
            ZonedDateTime.of(2026, 10, 11, 23, 0, 0, 0, GroupDeskTestConfig.KST).toInstant();

    public final GroupProbe probe;
    public final StudyFixture study;

    /** 목록을 요청하는 나 — 어느 그룹의 멤버도 아니다. */
    public final long me;

    /** 이 도구가 만든 그룹 이름의 앞머리 — 목록 검색은 대소문자를 무시하므로 대소문자가 섞여 있어야 그 성질도 함께 본다. */
    public final String token = "Zq" + UUID.randomUUID().toString().substring(0, 8);

    private final MockMvcTester mvc;
    private final List<Long> users = new ArrayList<>();

    public PublicGroupKit(MockMvcTester mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.probe = new GroupProbe(jdbc);
        this.study = new StudyFixture(jdbc);
        this.me = user();
    }

    /** 만든 사용자들의 공부 기록(확정 세션·draft)을 지운다 — 같은 컨텍스트의 다른 테스트로 새지 않게 {@code @AfterEach}에서 부른다. */
    public void clear() {
        study.clear(users);
    }

    public long user() {
        long userId = probe.insertUser();
        users.add(userId);
        return userId;
    }

    public long user(String nicknamePrefix, String profileImage) {
        long userId = probe.insertUser(nicknamePrefix, profileImage);
        users.add(userId);
        return userId;
    }

    /** 새 사용자를 이 그룹의 멤버로 넣고 그 ID를 돌려준다. */
    public long member(long groupId) {
        long userId = user();
        probe.insertMember(groupId, userId, "APPLICATION");
        return userId;
    }

    /** 그룹에 새 멤버를 {@code count}명 더하고 그 ID들을 돌려준다. */
    public List<Long> join(long groupId, int count) {
        List<Long> joined = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            joined.add(member(groupId));
        }
        return joined;
    }

    /** 이름이 {@code token + suffix}인 새 공개 그룹(시험 목표 ETC) — 그룹장은 새 사용자다. */
    public long group(String suffix) {
        return group(suffix, "ETC");
    }

    public long group(String suffix, String category) {
        return probe.insertGroup(user(), "PUBLIC", token + suffix, category);
    }

    /** {@code key, value, key, value ...} 쌍으로 쿼리 파라미터를 싣는다 — 글자 그대로 보내 URL 인코딩을 따로 신경 쓰지 않는다. */
    public MockMvcRequestBuilder request(String... keyValues) {
        MockMvcRequestBuilder request = v1Get(mvc, "/api/groups/public", me);
        for (int i = 0; i < keyValues.length; i += 2) {
            request = request.queryParam(keyValues[i], keyValues[i + 1]);
        }
        return request;
    }

    public PublicGroupPageResponse list(String... keyValues) {
        return assertThat(request(keyValues))
                .hasStatusOk()
                .bodyJson()
                .convertTo(PublicGroupPageResponse.class)
                .actual();
    }

    /** 이 도구의 표지가 이름에 든 그룹만, 한 쪽에. */
    public List<PublicGroupCard> mine() {
        return list("query", token, "size", "50").groups();
    }

    /** 이름에 {@code query}가 든 그룹 ID. */
    public List<Long> search(String query) {
        return ids(list("query", query, "size", "50").groups());
    }

    public static List<Long> ids(List<PublicGroupCard> cards) {
        return cards.stream().map(PublicGroupCard::groupId).toList();
    }

    public static PublicGroupCard card(List<PublicGroupCard> cards, long groupId) {
        return cards.stream()
                .filter(card -> card.groupId() == groupId)
                .findFirst()
                .orElseThrow();
    }

    public PublicGroupDetailResponse detail(long groupId, long userId) {
        return assertThat(v1Get(mvc, "/api/groups/public/" + groupId, userId))
                .hasStatusOk()
                .bodyJson()
                .convertTo(PublicGroupDetailResponse.class)
                .actual();
    }
}
