package project.study.shop.dto;

/** user_mascot_accessory 테이블 한 행. */
public record AccessoryRow(String mascotCode, AccessorySlot slot, long itemId) {}
