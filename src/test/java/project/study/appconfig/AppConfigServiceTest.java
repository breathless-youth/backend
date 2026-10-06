package project.study.appconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.appconfig.service.AppConfigService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AppConfigServiceTest {

    @Autowired
    private AppConfigService appConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void put(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO app_config (config_key, config_value) VALUES (?, ?)"
                        + " ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value",
                key,
                value);
    }

    @Test
    void 정확히_true일_때만_켜진다() {
        put("test.on", "true");
        put("test.spaced", " true ");
        put("test.upper", "TRUE");

        assertThat(appConfigService.isEnabled("test.on")).isTrue();
        assertThat(appConfigService.isEnabled("test.spaced")).isFalse();
        assertThat(appConfigService.isEnabled("test.upper")).isFalse();
        assertThat(appConfigService.isEnabled("test.missing")).isFalse();
    }

    @Test
    void 문자열은_앞뒤_공백을_떼고_비었으면_없다() {
        put("test.url", " https://forms.example/a\n");
        put("test.blank", "  ");

        assertThat(appConfigService.getString("test.url")).contains("https://forms.example/a");
        assertThat(appConfigService.getString("test.blank")).isEmpty();
        assertThat(appConfigService.getString("test.missing")).isEmpty();
    }
}
