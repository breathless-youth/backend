package project.study.planner.record;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.exception.ErrorResponse;

@Tag(name = "PlannerRecord", description = "05시 기준 개인 플래너 기록 편집. 기존 자정 통계와 랭킹의 원본은 보존한다")
@RestController
@RequestMapping(path = "/api/planner/records", version = "1")
@RequiredArgsConstructor
public class PlannerRecordController {
    private final PlannerRecordBatchService service;

    @Operation(summary = "개인 기록 일괄 저장", description = """
            최대 50개 작업을 요청 순서대로 독립 트랜잭션으로 처리한다. 한 작업의 실패는 다른 성공을 되돌리지 않는다.
            `operationId`는 사용자별 재시도 키(1~100자)다. 같은 키·본문은 최초 결과(오류 포함)를 반환하고, 다른 본문은 항목 409다.
            CREATE는 수동 기록만 추가한다. UPDATE/DELETE는 측정·수동 모두 가능하다. UPDATE의 subjectId=null은 과목 해제다.
            측정 묶음 키는 c:<원본 submissionStartedAt>, 수동은 m:<묶음 시작시각>이다. 이전 단독 기록은 c:id:<id>/m:id:<id>다.
            측정 키는 수정해도 유지한다. 수동 수정은 시작이 바뀌면 새 m: 키를 반환한다. 원본 자정 분할 조각은 한 묶음으로 편집된다.
            CREATE와 UPDATE에서 실제 변경한 경계만 분 단위로 내림한다. 변경 구간은 1분~24시간, 서버 현재 이전이며 다른 개인 기록과 겹치면 안 된다. 맞닿은 끝은 허용한다.
            UPDATE는 startedAt/endedAt 모두 생략하면 현재 전체 시각을 보존한다. 명시할 때는 조회의 fullStartedAt/fullEndedAt을 쓴다. 하나만 생략하면 항목 400이다.
            현재와 같은 경계는 초·소수초까지 보존한다. 시각이 모두 같으면 기존 짧은 측정 기록도 과목만 바꿀 수 있다.
            원본 측정·수동 세션/이벤트/랭킹 구간을 변경하지 않는다. 새 플래너만 수정본·삭제표식을 적용한다.
            과목만 바꾸면 현재 개인 기록의 총공부·순공을 유지한다. 시각을 바꾸면 이동 전 원본 이벤트와 새 구간의 겹침을 제외한다. PAUSE는 총공부·순공 모두에서, 그 외 이벤트는 순공에서 뺀다.
            원본 구간 밖으로 추가한 시간은 개인 플래너에서 집중으로 센다. 수동은 전부 집중이다.
            기존 /api/study-sessions와 자정 통계는 원본을 계속 보여주므로 새 플래너 화면의 집계와 다를 수 있다.
            """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = """
            {"operations":[
              {"operationId":"manual-001","action":"CREATE","startedAt":"2026-10-01T10:00:00Z","endedAt":"2026-10-01T11:00:00Z","subjectId":3,"timeZone":"Asia/Seoul"},
              {"operationId":"camera-002","action":"UPDATE","key":"c:2026-10-01T12:00:00Z","startedAt":"2026-10-01T12:15:00Z","endedAt":"2026-10-01T13:00:00Z","subjectId":3},
              {"operationId":"subject-only-004","action":"UPDATE","key":"c:2026-10-01T14:00:12.123456Z","subjectId":8},
              {"operationId":"delete-003","action":"DELETE","key":"m:2026-09-30T10:00:00Z"}
            ]}
            """)))
    @ApiResponse(
            responseCode = "200",
            description = "항목별 성공/실패. status는 항목의 HTTP 의미이며 전체 응답은 200",
            content =
                    @Content(
                            schema = @Schema(implementation = PlannerRecordBatchResponse.class),
                            examples = {
                                @ExampleObject(name = "부분 성공", value = """
                        {"results":[
                          {"operationId":"manual-001","success":true,"key":"m:2026-10-01T10:00:00Z","status":200,"code":null,"message":null},
                          {"operationId":"camera-002","success":false,"key":null,"status":409,"code":"CONFLICT","message":"다른 개인 기록과 시간이 겹칩니다"},
                          {"operationId":"subject-only-004","success":true,"key":"c:2026-10-01T14:00:12.123456Z","status":200,"code":null,"message":null},
                          {"operationId":"delete-003","success":false,"key":null,"status":404,"code":"NOT_FOUND","message":"기록을 찾을 수 없습니다"}
                        ]}
                        """),
                                @ExampleObject(name = "입력 검증", value = """
                        {"results":[{"operationId":"invalid-001","success":false,"key":null,"status":400,"code":"BAD_REQUEST","message":"기록은 1분 이상 24시간 이하여야 합니다"}]}
                        """),
                                @ExampleObject(name = "다른 본문 재시도", value = """
                        {"results":[{"operationId":"manual-001","success":false,"key":null,"status":409,"code":"CONFLICT","message":"operationId에 다른 요청을 사용할 수 없습니다"}]}
                        """)
                            }))
    @ApiResponse(
            responseCode = "400",
            description = "빈 작업 배열·50개 초과·잘못된 JSON",
            content =
                    @Content(
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"code\":\"VALIDATION_FAILED\",\"message\":\"operations: 작업을 하나 이상 보내야 합니다\"}")))
    @ApiResponse(
            responseCode = "401",
            description = "인증 필요",
            content =
                    @Content(
                            examples = @ExampleObject(value = "{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다\"}")))
    @PostMapping("/batch")
    public PlannerRecordBatchResponse batch(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody PlannerRecordBatchRequest request) {
        return service.batch(userId, request);
    }
}
