package project.study.planner.record;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.NotFoundException;
import project.study.studysession.repository.ManualStudySessionLockRepository;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerRecordBatchService {
    private final PlannerRecordRepository repository;
    private final PlannerRecordMutationService mutations;
    private final ManualStudySessionLockRepository locks;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper mapper;

    public PlannerRecordBatchResponse batch(Long userId, PlannerRecordBatchRequest request) {
        return new PlannerRecordBatchResponse(request.operations().stream()
                .map(operation -> apply(userId, operation))
                .toList());
    }

    private PlannerRecordResult apply(Long userId, PlannerRecordOperation operation) {
        if (operation == null
                || operation.operationId() == null
                || operation.operationId().isBlank()
                || operation.operationId().length() > 100) {
            return PlannerRecordResult.failure(operation, 400, "BAD_REQUEST", "operationId는 1~100자여야 합니다");
        }
        String hash = hash(operation);
        try {
            return transaction().execute(status -> {
                locks.lockUser(userId);
                return repository.replay(userId, operation, hash).orElseGet(() -> {
                    PlannerRecordResult result = mutations.mutate(userId, operation);
                    repository.saveResult(userId, operation, hash, result);
                    return result;
                });
            });
        } catch (RuntimeException error) {
            PlannerRecordResult failed = failure(operation, error);
            try {
                return transaction().execute(status -> {
                    locks.lockUser(userId);
                    return repository.replay(userId, operation, hash).orElseGet(() -> {
                        repository.saveResult(userId, operation, hash, failed);
                        return failed;
                    });
                });
            } catch (ConflictException conflict) {
                return failure(operation, conflict);
            } catch (RuntimeException storageFailure) {
                log.error(
                        "planner record result persistence failed: operationId={}",
                        operation.operationId(),
                        storageFailure);
                return PlannerRecordResult.failure(operation, 500, "INTERNAL_ERROR", "기록 저장 중 오류가 발생했습니다");
            }
        }
    }

    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static PlannerRecordResult failure(PlannerRecordOperation operation, RuntimeException error) {
        if (error instanceof BadRequestException bad) {
            return PlannerRecordResult.failure(operation, 400, bad.getCode().name(), bad.getMessage());
        }
        if (error instanceof NotFoundException missing) {
            return PlannerRecordResult.failure(operation, 404, missing.getCode().name(), missing.getMessage());
        }
        if (error instanceof ConflictException conflict) {
            return PlannerRecordResult.failure(
                    operation, 409, conflict.getCode().name(), conflict.getMessage());
        }
        log.error("planner record operation failed: operationId={}", operation.operationId(), error);
        return PlannerRecordResult.failure(operation, 500, "INTERNAL_ERROR", "기록 저장 중 오류가 발생했습니다");
    }

    private String hash(PlannerRecordOperation operation) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(mapper.writeValueAsString(operation.hashBody())
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
