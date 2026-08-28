package project.study.studysession.repository;

/**
 * {@code findQualifyingSessions} 네이티브 쿼리 결과 매핑용 프로젝션.
 * social은 세션 시간이 그 유저의 룸 참여구간과 겹치는지를 SQL EXISTS로 판별한 값이다.
 */
public interface QualifyingSessionRow {

    long getUserId();

    int getFocusSec();

    boolean getSocial();
}
