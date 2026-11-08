package project.study.group.translation;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class TranslationConfigTest {
    final TranslationConfig config = new TranslationConfig();

    @Test
    void missingFlagsAndKeysKeepEverythingOff() {
        var settings = config.settings(new MockEnvironment());
        assertThat(settings.enabled()).isFalse();
        for (var provider : TranslationProvider.values())
            assertThat(settings.active(provider)).isFalse();
        var missingKey = new MockEnvironment()
                .withProperty("app.group-translation.enabled", "true")
                .withProperty("app.group-translation.google.enabled", "true");
        assertThat(config.settings(missingKey).active(TranslationProvider.GOOGLE))
                .isFalse();
    }

    @Test
    void explicitFlagsAndKeyEnableOnlyThatProvider() {
        var env = new MockEnvironment()
                .withProperty("app.group-translation.enabled", "true")
                .withProperty("app.group-translation.google.enabled", "true")
                .withProperty("GOOGLE_TRANSLATION_API_KEY", "fake");
        assertThat(config.settings(env).active(TranslationProvider.GOOGLE)).isTrue();
        assertThat(config.settings(env).active(TranslationProvider.AZURE)).isFalse();
    }

    @Test
    void deploymentEnvironmentVariablesBindFlagsAndConservativeLimits() {
        var env = new StandardEnvironment();
        env.getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of(
                                "APP_GROUPTRANSLATION_ENABLED", "true",
                                "APP_GROUPTRANSLATION_DEEPL_ENABLED", "true",
                                "DEEPL_TRANSLATION_API_KEY", "fake:fx",
                                "APP_GROUPTRANSLATION_GOOGLE_DAILYLIMIT", "13000",
                                "APP_GROUPTRANSLATION_GOOGLE_MONTHLYLIMIT", "400000",
                                "APP_GROUPTRANSLATION_DEEPL_LIFETIMELIMIT", "1500")));
        ConfigurationPropertySources.attach(env);
        assertThat(config.settings(env).active(TranslationProvider.DEEPL)).isTrue();
        assertThat(config.settings(env).active(TranslationProvider.GOOGLE)).isFalse();
        assertThat(env.getProperty("app.group-translation.google.daily-limit", Long.class))
                .isEqualTo(13000);
        assertThat(env.getProperty("app.group-translation.google.monthly-limit", Long.class))
                .isEqualTo(400000);
        assertThat(env.getProperty("app.group-translation.deepl.lifetime-limit", Long.class))
                .isEqualTo(1500);
    }

    @Test
    void paidAzureOrDeepLPlansCannotBeEnabled() {
        var env = new MockEnvironment()
                .withProperty("app.group-translation.enabled", "true")
                .withProperty("app.group-translation.azure.enabled", "true")
                .withProperty("AZURE_TRANSLATION_API_KEY", "fake")
                .withProperty("app.group-translation.azure.plan", "S1");
        assertThatThrownBy(() -> config.settings(env)).isInstanceOf(IllegalArgumentException.class);
    }
}
