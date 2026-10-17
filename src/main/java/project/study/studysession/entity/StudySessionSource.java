package project.study.studysession.entity;

/**
 * 세션 생성 방법
 *
 * 랭킹·연속 공부·집중률처럼 측정의 공정성에 기대는 집계는 MEASURED만 읽는다.
 */
public enum StudySessionSource {
    MEASURED,
    MANUAL
}
