package project.study.studysession.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.studysession.dto.ActiveSessionSnapshotResponse;
import project.study.studysession.dto.LegacyActiveSessionSnapshotRequest;
import project.study.studysession.dto.LegacyStudySessionCreateRequest;
import project.study.studysession.dto.SessionRecoveryResponse;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.service.StudySessionService;
import project.study.user.service.UserService;

/**
 * 구 앱(v1.2.x) 전용 세션 API — API-Version 헤더가 없거나 1인 요청만 여기로 온다 (ADR-0020).
 *
 * <p>v1.2.1 계약 그대로 본문·쿼리의 userId로 식별하고, 토큰 계약 핸들러({@link StudySessionController},
 * {@link ActiveStudySessionController})에 userId만 바꿔 위임하는 어댑터라 로직이 없다(멱등 재제출·재시도 포함).
 * 강제 업데이트(BY-531) 뒤 contract 시 이 클래스와 Legacy DTO를 삭제한다.
 */
// Swagger에 싣지 않는다 — springdoc은 같은 경로+메서드를 하나로 합쳐 토큰 계약(v2) 문서를 덮어쓴다.
// 구 앱 계약은 v1.2.1 그대로이며 ADR-0020의 표가 명세다
@Hidden
@RestController
@RequestMapping(value = "/api/study-sessions", version = "1")
@RequiredArgsConstructor
public class LegacyStudySessionController {

    private final StudySessionController studySessionController;
    private final ActiveStudySessionController activeStudySessionController;
    private final UserService userService;
    private final StudySessionService studySessionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<StudySessionResponse> create(@Valid @RequestBody LegacyStudySessionCreateRequest request) {
        return studySessionController.create(request.userId(), request.toRequest());
    }

    @GetMapping("/{id}")
    public StudySessionResponse detail(@PathVariable Long id, @RequestParam Long userId) {
        // 익명 userId 경로라 과목·할 일 이름은 싣지 않는다 (BY-734) — 구 앱은 그 필드를 읽지 않는다
        return studySessionService.findById(userId, id, false);
    }

    @PutMapping("/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@Valid @RequestBody LegacyActiveSessionSnapshotRequest request) {
        // 토큰 계약은 유효한 토큰이 곧 실존 유저지만 여기는 아무 숫자나 올 수 있다 — 없는 유저를 버퍼에 넣으면 flush 때
        // FK 위반으로 배치가 통째로 롤백되고 정상 유저 행까지 개별 재시도로 밀린다. 404(USER_NOT_FOUND)로 먼저 끊는다
        userService.getProfile(request.userId());
        activeStudySessionController.report(request.userId(), request.toRequest());
    }

    @GetMapping("/active")
    public ActiveSessionSnapshotResponse restore(@RequestParam Long userId) {
        return activeStudySessionController.restore(userId);
    }

    @PostMapping("/recovery")
    public SessionRecoveryResponse recover(@RequestParam Long userId) {
        return activeStudySessionController.recover(userId);
    }
}
