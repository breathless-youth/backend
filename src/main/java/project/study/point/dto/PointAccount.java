package project.study.point.dto;

/** 잔액과 그 가운데 결제로 산 몫. */
public record PointAccount(int balance, int paidBalance) {

    public int freeBalance() {
        return balance - paidBalance;
    }
}
