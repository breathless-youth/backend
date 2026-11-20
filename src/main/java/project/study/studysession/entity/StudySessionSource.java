package project.study.studysession.entity;

/**
 * 세션 생성 방법
 *
 * MEASURED는 관리 모드(AI 카메라로 잰 세션), TIMER는 자율 모드(카메라 없이 켜 둔 시간), MANUAL은 사용자가 직접 넣은 기록이다.
 * 랭킹·연속 공부·집중률처럼 측정의 공정성에 기대는 집계는 MEASURED만 읽는다.
 * 그룹 공부 시간처럼 자율 모드를 넣는 집계는 IN ('MEASURED', 'TIMER')로 넣을 출처를 직접 적는다 (ADR-0049).
 */
public enum StudySessionSource {
    MEASURED,
    MANUAL,
    TIMER
}
