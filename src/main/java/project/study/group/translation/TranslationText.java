package project.study.group.translation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record TranslationText(long groupId, TranslationField field, String source) {
    public TranslationText {
        Objects.requireNonNull(field);
        Objects.requireNonNull(source);
    }

    public long characters() {
        return source.codePointCount(0, source.length());
    }

    public String sourceHash() {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
