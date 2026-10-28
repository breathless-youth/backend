package project.study.push.service;

import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FcmOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import project.study.push.dto.PushMessage;
import project.study.push.dto.PushResult;
import project.study.push.repository.PushTokenRepository;

/**
 * 사용자 ID로 기기 토큰을 찾아 FCM으로 보낸다 — 시나리오 잡이 부르는 유일한 입구다. 빈은 FcmConfig가 만든다.
 *
 * <p>토큰은 UNREGISTERED일 때만 지운다. INVALID_ARGUMENT·SENDER_ID_MISMATCH 등은 메시지 형식이나 자격증명(dev에 운영 키 등)
 * 같은 우리 쪽 문제로도 나서, 지우면 멀쩡한 토큰이 통째로 사라진다. 토큰 값은 로그·Sentry 어디에도 남기지 않는다.
 */
@Slf4j
public class PushSender {

    // FCM sendEachForMulticast 한 번에 실을 수 있는 토큰 상한
    static final int MAX_TOKENS_PER_CALL = 500;

    private final PushTokenRepository pushTokenRepository;
    // 자격증명이 없으면 null — 로컬·테스트 환경은 보내지 않고 그대로 돈다
    private final FirebaseMessaging messaging;

    public PushSender(PushTokenRepository pushTokenRepository, FirebaseMessaging messaging) {
        this.pushTokenRepository = pushTokenRepository;
        this.messaging = messaging;
    }

    /** 끝날 때까지 기다린다. 결과의 sent는 FCM 접수 수일 뿐 기기 도착 수가 아니다. */
    public PushResult send(Collection<Long> userIds, PushMessage message) {
        if (messaging == null || userIds.isEmpty()) {
            return PushResult.NONE;
        }
        List<String> tokens = pushTokenRepository.findTokensByUserIdIn(userIds);
        int sent = 0;
        List<String> unregistered = new ArrayList<>();
        Map<String, Integer> failures = new TreeMap<>();

        for (int from = 0; from < tokens.size(); from += MAX_TOKENS_PER_CALL) {
            List<String> chunk = tokens.subList(from, Math.min(from + MAX_TOKENS_PER_CALL, tokens.size()));
            BatchResponse batch;
            try {
                batch = messaging.sendEachForMulticast(toMulticast(chunk, message));
            } catch (FirebaseMessagingException e) {
                // 토큰별 오류는 응답 안에 온다 — 여기로 오는 건 호출 자체가 실패한 경우라 묶음 전체를 실패로 센다
                failures.merge(codeName(e.getMessagingErrorCode()), chunk.size(), Integer::sum);
                continue;
            }
            List<SendResponse> responses = batch.getResponses();
            for (int i = 0; i < responses.size(); i++) {
                SendResponse response = responses.get(i);
                if (response.isSuccessful()) {
                    sent++;
                    continue;
                }
                MessagingErrorCode code = response.getException().getMessagingErrorCode();
                if (code == MessagingErrorCode.UNREGISTERED) {
                    unregistered.add(chunk.get(i));
                } else {
                    failures.merge(codeName(code), 1, Integer::sum);
                }
            }
        }

        if (!unregistered.isEmpty()) {
            pushTokenRepository.deleteByTokenIn(unregistered);
        }
        int failed = failures.values().stream().mapToInt(Integer::intValue).sum();
        log.info("푸시 발송 type={} sent={} failed={} removed={}", message.type(), sent, failed, unregistered.size());
        if (failed > 0) {
            Sentry.captureMessage("푸시 발송 실패 type=" + message.type() + " " + failures, SentryLevel.WARNING);
        }
        return new PushResult(sent, failed, unregistered.size());
    }

    private static MulticastMessage toMulticast(List<String> tokens, PushMessage message) {
        MulticastMessage.Builder builder = MulticastMessage.builder();
        if (message.pushId() != null) {
            builder.putData("push_id", message.pushId().toString());
        }
        return builder.addAllTokens(tokens)
                .setNotification(Notification.builder()
                        .setTitle(message.title())
                        .setBody(message.body())
                        .build())
                .putData("link", message.link())
                .putData("type", message.type())
                // iOS는 sound가 없으면 소리 없이 배너만 뜬다
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder().setSound("default").build())
                        .build())
                // 콘솔 Cloud Messaging 보고 탭에서 종류별로 발송·열림 수를 보기 위해
                .setFcmOptions(FcmOptions.withAnalyticsLabel(message.type()))
                .build();
    }

    private static String codeName(MessagingErrorCode code) {
        return code == null ? "UNKNOWN" : code.name();
    }
}
