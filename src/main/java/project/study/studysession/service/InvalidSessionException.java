package project.study.studysession.service;

import project.study.common.BadRequestException;

public class InvalidSessionException extends BadRequestException {

    public InvalidSessionException(String message) {
        super(message);
    }
}
