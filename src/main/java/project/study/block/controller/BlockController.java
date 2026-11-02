package project.study.block.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.block.dto.BlockListResponse;
import project.study.block.service.BlockService;

/**
 * 사용자 차단 조회·해제. 차단은 그룹과 상관없이 사용자 사이에 걸려 별도 도메인이지만, FE가 그룹 기능과 함께 찾도록 태그는 {@code 그룹}을 쓴다.
 * 태그 설명은 {@code GroupController}에만 둔다 — 컨트롤러마다 적으면 springdoc이 합치다 어긋난다.
 */
@Tag(name = "그룹")
@RestController
@RequestMapping("/api/blocks")
@RequiredArgsConstructor
public class BlockController {

    private final BlockService blockService;

    @Operation(summary = "차단 목록", description = """
                    내가 차단한 사람을 차단한 순서(오래된 것부터)로 준다. 차단은 공부방에서 멤버를 신고할 때 함께 걸리고, \
                    걸리면 어느 그룹 공부방에서든 서로 보이지 않는다. 이 목록에는 내가 건 차단만 나오고, 나를 차단한 사람은 알 수 없다.""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 차단한 사람이 없으면 `blockedUsers`가 빈 배열")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @GetMapping(version = "1")
    public BlockListResponse blocks(@AuthenticationPrincipal Long userId) {
        return blockService.blocks(userId);
    }

    @Operation(summary = "차단 해제", description = """
                    내가 건 차단을 푼다. 다음 공부방 입장부터 다시 보인다. \
                    상대도 나를 차단했다면 계속 보이지 않는다 — 어느 한쪽이라도 차단했으면 막힌다. \
                    차단하지 않은 사람이어도 같은 204다.""")
    @ApiResponse(responseCode = "204", description = "해제됨")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @DeleteMapping(value = "/{userId}", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "차단을 풀 사람의 userId", example = "42") @PathVariable("userId") long targetUserId) {
        blockService.unblock(userId, targetUserId);
    }
}
