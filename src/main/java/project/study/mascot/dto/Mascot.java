package project.study.mascot.dto;

/** mascot 테이블 한 행. price가 있으면 포인트로 사서 여는 캐릭터이고 unlockFocusSec은 쓰지 않는다. */
public record Mascot(String code, int unlockFocusSec, Integer price) {

    public boolean priced() {
        return price != null;
    }
}
