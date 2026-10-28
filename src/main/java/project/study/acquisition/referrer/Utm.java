package project.study.acquisition.referrer;

/** 설치 정보에서 읽은 UTM 값. 없는 항목은 null이고 Utm 자체는 항상 존재한다. */
public record Utm(String source, String medium, String campaign) {}
