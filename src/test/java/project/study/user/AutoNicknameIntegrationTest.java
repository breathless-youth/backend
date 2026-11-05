package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.nickname.AutoNicknameFixtures;
import project.study.user.nickname.NicknameLanguage;
import project.study.user.service.UserService;

/** 자동 닉네임 발급과 기존 '포메+숫자' 유저 마이그레이션 (BY-1135). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AutoNicknameIntegrationTest {

    private static final String MIGRATION = "db/migration/V61__animal_nicknames.sql";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    // 같은 컨테이너를 쓰는 다른 테스트가 커밋한 유저가 조합·'포메' 닉네임을 쥐고 있을 수 있어 트랜잭션 안에서 비켜 둔다 (끝나면 롤백)
    @BeforeEach
    void 기존_닉네임을_비켜_둔다() {
        jdbcTemplate.update("update users set nickname = 'test-' || id where nickname is not null");
    }

    @Test
    void 새_유저는_조합_닉네임과_그_첫_글자_이니셜을_받는다() {
        long userId = register();

        String nickname = nicknameOf(userId);
        assertThat(AutoNicknameFixtures.all(NicknameLanguage.KO)).contains(nickname);
        assertThat(initialOf(userId)).isEqualTo(nickname.substring(0, 1));
        assertThat(nicknameAutoOf(userId)).isTrue();
    }

    @Test
    void 언어를_보내면_그_언어_목록에서_받는다() {
        long userId = userService
                .register(new UserRegisterRequest(UUID.randomUUID().toString(), "zh-Hant-TW"))
                .userId();

        assertThat(AutoNicknameFixtures.all(NicknameLanguage.ZH_TW)).contains(nicknameOf(userId));
        assertThat(nicknameAutoOf(userId)).isTrue();
    }

    @Test
    void 조합이_다_차면_옛_방식_닉네임으로_발급한다() {
        occupy(AutoNicknameFixtures.all(NicknameLanguage.KO));

        long userId = register();

        assertThat(nicknameOf(userId)).matches("포메\\d{5}");
        assertThat(initialOf(userId)).isEqualTo("포");
    }

    @Test
    void 마이그레이션은_포메_숫자_닉네임만_서로_다른_조합으로_바꾼다() throws IOException {
        long auto = insertUser("포메12345");
        long legacy = insertUser("포메7"); // V9가 '포메' || id로 채운 옛 유저
        long custom = insertUser("직접 지은 이름");
        long lookalike = insertUser("포메짱");

        migrate();

        assertThat(List.of(nicknameOf(auto), nicknameOf(legacy)))
                .allSatisfy(AutoNicknameFixtures::assertAuto)
                .doesNotHaveDuplicates();
        assertThat(initialOf(auto)).isEqualTo(nicknameOf(auto).substring(0, 1));
        assertThat(initialOf(legacy)).isEqualTo(nicknameOf(legacy).substring(0, 1));
        assertThat(nicknameAutoOf(auto)).isTrue();
        assertThat(nicknameAutoOf(legacy)).isTrue();
        assertThat(nicknameAutoOf(custom)).isFalse();
        assertThat(nicknameOf(custom)).isEqualTo("직접 지은 이름");
        assertThat(nicknameOf(lookalike)).isEqualTo("포메짱");
    }

    @Test
    void 마이그레이션은_이미_쓰인_조합을_건너뛰고_모자라면_남은_유저는_그대로_둔다() throws IOException {
        List<String> all = new ArrayList<>(AutoNicknameFixtures.all(NicknameLanguage.KO));
        String free = all.removeLast();
        occupy(all);
        long first = insertUser("포메00001");
        long second = insertUser("포메00002");

        migrate();

        Set<String> after = Set.of(nicknameOf(first), nicknameOf(second));
        assertThat(after).hasSize(2).contains(free).containsAnyOf("포메00001", "포메00002");
    }

    private long register() {
        return userService
                .register(new UserRegisterRequest(UUID.randomUUID().toString()))
                .userId();
    }

    private long insertUser(String nickname) {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, nickname, initial, color_index, created_at, updated_at)"
                        + " values ('DEVICE', ?, ?, ?, 0, now(), now()) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                nickname,
                nickname.substring(0, 1));
    }

    private void occupy(Iterable<String> nicknames) {
        List<Object[]> rows = new ArrayList<>();
        nicknames.forEach(n -> rows.add(new Object[] {UUID.randomUUID().toString(), n}));
        jdbcTemplate.batchUpdate(
                "insert into users (provider, provider_user_id, nickname, initial, color_index, created_at, updated_at)"
                        + " values ('DEVICE', ?, ?, '가', 0, now(), now())",
                rows);
    }

    private void migrate() throws IOException {
        jdbcTemplate.execute(new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8));
    }

    private String nicknameOf(long userId) {
        return jdbcTemplate.queryForObject("select nickname from users where id = ?", String.class, userId);
    }

    private boolean nicknameAutoOf(long userId) {
        return jdbcTemplate.queryForObject("select nickname_auto from users where id = ?", Boolean.class, userId);
    }

    private String initialOf(long userId) {
        return jdbcTemplate.queryForObject("select initial from users where id = ?", String.class, userId);
    }
}
