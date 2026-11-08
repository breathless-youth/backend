package project.study.group.translation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface TextTranslator {
    enum Failure {
        NONE,
        TEMPORARY,
        RATE_LIMIT,
        QUOTA,
        AUTH,
        UNSUPPORTED,
        INVALID_RESPONSE
    }

    record TranslationReply(List<String> texts, Failure failure, Instant retryAt) {
        public static TranslationReply failed(Failure failure) {
            return new TranslationReply(List.of(), failure, null);
        }
    }

    TranslationProvider provider();

    TranslationReply translate(List<String> texts, TranslationLanguage language, Duration timeout);
}
