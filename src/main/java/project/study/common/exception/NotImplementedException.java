package project.study.common.exception;

/** 계약(경로·DTO)만 있고 로직이 아직 없는 기능이 던진다 (501). 기능을 채우면 호출부와 함께 지운다. */
public class NotImplementedException extends RuntimeException {

    public NotImplementedException() {
        super("아직 준비 중인 기능입니다");
    }
}
