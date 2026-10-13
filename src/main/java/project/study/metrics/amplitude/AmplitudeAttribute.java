package project.study.metrics.amplitude;

/** 리포트가 쓰는 Amplitude 유저 속성 (V28 amplitude_user_attribute). 값이 없으면 null. */
public record AmplitudeAttribute(String utmSource, boolean adClick, String osName) {}
