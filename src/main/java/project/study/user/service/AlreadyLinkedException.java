package project.study.user.service;

/** 이미 소셜 계정인 사용자가 link를 불렀다 — 409 {@code ALREADY_LINKED}. */
public class AlreadyLinkedException extends RuntimeException {

    public AlreadyLinkedException() {
        super("이미 소셜 계정으로 로그인한 사용자입니다");
    }
}
