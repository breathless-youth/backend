package project.study.mascot.dto;

/** 마스코트를 여는 방법. */
public enum MascotUnlockType {
    /** 누적 순공이 기준을 넘으면 열린다 */
    FOCUS_TIME,
    /** 포인트로 사면 열린다 */
    POINTS
}
