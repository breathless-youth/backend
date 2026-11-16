package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 효과 그림의 조각 하나. 위치와 크기는 그림 판에 대한 비율(0~1)이다. */
public record EffectPiece(
        @Schema(description = "조각의 왼쪽 위치(판 폭에 대한 비율)", example = "0.12")
        double x,

        @Schema(description = "조각의 위쪽 위치(판 높이에 대한 비율)", example = "0.3")
        double y,

        @Schema(description = "조각의 폭(판 폭에 대한 비율)", example = "0.2")
        double width,

        @Schema(description = "조각의 높이(판 높이에 대한 비율)", example = "0.2")
        double height,

        @Schema(description = """
                        움직임 종류. 서버는 값을 가두지 않으며 앱이 모르는 값은 멈춘 그림으로 그린다. \
                        예전 이름: float·fall·rise·twinkle·flutter·pulse·sway·bob·pop. \
                        새 이름: glow(밝기가 오르내리고 빛줄기가 가장자리를 돈다)·ripple(몸을 감싼 빛이 일렁인다)·\
                        drift(떠오르며 맴돌고 반짝인다)·spread(바깥 위로 흩어졌다 모인다)·wipe(왼쪽에서 오른쪽으로 그려졌다 사라진다)·\
                        spiral(몸 둘레를 돌며 앞뒤를 지난다)·starry(원판이 돌고 별이 반짝인다)·flow(물결처럼 흔들리며 색이 바뀐다)·\
                        flash(짧게 번쩍이고 잔광이 사라진다)·countdown(원 안이 쓸리며 3·2·1)""", example = "glow") String motion,

        @Schema(
                description = """
                        띠 그림(프레임을 가로로 이은 한 장) 주소. 한 장은 이 조각의 자리와 같은 영역이다. \
                        spriteUrl·spriteFrames·spriteDurationMs가 모두 있으면 motion 대신 띠 그림을 넘겨 그린다. 없으면 null""",
                example =
                        "https://web.focusmakers.app/avatars/shop/stopwatch/effect/acc_stopwatch_effect_flame_aura-sprite.webp",
                nullable = true)
        String spriteUrl,

        @Schema(description = "띠 그림의 장 수. 없으면 null", example = "12", nullable = true)
        Integer spriteFrames,

        @Schema(description = "모든 장을 한 번 넘기는 데 걸리는 시간(ms). 없으면 null", example = "1200", nullable = true)
        Integer spriteDurationMs) {}
