package project.study.point.service;

import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;

public class InsufficientPointsException extends ConflictException {
    public InsufficientPointsException() {
        super(ErrorCode.INSUFFICIENT_POINTS, "포인트가 모자랍니다");
    }
}
