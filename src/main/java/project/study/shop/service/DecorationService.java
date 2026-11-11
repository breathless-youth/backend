package project.study.shop.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.mascot.service.MascotService;
import project.study.point.service.PointLocks;
import project.study.shop.dto.AccessorySlot;
import project.study.shop.dto.DecorationResponse;
import project.study.shop.dto.DecorationRow;
import project.study.shop.dto.EquippedAccessory;
import project.study.shop.dto.EquippedItem;
import project.study.shop.dto.MascotAppearance;
import project.study.shop.dto.ShopCategory;
import project.study.shop.dto.ShopItem;
import project.study.shop.repository.ShopRepository;
import project.study.user.service.MemberGuard;

/** 착용한 소품과 적용한 타이머 배경. 가진 상품만 쓸 수 있고 itemId가 null이면 벗거나 기본으로 되돌린다. */
@Service
@RequiredArgsConstructor
public class DecorationService {

    private final ShopRepository repository;
    private final ShopService shopService;
    private final MascotService mascotService;
    private final MemberGuard members;
    private final PointLocks locks;

    @Transactional(readOnly = true)
    public DecorationResponse get(Long userId) {
        members.requireMember(userId);
        List<EquippedAccessory> accessories = repository.accessories(userId).stream()
                .map(row -> new EquippedAccessory(row.mascotCode(), row.slot(), equipped(row.itemId())))
                .toList();
        DecorationRow row = repository.decoration(userId);
        return new DecorationResponse(
                mascotService.selectedCode(userId), accessories, equipped(row.timerBackgroundItemId()));
    }

    @Transactional
    public DecorationResponse equipAccessory(Long userId, String mascotCode, AccessorySlot slot, Long itemId) {
        members.requireMember(userId);
        locks.lockUser(userId);
        if (itemId == null) {
            repository.deleteAccessory(userId, mascotCode, slot);
            return get(userId);
        }
        ShopItem item = ownedItem(userId, itemId, ShopCategory.MASCOT_ACCESSORY);
        if (!mascotCode.equals(item.mascotCode()) || slot != item.slot()) {
            throw new BadRequestException("그 마스코트의 그 부위에 끼는 소품이 아닙니다");
        }
        repository.saveAccessory(userId, mascotCode, slot, itemId);
        return get(userId);
    }

    @Transactional
    public DecorationResponse setTimerBackground(Long userId, Long itemId) {
        members.requireMember(userId);
        locks.lockUser(userId);
        Long checked = itemId == null
                ? null
                : ownedItem(userId, itemId, ShopCategory.TIMER_BACKGROUND).id();
        repository.saveDecoration(userId, new DecorationRow(checked));
        return get(userId);
    }

    /** 다른 사람에게 보이는 모습 — 그룹 책상이 쓴다. 넘긴 사람마다 값이 있고 비회원은 첫 마스코트에 소품이 없다. */
    @Transactional(readOnly = true)
    public Map<Long, MascotAppearance> appearancesOf(Collection<Long> userIds) {
        Map<Long, String> mascots = mascotService.selectedCodes(userIds);
        var worn = repository.accessoriesOf(userIds);
        Map<Long, MascotAppearance> appearances = new HashMap<>();
        mascots.forEach((userId, mascotCode) -> appearances.put(
                userId,
                new MascotAppearance(
                        mascotCode,
                        worn.getOrDefault(userId, List.of()).stream()
                                .filter(entry -> entry.getKey().mascotCode().equals(mascotCode))
                                .map(entry -> new EquippedAccessory(
                                        mascotCode, entry.getKey().slot(), EquippedItem.from(entry.getValue())))
                                .toList())));
        return appearances;
    }

    private ShopItem ownedItem(long userId, long itemId, ShopCategory category) {
        ShopItem item = repository
                .findById(itemId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SHOP_ITEM_NOT_FOUND, "없는 상품입니다: " + itemId));
        if (item.category() != category) {
            throw new BadRequestException("여기에 쓸 수 있는 종류의 상품이 아닙니다");
        }
        shopService.requireOwned(userId, item);
        return item;
    }

    private EquippedItem equipped(Long itemId) {
        return itemId == null
                ? null
                : EquippedItem.from(repository.findById(itemId).orElse(null));
    }
}
