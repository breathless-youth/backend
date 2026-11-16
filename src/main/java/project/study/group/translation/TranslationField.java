package project.study.group.translation;

/**
 * 번역하는 원문 칸. 칸마다 원문이 있는 테이블과 번역을 담는 캐시 테이블이 정해져 있다 — 그룹 이름·소개는 그룹 행, 신청 소개 글은 신청 행을
 * 기준으로 캐시한다 (ADR-0046 결정 11, BY-1301).
 */
public enum TranslationField {
    NAME(Subject.GROUP, "name"),
    DESCRIPTION(Subject.GROUP, "description"),
    INTRODUCTION(Subject.APPLICATION, "introduction");

    /** 원문 행의 종류. 캐시 테이블·키 칸·원문 테이블과, 번역해도 되는 행인지 거르는 조건을 묶는다. */
    enum Subject {
        GROUP("group_translation_cache", "group_id", "study_group", "deleted_at IS NULL"),
        // 그룹장이 보는 건 기한 안에서 답을 기다리는 신청뿐이다. 답이 났거나 기한이 지난 신청은 새로 번역하지 않는다
        APPLICATION(
                "group_application_translation_cache",
                "application_id",
                "study_group_application",
                "status = 'PENDING' AND expires_at > :now");

        final String cacheTable;
        final String keyColumn;
        final String sourceTable;
        final String liveCondition;

        Subject(String cacheTable, String keyColumn, String sourceTable, String liveCondition) {
            this.cacheTable = cacheTable;
            this.keyColumn = keyColumn;
            this.sourceTable = sourceTable;
            this.liveCondition = liveCondition;
        }
    }

    final Subject subject;
    final String sourceColumn;

    TranslationField(Subject subject, String sourceColumn) {
        this.subject = subject;
        this.sourceColumn = sourceColumn;
    }
}
