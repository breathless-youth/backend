package project.study.studysession.dto;

import project.study.studysession.entity.EventStatus;

/** 기간 집계 쿼리의 프로젝션 — 상태별 이벤트 발생 건수. */
public record EventStatusCount(EventStatus status, long count) {}
