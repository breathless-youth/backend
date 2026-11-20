# BY-1366 공간 영상 방식과 서버 분류 서버 설계

2026-11-20 작성. [지라 BY-1366](https://breathless-youth.atlassian.net/browse/BY-1366), 브랜치 `feature/BY-1366-space-video-mode-tags`, 스토리 BY-1331.
결정 기록은 ADR-0047 13항의 바뀜 표기와 새 37항이다. 칸 삭제는 [BY-1367](https://breathless-youth.atlassian.net/browse/BY-1367)(V88)에서 한다.

## 배경

감시는 영상 공간을 그리는 방식이고, 독서실 감독관(`SPACE_PROCTOR`)은 그 방식의 첫 컨셉이다.
지금 서버는 공간 종류(`IMAGE`·`VIDEO`)만 알려 줘서, 앱은 감독관이 감시 공간인지 알 수 없다.
감시 공간은 들어오기·머무르기·나가기 장면과 소리를 따로 받아 재생하므로, 계속 반복하는 일반 영상과 재생기가 다르다.

공간 분류는 자유 문자열 칸 `shop_item.tag`와 앱의 매핑표로 정한다.
앱은 매핑표에 없는 코드를 분류 없이 두어서, 분류를 하나 늘릴 때마다 앱을 다시 배포해야 한다.
분류 이름도 앱 번역 파일에 있다.

이 작업은 영상 공간에 영상 방식을 두고, 분류를 서버 표가 이름까지 정하게 바꾼다.

## 결정 표

| 항목 | 결정 |
|---|---|
| 영상 방식 칸 | `shop_item.video_mode`(`LOOP`·`WATCH`). 영상 공간은 반드시 갖고, 그림 공간과 공간이 아닌 상품은 갖지 않는다. 기본값은 두지 않아 영상 공간을 등록할 때 방식을 반드시 적는다. |
| 감독관 | `WATCH`. 감시는 분류가 아니라 영상 방식이라 분류를 붙이지 않는다. |
| 새 형식 | 감시에 넣기 애매한 형식은 분류가 아니라 `video_mode`의 새 값으로 만든다. 새 값은 폴더 규칙·재생기·업로드 스크립트를 함께 정의할 때만 더한다. |
| 분류 표 | `shop_tag`(코드, 한국어 이름, 다른 언어 이름, 순서, 사용 여부)와 `shop_item_tag`(상품과 분류 연결). 한 상품에 분류를 여러 개 붙일 수 있다. 카테고리 칸은 두지 않고 모든 상품이 같이 쓴다. |
| 처음 넣을 분류 행 | 없다. 감독관은 분류를 붙이지 않고, 다른 공간은 아직 없다. |
| 분류 이름 번역 | 상품 이름과 같이 `COALESCE(names ->> :lang, name)`. 번역이 없으면 한국어다. |
| 쓰지 않는 분류 | `active = false`인 분류는 응답의 `tags`에서 뺀다. 연결 행은 남긴다. |
| 분류 순서 | `shop_tag.sort_order`, 같으면 `id` 순서로 보낸다. |
| `shop_item.tag` 칸 | V87에서는 남긴다. 롤링 배포 중인 옛 서버가 같은 상품 쿼리에서 이 칸을 읽는다(상품 목록·구매·결제 알림). BY-1367의 V88에서 삭제한다. |
| 응답의 `tag` 필드 | deprecated로 표시하고 V88까지 남긴다. 지금 웹 상점이 이 필드로 분류 칩을 정하고, 웹은 백엔드보다 늦게 배포될 수 있다. |
| 분류를 정하는 코드 | 응답에 싣는 것 말고는 `tag`를 쓰지 않는다. 분류는 모두 `shop_tag`·`shop_item_tag`가 정한다. |
| 상품 목록 응답 | `videoMode`와 `tags: [{code, name}]`를 더한다. 분류가 없으면 빈 배열이다. |
| 꾸미기 응답 | 공용 `EquippedItem`에 `videoMode`를 더한다. 마스코트 소품에서는 `assetKey`·`assetType`처럼 `null`이다. `tags`는 싣지 않는다. 앱은 분류를 공간 목록에서만 쓴다. |
| 락 | `SET LOCAL lock_timeout = '5s'`. `shop_item` 변경이 5초 안에 락을 못 잡으면 배포를 실패시킨다(V84와 같다). |

## 스키마 (V87)

```sql
SET LOCAL lock_timeout = '5s';

ALTER TABLE shop_item ADD COLUMN video_mode varchar(8) CHECK (video_mode IN ('LOOP', 'WATCH'));
UPDATE shop_item SET video_mode = 'WATCH' WHERE code = 'SPACE_PROCTOR';
ALTER TABLE shop_item ADD CONSTRAINT ck_shop_item_video_mode CHECK (
    (video_mode IS NOT NULL) = (asset_type IS NOT DISTINCT FROM 'VIDEO'));

CREATE TABLE shop_tag (
    id bigserial PRIMARY KEY,
    code varchar(64) NOT NULL UNIQUE,
    name varchar(60) NOT NULL,
    names jsonb NOT NULL DEFAULT '{}'::jsonb,
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true
);

CREATE TABLE shop_item_tag (
    item_id bigint NOT NULL REFERENCES shop_item (id),
    tag_id bigint NOT NULL REFERENCES shop_tag (id),
    PRIMARY KEY (item_id, tag_id)
);
CREATE INDEX idx_shop_item_tag_tag ON shop_item_tag (tag_id);
```

- `UPDATE`를 `CHECK`보다 먼저 둔다. V86이 넣은 감독관 행이 `VIDEO`라서 순서가 바뀌면 제약 추가가 실패한다.
- 감독관 말고 `VIDEO` 행이 있으면 제약 추가가 실패하고 배포가 멈춘다. 지금은 감독관 하나뿐이다.
- 상품과 분류는 지우지 않고 `active`로 내리므로, 외래 키에 `ON DELETE CASCADE`를 두지 않는다. 지우려 하면 연결 행 때문에 실패하는 쪽이 낫다.
- 이름과 다른 언어 이름 칸은 `shop_item`과 같은 길이·기본값이다.

## 코드

### 영상 방식

- `VideoMode` enum(`LOOP`, `WATCH`)을 `AssetType` 옆에 둔다.
- `ShopItem`에 `videoMode`를 더하고, `ShopRepository.ITEM_COLUMNS`와 매퍼 `item()`이 `video_mode`를 읽는다.
- `accessoriesOf`는 `i.*`로 같은 매퍼를 쓰므로 따로 바꾸지 않는다.
- `ShopItemResponse`·`EquippedItem`에 `videoMode`를 더하고, `ShopService.list`와 `DecorationService`가 채운다.

### 분류

- `ShopRepository`에 분류 쿼리를 하나 더한다. 상품 목록 쿼리가 판매를 멈춘 상품까지 모두 읽으므로 상품 ID로 거르지 않고 연결 전체를 읽는다.

```sql
SELECT it.item_id, t.code, COALESCE(t.names ->> :lang, t.name) AS name
FROM shop_item_tag it JOIN shop_tag t ON t.id = it.tag_id
WHERE t.active
ORDER BY it.item_id, t.sort_order, t.id
```

- 결과는 상품 ID → `List<ShopTagResponse>`로 묶는다. 소품 목록(`accessoriesOf`)이 같은 방식으로 따로 읽어 묶는다.
- `ShopService.list`는 `tags.getOrDefault(item.id(), List.of())`로 붙인다.
- `ShopTagResponse(code, name)`는 새 record다.

### `tag` 필드

- `ShopItem.tag`와 매퍼는 그대로 두고 응답에만 싣는다.
- `ShopItemResponse.tag`의 `@Schema`에 `deprecated = true`와 "`tags`로 대체. V88(BY-1367)에서 삭제"를 적는다.
- 다른 코드가 `tag`를 읽는 곳은 없다. 착수할 때 `rg '\.tag\(\)'`로 다시 확인한다.

## 문서

- ADR-0047 13항 앞에 "(2026-11-20 BY-1366로 바뀜: 분류는 `shop_tag`가 이름까지 정한다. …)" 표기를 붙인다. 15항과 같은 방식이다.
- ADR-0047 37항을 새로 둔다. 영상 방식, 감독관이 `WATCH`인 이유, 새 형식은 새 값으로 만든다는 방침, 서버 분류, `tag` 칸을 V88까지 남기는 이유를 적는다. 36항은 그대로 둔다.
- `docs/erd.dbml`에 `video_mode`, `shop_tag`, `shop_item_tag`를 더하고 `tag`에 "deprecated, V88에서 삭제" 메모를 단다.
- Swagger 설명은 `videoMode`·`tags`·`tag`에 위 내용을 적는다.

## 테스트

- `ShopSpaceItemApiTest`
  - 감독관은 `videoMode = WATCH`이고 `tags`가 빈 배열이다. `tag`는 deprecated 필드로 `STUDY_SPACE`가 그대로 온다(149행 검사를 이렇게 바꾼다).
  - 분류를 둘 붙인 공간은 `tags`가 분류 순서대로 오고, 이름이 `Accept-Language`에 맞게 번역된다. 번역이 없으면 한국어다.
  - `active = false`인 분류는 `tags`에서 빠진다.
  - 영상 공간에 `video_mode`가 없으면, 그림 공간이나 소품에 `video_mode`가 있으면, 값이 `LOOP`·`WATCH`가 아니면 `INSERT`가 제약에 걸린다(129~137행의 제약 검사와 같은 방식).
- 꾸미기 응답 테스트: 감독관을 배경으로 적용하면 `videoMode = WATCH`가 오고, 소품의 `videoMode`는 `null`이다.
- `PointShopFixture.freeItem`은 영상이면 `video_mode`를 받는다. `ShopProgressTest`의 `ShopItem` 생성자 호출은 새 칸을 더해 맞춘다.
- 검증: `./gradlew check -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` (Docker 필요).

## 범위 밖

- `shop_item.tag` 칸과 응답 필드 삭제 (BY-1367)
- 앱이 `videoMode`·`tags`를 읽는 일 (BY-1335)
- 분류를 관리하는 API. 분류 행은 상품처럼 마이그레이션으로 넣는다.
- infra 업로드 스크립트와 위키 정책은 같은 티켓의 다른 저장소 작업이다.
