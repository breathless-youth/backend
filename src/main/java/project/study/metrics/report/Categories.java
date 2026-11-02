package project.study.metrics.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import project.study.common.category.StudyCategory;

/** 공부 분야 코드(`users.category`) → 메시지 라벨. 코드·라벨·표시 순서는 StudyCategory를 따른다. */
public final class Categories {

    public static final String NONE = "미입력";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        for (StudyCategory category : StudyCategory.values()) {
            LABELS.put(category.name(), category.label());
        }
    }

    private static final List<String> ORDER = List.copyOf(LABELS.values());

    private Categories() {}

    public static String label(String category) {
        if (category == null || category.isBlank()) {
            return NONE;
        }
        return LABELS.getOrDefault(category, category);
    }

    /** 같은 수일 때의 표시 순서 — 정의 순, 모르는 값은 그 뒤, 미입력은 맨 끝. */
    public static int order(String label) {
        if (NONE.equals(label)) {
            return Integer.MAX_VALUE;
        }
        int index = ORDER.indexOf(label);
        return index < 0 ? ORDER.size() : index;
    }
}
