package project.study.studysession.service;

import project.study.common.ConflictException;

public class DuplicateSessionException extends ConflictException {

    public DuplicateSessionException(String message) {
        super(message);
    }
}
