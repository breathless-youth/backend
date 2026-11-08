package project.study.group.translation;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import project.study.group.service.GroupService;

@Configuration(proxyBeanMethods = false)
public class TranslationConfig {
    static final String PREFIX = "app.group-translation.";

    public record Settings(boolean enabled, Map<TranslationProvider, Boolean> providers) {
        public Settings {
            providers = Map.copyOf(providers);
        }

        public boolean active(TranslationProvider provider) {
            return enabled && providers.getOrDefault(provider, false);
        }
    }

    @Bean(name = "groupTranslationSettings")
    Settings settings(Environment env) {
        boolean enabled = env.getProperty(PREFIX + "enabled", Boolean.class, false);
        Map<TranslationProvider, Boolean> providers = new EnumMap<>(TranslationProvider.class);
        for (var provider : TranslationProvider.values()) {
            boolean active = enabled
                    && env.getProperty(path(provider, "enabled"), Boolean.class, false)
                    && !key(env, provider).isBlank();
            if (active) requireFreePlan(env, provider);
            providers.put(provider, active);
        }
        return new Settings(enabled, providers);
    }

    private static void requireFreePlan(Environment env, TranslationProvider provider) {
        String expected = provider == TranslationProvider.AZURE ? "F0" : "DEVELOPER";
        if (provider != TranslationProvider.GOOGLE
                && !expected.equalsIgnoreCase(env.getProperty(path(provider, "plan"), expected)))
            throw new IllegalArgumentException("Only free translation plans are allowed");
    }

    static String path(TranslationProvider provider, String property) {
        return PREFIX + provider.name().toLowerCase(Locale.ROOT) + "." + property;
    }

    static String key(Environment env, TranslationProvider provider) {
        return env.getProperty(
                path(provider, "api-key"), env.getProperty(provider.name() + "_TRANSLATION_API_KEY", ""));
    }

    @Bean
    TranslationCacheRepository translationCacheRepository(
            JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        return new TranslationCacheRepository(jdbc, manager, clock);
    }

    @Bean
    TranslationBudget translationBudget(
            JdbcClient jdbc, PlatformTransactionManager manager, Clock clock, Environment env) {
        return new TranslationBudget(
                jdbc,
                manager,
                clock,
                new TranslationBudget.Limits(
                        env.getProperty(PREFIX + "google.daily-limit", Long.class, 13000L),
                        env.getProperty(PREFIX + "google.monthly-limit", Long.class, 400000L),
                        env.getProperty(PREFIX + "azure.monthly-limit", Long.class, 2000000L),
                        env.getProperty(PREFIX + "deepl.lifetime-limit", Long.class, 1000000L)));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.group-translation",
            name = {"enabled", "google.enabled"},
            havingValue = "true")
    GoogleTextTranslator googleTextTranslator(Environment env) {
        return new GoogleTextTranslator(key(env, TranslationProvider.GOOGLE));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.group-translation",
            name = {"enabled", "azure.enabled"},
            havingValue = "true")
    AzureTextTranslator azureTextTranslator(Environment env) {
        return new AzureTextTranslator(
                key(env, TranslationProvider.AZURE),
                env.getProperty(
                        path(TranslationProvider.AZURE, "region"),
                        env.getProperty("AZURE_TRANSLATION_REGION", "koreacentral")));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.group-translation",
            name = {"enabled", "deepl.enabled"},
            havingValue = "true")
    DeepLTextTranslator deepLTextTranslator(Environment env) {
        return new DeepLTextTranslator(key(env, TranslationProvider.DEEPL));
    }

    @Bean
    GroupTranslationService groupTranslationService(
            TranslationCacheRepository cache,
            TranslationBudget budget,
            ObjectProvider<TextTranslator> translators,
            Settings settings,
            Clock clock,
            MeterRegistry metrics) {
        return new GroupTranslationService(cache, budget, translators.stream().toList(), settings, clock, metrics);
    }

    @Bean
    LocalizedGroupReader localizedGroupReader(GroupService groups, GroupTranslationService translations) {
        return new LocalizedGroupReader(groups, translations);
    }
}
