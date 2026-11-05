package project.study.push.dto;

import java.util.Objects;

/** 알림에 보이는 제목과 본문 한 벌. 언어마다 하나씩 만든다. */
public record PushText(String title, String body) {

    public PushText {
        Objects.requireNonNull(title);
        Objects.requireNonNull(body);
    }
}
