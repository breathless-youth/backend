package project.study.metrics.report.render;

import static project.study.metrics.slack.SlackMessage.bold;

import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.ExperimentSection;
import project.study.metrics.slack.SlackMessage;
import project.study.metrics.slack.SlackMessage.Builder;

/** 🔔 첫 세션 5분 푸시 실험 스레드 (BY-1070). 실험을 처음 보는 팀원도 읽히게 대상·방식·목표부터 쓴다. */
public final class ExperimentRenderer {

    public static final String TITLE = "🔔 첫 세션 5분 이상 유도 Push 알림 A/B 테스트";

    private ExperimentRenderer() {}

    public static SlackMessage render(ExperimentSection section) {
        Builder out = SlackMessage.builder(TITLE)
                .item("대상 = 가입 후 첫 세션 미시작 사용자 (D0 가입 후 %s ~ D1 20시 기준)".formatted(delay(section)))
                .item("방식 = 무작위 50:50 (알림 발송 · 미발송)")
                .item("목표 = 첫 세션 순공 5분 달성, 그 결과 7일째 재방문");
        if (section.empty()) {
            return out.item("인원 = 아직 없음").build();
        }
        out.item(
                "인원 = 발송 ",
                bold(section.push().assigned()),
                "명 · 미발송 ",
                bold(section.control().assigned()),
                "명");
        row(
                out,
                "① 첫 세션 순공 5분 이상 (핵심)",
                section.push().firstFiveMinutes(),
                section.control().firstFiveMinutes());
        row(out, "② 첫 세션 시작", section.push().started(), section.control().started());
        row(out, "③ D7 재방문 (판정)", section.push().daySeven(), section.control().daySeven());
        out.sub("D7 = 가입 7일째 당일 세션 시작 · 집계 완료자만 · 목표 집단별 %d명 중 %d명"
                .formatted(section.targetPerArm(), section.judgedPerArm()));
        row(
                out,
                "④ 알림 권한 해제 (부작용)",
                section.push().notificationsOff(),
                section.control().notificationsOff());
        out.sub("앱 삭제 추정 = 발송 %d명 (비율 제외 · 미발송은 확인 불가)".formatted(section.push().tokenGone()));
        return out.quote("목표 인원 전 차이는 우연 가능 · 결론 보류").build();
    }

    private static void row(Builder out, String label, Rate push, Rate control) {
        out.item(
                label + " = 발송 ",
                bold(Fmt.percent(push)),
                Fmt.fraction(push),
                " · 미발송 ",
                bold(Fmt.percent(control)),
                Fmt.fraction(control),
                gap(push, control));
    }

    // 설정값을 그대로 쓴다 — 90분처럼 시간으로 나누어떨어지지 않으면 분으로
    private static String delay(ExperimentSection section) {
        long minutes = section.d0Delay().toMinutes();
        return minutes % 60 == 0 ? minutes / 60 + "시간" : minutes + "분";
    }

    // 두 쪽 모두 분모가 있을 때만 차이를 쓴다
    private static String gap(Rate push, Rate control) {
        if (!push.defined() || !control.defined()) {
            return "";
        }
        long points = Math.round(100 * (push.ratio() - control.ratio()));
        return ", 차이 %s%d%%p".formatted(points > 0 ? "+" : "", points);
    }
}
