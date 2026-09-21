package project.study.studysession.dto;

/** 과목 하나의 누적 시간 — study_session_subject_time을 subject_id로 합산한 결과. */
public record SubjectTimeSum(Long id, Long studySec, Long focusSec) {

    public static final SubjectTimeSum ZERO = new SubjectTimeSum(null, 0L, 0L);
}
