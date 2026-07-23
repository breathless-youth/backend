package project.study.studysession;

import project.study.common.BadRequestException;

public class InvalidSessionException extends BadRequestException {

    public InvalidSessionException(String message) {
        super(message);
    }
}
