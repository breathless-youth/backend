package project.study.shop.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.mascot.service.MascotService;
import project.study.point.dto.PointReason;
import project.study.point.service.PointService;
import project.study.shop.dto.AccessoryRow;
import project.study.shop.dto.DecorationRow;
import project.study.shop.dto.PurchaseResponse;
import project.study.shop.dto.ShopCategory;
import project.study.shop.dto.ShopItem;
import project.study.shop.dto.ShopItemListResponse;
import project.study.shop.dto.ShopItemResponse;
import project.study.shop.repository.ShopRepository;
import project.study.user.service.MemberGuard;

/** 상품 목록과 구매. 상품·가격은 shop_item 행이라 앱을 다시 올리지 않고 추가하거나 바꾼다. */
@Service
@RequiredArgsConstructor
public class ShopService {

    private final ShopRepository repository;
    private final PointService pointService;
    private final MascotService mascotService;
    private final MemberGuard members;

    @Transactional(readOnly = true)
    public ShopItemListResponse list(Long userId, String language) {
        members.requireMember(userId);
        ShopProgress progress = progressOf(userId);
        List<AccessoryRow> accessories = repository.accessories(userId);
        DecorationRow decoration = repository.decoration(userId);
        List<ShopItemResponse> items = repository.findActive(language).stream()
                .map(item -> new ShopItemResponse(
                        item.id(),
                        item.code(),
                        item.category(),
                        item.mascotCode(),
                        item.slot(),
                        item.tag(),
                        item.name(),
                        item.imageUrl(),
                        item.price(),
                        progress.owns(item),
                        inUse(item, accessories, decoration),
                        progress.unlockOf(item)))
                .toList();
        return new ShopItemListResponse(items);
    }

    /**
     * 포인트 사용자 락을 먼저 잡고 가졌는지 본 뒤 잔액을 뺀다. 같은 구매 요청이 두 번 오면 두 번째는 락을 기다렸다가 {@code
     * ITEM_ALREADY_OWNED}를 받으므로 포인트는 한 번만 빠진다.
     */
    @Transactional
    public PurchaseResponse purchase(Long userId, long itemId) {
        members.requireMember(userId);
        pointService.lock(userId);
        ShopItem item = repository
                .findById(itemId)
                .filter(found -> repository.isActive(found.id()))
                .orElseThrow(() -> new NotFoundException(ErrorCode.SHOP_ITEM_NOT_FOUND, "없는 상품입니다: " + itemId));
        if (item.isReward()) {
            throw new ConflictException(ErrorCode.ITEM_NOT_PURCHASABLE, "포인트로 살 수 없는 상품입니다");
        }
        if (repository.ownedItemIds(userId).contains(itemId)) {
            throw new ConflictException(ErrorCode.ITEM_ALREADY_OWNED, "이미 가진 상품입니다");
        }
        if (item.category() == ShopCategory.MASCOT_ACCESSORY) {
            mascotService.requireUnlocked(userId, item.mascotCode());
        }
        int balance = pointService.spend(userId, PointReason.PURCHASE, "item:" + itemId, item.price(), item.name());
        repository.insertOwned(userId, itemId);
        return new PurchaseResponse(itemId, balance);
    }

    /** 가진 상품이 아니면 409 {@code ITEM_NOT_OWNED}. 꾸미기 저장이 부른다. */
    void requireOwned(long userId, ShopItem item) {
        if (!progressOf(userId).owns(item)) {
            throw new ConflictException(ErrorCode.ITEM_NOT_OWNED, "가지지 않은 상품입니다");
        }
    }

    private ShopProgress progressOf(long userId) {
        return new ShopProgress(
                repository.ownedItemIds(userId), mascotService.totalFocusSec(userId), repository.maxStreakDays(userId));
    }

    private static boolean inUse(ShopItem item, List<AccessoryRow> accessories, DecorationRow decoration) {
        return switch (item.category()) {
            case MASCOT_ACCESSORY -> accessories.stream().anyMatch(row -> row.itemId() == item.id());
            case TIMER_BACKGROUND -> Long.valueOf(item.id()).equals(decoration.timerBackgroundItemId());
        };
    }
}
