package project.study.group.translation;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
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
    void paidAzureOrDeepLPlansCannotBeEnabled() {
        var env = new MockEnvironment()
                .withProperty("app.group-translation.enabled", "true")
                .withProperty("app.group-translation.azure.enabled", "true")
                .withProperty("AZURE_TRANSLATION_API_KEY", "fake")
                .withProperty("app.group-translation.azure.plan", "S1");
        assertThatThrownBy(() -> config.settings(env)).isInstanceOf(IllegalArgumentException.class);
    }
}
