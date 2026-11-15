package project.study.shop.dto;

/** 마스코트 소품을 끼는 부위. 부위마다 하나만 낀다. SET을 끼면 나머지가 풀리고, 나머지 가운데 하나를 끼면 SET이 풀린다. */
public enum AccessorySlot {
    HEAD,
    FACE,
    BODY,
    WINGS,
    HELD,
    UNDERFOOT,
    EFFECT,
    /** 캐릭터의 한 벌 옷. 다른 7칸의 소품과 함께 끼지 않는다 */
    SET
}
