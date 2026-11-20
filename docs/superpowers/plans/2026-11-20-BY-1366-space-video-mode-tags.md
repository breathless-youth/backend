# BY-1366 공간 영상 방식·서버 분류 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 영상 공간에 `video_mode`(LOOP·WATCH)를 두고, 공간 분류를 `shop_tag`·`shop_item_tag` 표가 이름까지 정하게 한다.

**Architecture:** V87 마이그레이션 하나가 칸·제약·표를 모두 더한다. 상품 목록은 기존 쿼리에 `video_mode`를 더하고, 분류는 별도 쿼리로 읽어 상품 ID별로 묶어 붙인다. `shop_item.tag`는 V88(BY-1367)까지 응답에만 남긴다.

**Tech Stack:** Spring Boot 4.1, Java 25, JdbcClient, PostgreSQL, Flyway, Testcontainers, MockMvcTester.

설계서: `docs/superpowers/specs/2026-11-20-BY-1366-space-video-mode-tags-design.md`

## Global Constraints

- 마이그레이션 번호는 V87이다. Flyway `outOfOrder`가 꺼져 있다.
- `SET LOCAL lock_timeout = '5s'`를 마이그레이션 첫 줄에 둔다.
- `shop_item.tag` 칸과 응답의 `tag` 필드는 삭제하지 않는다(BY-1367).
- 커밋은 사용자 승인 뒤에만 한다. 각 태스크의 "커밋" 단계는 커밋 계획 표에 올릴 묶음을 뜻한다. 작성자는 사용자 본인 계정(wonza-hub)이다.
- 검증 명령: `./gradlew check -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` (Docker 필요).
- 한 태스크의 테스트만 돌릴 때: `./gradlew test --tests "project.study.shop.*" -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
- 주석은 "왜"만 쓰고, 티켓 번호·디자인 번호·마크다운을 넣지 않는다.

---

### Task 1: V87 마이그레이션과 영상 방식

**Files:**
- Create: `src/main/resources/db/migration/V87__shop_item_video_mode_and_tags.sql`
- Create: `src/main/java/project/study/shop/dto/VideoMode.java`
- Modify: `src/main/java/project/study/shop/dto/ShopItem.java` (마지막 컴포넌트 뒤에 `videoMode`)
- Modify: `src/main/java/project/study/shop/repository/ShopRepository.java` (`ITEM_COLUMNS`, `item()`)
- Modify: `src/main/java/project/study/shop/dto/ShopItemResponse.java` (`assetType` 뒤에 `videoMode`)
- Modify: `src/main/java/project/study/shop/dto/EquippedItem.java` (`assetType` 뒤에 `videoMode`, `from`)
- Modify: `src/main/java/project/study/shop/service/ShopService.java` (`list`의 생성자 호출)
- Modify: `src/test/java/project/study/point/PointShopFixture.java` (`freeItem`)
- Modify: `src/test/java/project/study/shop/service/ShopProgressTest.java` (`ShopItem` 생성자 세 곳)
- Test: `src/test/java/project/study/shop/ShopSpaceItemApiTest.java`

**Interfaces:**
- Produces: `enum VideoMode { LOOP, WATCH }`, `ShopItem.videoMode()`, `ShopItemResponse.videoMode()`, `EquippedItem.videoMode()`, 표 `shop_tag`·`shop_item_tag`(Task 2가 쓴다).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ShopSpaceItemApiTest`에 아래 두 테스트를 더하고, `독서실_감독관은_처음부터_카탈로그에_있는_무료_영상_공간이다`의 `assetType` 검사 뒤에 한 줄을 더한다.

```java
        assertThat(ko.videoMode()).isEqualTo(VideoMode.WATCH);
```

```java
    @Test
    void 영상_공간만_영상_방식을_갖고_값은_LOOP와_WATCH뿐이다() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO shop_item (code, category, name, asset_key, asset_type)
                        VALUES ('NO_MODE', 'TIMER_BACKGROUND', '배경', 'bg/1', 'VIDEO')""").update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO shop_item (code, category, name, asset_key, asset_type, video_mode)
                        VALUES ('IMAGE_MODE', 'TIMER_BACKGROUND', '배경', 'bg/1', 'IMAGE', 'LOOP')""").update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO shop_item (code, category, mascot_code, slot, name, price, video_mode)
                        VALUES ('HAT_MODE', 'MASCOT_ACCESSORY', 'STOPWATCH', 'HEAD', '모자', 100, 'LOOP')""").update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO shop_item (code, category, name, asset_key, asset_type, video_mode)
                        VALUES ('BAD_MODE', 'TIMER_BACKGROUND', '배경', 'bg/1', 'VIDEO', 'SLIDE')""").update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 감독관을_배경으로_적용하면_꾸미기_응답에_감시_방식이_온다() {
        long proctor = jdbc.sql("SELECT id FROM shop_item WHERE code = 'SPACE_PROCTOR'")
                .query(Long.class)
                .single();
        long user = fixture.member();

        assertThat(put(user, "/api/decorations/timer-background", "{\"itemId\":" + proctor + "}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.timerBackground.videoMode", v -> assertThat(v).isEqualTo("WATCH"));
    }
```

import에 `project.study.shop.dto.VideoMode`를 더한다.

- [ ] **Step 2: 테스트가 실패하는지 본다**

Run: `./gradlew test --tests "project.study.shop.ShopSpaceItemApiTest" -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
Expected: 컴파일 실패(`VideoMode`, `videoMode()` 없음).

- [ ] **Step 3: 마이그레이션을 쓴다**

`V87__shop_item_video_mode_and_tags.sql`:

```sql
-- 영상 공간의 재생 방식과 서버가 정하는 공간 분류
-- 감시(WATCH)는 들어오기·머무르기·나가기 장면과 소리를 따로 재생하는 방식이고, 계속 반복하는 일반 영상(LOOP)과 재생기가 다르다
-- 락 대기 상한: shop_item을 바꾸는 동안 상품 조회가 막히므로 5초 안에 못 잡으면 배포를 실패시킨다(V84와 같다)
SET LOCAL lock_timeout = '5s';

ALTER TABLE shop_item ADD COLUMN video_mode varchar(8) CHECK (video_mode IN ('LOOP', 'WATCH'));
-- V86의 감독관 행이 이미 VIDEO라 제약보다 먼저 채운다
UPDATE shop_item SET video_mode = 'WATCH' WHERE code = 'SPACE_PROCTOR';
-- 기본값을 두지 않아 영상 공간을 넣을 때 방식을 반드시 적게 한다
ALTER TABLE shop_item ADD CONSTRAINT ck_shop_item_video_mode CHECK (
    (video_mode IS NOT NULL) = (asset_type IS NOT DISTINCT FROM 'VIDEO'));

-- 분류를 늘릴 때 앱을 다시 배포하지 않도록 이름까지 서버가 갖는다
-- shop_item.tag는 배포 중인 옛 서버와 아직 바뀌지 않은 웹이 읽어 다음 릴리즈에 지운다
CREATE TABLE shop_tag (
    id bigserial PRIMARY KEY,
    code varchar(64) NOT NULL UNIQUE,
    name varchar(60) NOT NULL,
    -- 다른 언어 이름. 키는 주 언어(en, ja, zh)이고 zh에는 번체를 넣는다. 없는 언어는 name(한국어)으로 보여 준다
    names jsonb NOT NULL DEFAULT '{}'::jsonb,
    sort_order integer NOT NULL DEFAULT 0,
    -- false면 응답에서 뺀다. 연결은 남겨 다시 켜면 돌아온다
    active boolean NOT NULL DEFAULT true
);

-- 상품과 분류는 지우지 않고 active로 내리므로 CASCADE를 두지 않는다
CREATE TABLE shop_item_tag (
    item_id bigint NOT NULL REFERENCES shop_item (id),
    tag_id bigint NOT NULL REFERENCES shop_tag (id),
    PRIMARY KEY (item_id, tag_id)
);
CREATE INDEX idx_shop_item_tag_tag ON shop_item_tag (tag_id);
```

- [ ] **Step 4: enum과 DTO를 고친다**

`VideoMode.java`:

```java
package project.study.shop.dto;

/** 영상 공간의 재생 방식. 방식마다 파일 구성과 앱의 재생기가 다르다. */
public enum VideoMode {
    LOOP,
    WATCH
}
```

`ShopItem`: `AssetType assetType` 뒤에 `VideoMode videoMode`를 더한다.

`ShopRepository.ITEM_COLUMNS` 끝을 `"..., active, asset_key, asset_type, video_mode"`로 바꾸고, `item()`에 아래를 더한다.

```java
        String videoMode = rs.getString("video_mode");
        // ... new ShopItem(..., assetType == null ? null : AssetType.valueOf(assetType),
                videoMode == null ? null : VideoMode.valueOf(videoMode));
```

`ShopItemResponse`: `AssetType assetType` 뒤에 더한다.

```java
        @Schema(
                description = "영상 공간의 재생 방식. LOOP는 계속 반복하는 영상, WATCH는 들어오기·머무르기·나가기 장면과 소리를 따로 재생하는 감시 방식이다."
                        + " 영상 공간이 아니면 null",
                example = "WATCH")
        VideoMode videoMode
```

`EquippedItem`: 같은 필드를 `assetType` 뒤에 더하고, `from`에서 `item.videoMode()`를 넘긴다.

`ShopService.list`: 생성자 호출의 `item.assetType()` 뒤에 `item.videoMode()`를 더한다.

`ShopProgressTest`: 세 `new ShopItem(...)`의 마지막 `null` 뒤에 `null`을 하나 더한다.

`PointShopFixture.freeItem`: 영상이면 일반 영상 방식을 넣는다.

```java
    /** 얻는 방법 없이 모두가 가지는 무료 공간. 영상이면 일반 반복 영상(LOOP)이다. */
    public long freeItem(String assetKey, String assetType) {
        return jdbc.sql("""
                        INSERT INTO shop_item (code, category, name, asset_key, asset_type, video_mode)
                        VALUES (:code, 'TIMER_BACKGROUND', '무료', :key, :type,
                                CASE WHEN CAST(:type AS varchar) = 'VIDEO' THEN 'LOOP' END) RETURNING id""")
                .param("code", "ITEM_" + UUID.randomUUID())
                .param("key", assetKey)
                .param("type", assetType)
                .query(Long.class)
                .single();
    }
```

- [ ] **Step 5: 테스트가 통과하는지 본다**

Run: `./gradlew test --tests "project.study.shop.*" -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
Expected: PASS. 기존 `ShopApiTest`·`ShopAppearanceTest`·`PaidItemApiTest`도 통과한다(소품의 `videoMode`는 null).

- [ ] **Step 6: 커밋 묶음**

`feat: 영상 공간에 재생 방식을 두고 감독관을 감시 방식으로 둔다 (BY-1366)`. Task 1의 파일 전부.

---

### Task 2: 서버 분류를 상품 목록에 싣는다

**Files:**
- Create: `src/main/java/project/study/shop/dto/ShopTagResponse.java`
- Modify: `src/main/java/project/study/shop/repository/ShopRepository.java` (`tagsByItem` 추가)
- Modify: `src/main/java/project/study/shop/dto/ShopItemResponse.java` (`tag` 설명, 끝에 `tags`)
- Modify: `src/main/java/project/study/shop/service/ShopService.java` (`list`)
- Modify: `src/test/java/project/study/point/PointShopFixture.java` (`tag`, `tagItem`, `deactivateTag`)
- Test: `src/test/java/project/study/shop/ShopSpaceItemApiTest.java`

**Interfaces:**
- Consumes: Task 1의 `shop_tag`·`shop_item_tag` 표, `ShopItemResponse`의 `videoMode` 위치.
- Produces: `record ShopTagResponse(String code, String name)`, `Map<Long, List<ShopTagResponse>> ShopRepository.tagsByItem(String language)`, `ShopItemResponse.tags()`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`PointShopFixture`에 더한다.

```java
    /** 공간 분류. 코드는 겹치지 않게 만든다. names는 jsonb 문자열이다. */
    public long tag(String name, String names, int sortOrder) {
        return jdbc.sql("""
                        INSERT INTO shop_tag (code, name, names, sort_order)
                        VALUES (:code, :name, CAST(:names AS jsonb), :sort) RETURNING id""")
                .param("code", "TAG_" + UUID.randomUUID())
                .param("name", name)
                .param("names", names)
                .param("sort", sortOrder)
                .query(Long.class)
                .single();
    }

    public void tagItem(long itemId, long tagId) {
        jdbc.sql("INSERT INTO shop_item_tag (item_id, tag_id) VALUES (:i, :t)")
                .param("i", itemId)
                .param("t", tagId)
                .update();
    }

    public void deactivateTag(long tagId) {
        jdbc.sql("UPDATE shop_tag SET active = false WHERE id = :id").param("id", tagId).update();
    }
```

`ShopSpaceItemApiTest`에 더한다.

```java
    @Test
    void 공간의_분류는_분류_순서대로_언어에_맞는_이름으로_오고_쓰지_않는_분류는_빠진다() {
        long user = fixture.member();
        long space = fixture.freeItem("nature/abc12345", "IMAGE");
        long cafe = fixture.tag("카페", "{\"en\": \"Cafe\"}", 2);
        long nature = fixture.tag("자연", "{}", 1);
        long hidden = fixture.tag("숨김", "{}", 0);
        fixture.tagItem(space, cafe);
        fixture.tagItem(space, nature);
        fixture.tagItem(space, hidden);
        fixture.deactivateTag(hidden);

        assertThat(listedIn(user, space, "en").tags())
                .extracting(ShopTagResponse::name)
                .containsExactly("자연", "Cafe");
        assertThat(listedIn(user, space, "ko").tags())
                .extracting(ShopTagResponse::name)
                .containsExactly("자연", "카페");
        assertThat(listedIn(user, space, "ko").tags())
                .extracting(ShopTagResponse::code)
                .allSatisfy(code -> assertThat(code).startsWith("TAG_"));
    }
```

`독서실_감독관은_처음부터_카탈로그에_있는_무료_영상_공간이다`에서 `assertThat(ko.tag()).isEqualTo("STUDY_SPACE");` 앞에 주석과 한 줄을 더한다.

```java
        // 감시는 분류가 아니라 방식이라 분류가 없다. tag는 V88까지 남기는 옛 필드다
        assertThat(ko.tags()).isEmpty();
```

`nameIn`을 아래로 바꾸고 `nameIn` 호출 세 곳을 `listedIn(user, proctor, "en").name()` 꼴로 바꾼다.

```java
    private ShopItemResponse listedIn(long user, long itemId, String lang) {
        return shopService.list(user, lang).items().stream()
                .filter(item -> item.id() == itemId)
                .findFirst()
                .orElseThrow();
    }
```

import에 `project.study.shop.dto.ShopTagResponse`를 더한다.

- [ ] **Step 2: 테스트가 실패하는지 본다**

Run: `./gradlew test --tests "project.study.shop.ShopSpaceItemApiTest" -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
Expected: 컴파일 실패(`ShopTagResponse`, `tags()` 없음).

- [ ] **Step 3: 구현한다**

`ShopTagResponse.java`:

```java
package project.study.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ShopTagResponse(
        @Schema(description = "분류 코드", example = "NATURE") String code,

        @Schema(description = "분류 이름. Accept-Language의 언어, 번역이 없으면 한국어", example = "자연") String name) {}
```

`ShopRepository`에 더한다(`accessoriesOf` 아래).

```java
    /** 쓰는 분류를 상품별로 묶는다. 분류 순서대로이고, 이름은 language 번역이 있으면 그것, 없으면 한국어다. */
    public Map<Long, List<ShopTagResponse>> tagsByItem(String language) {
        Map<Long, List<ShopTagResponse>> byItem = new HashMap<>();
        jdbc.sql("""
                        SELECT it.item_id, t.code, COALESCE(t.names ->> :lang, t.name) AS name
                        FROM shop_item_tag it JOIN shop_tag t ON t.id = it.tag_id
                        WHERE t.active
                        ORDER BY it.item_id, t.sort_order, t.id""")
                .param("lang", language)
                .query((rs, n) -> byItem.computeIfAbsent(rs.getLong("item_id"), id -> new ArrayList<>())
                        .add(new ShopTagResponse(rs.getString("code"), rs.getString("name"))))
                .list();
        return byItem;
    }
```

`ShopItemResponse`의 `tag` `@Schema`를 바꾸고 끝에 `tags`를 더한다.

```java
        @Schema(
                description = "옛 분류 코드. tags로 대체했고 다음 릴리즈에 뺀다. 새 코드는 읽지 않는다",
                example = "STUDY_SPACE",
                deprecated = true)
        String tag,
        // ...
        VideoMode videoMode,

        @Schema(description = "공간 분류. 분류 순서대로 오고, 쓰지 않는 분류는 빠진다. 없으면 빈 배열")
        List<ShopTagResponse> tags) {}
```

`ShopService.list`:

```java
        Map<Long, List<ShopTagResponse>> tags = repository.tagsByItem(language);
        // ... new ShopItemResponse(..., item.assetType(), item.videoMode(),
                        tags.getOrDefault(item.id(), List.of())))
```

- [ ] **Step 4: 테스트가 통과하는지 본다**

Run: `./gradlew test --tests "project.study.shop.*" -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
Expected: PASS.

- [ ] **Step 5: `tag`를 읽는 곳이 응답뿐인지 본다**

Run: `rg -n '\.tag\(\)' src/main`
Expected: `ShopService.java` 한 곳(응답 생성)만 나온다.

- [ ] **Step 6: 커밋 묶음**

`feat: 공간 분류를 서버 표에서 이름까지 읽어 상품 목록에 싣는다 (BY-1366)`. Task 2의 파일 전부.

---

### Task 3: 문서

**Files:**
- Modify: `docs/adr/0047-points-shop-ledger.md` (28행 13항, 36항 뒤에 37항)
- Modify: `docs/erd.dbml` (`Table shop_item`, 새 표 둘)

- [ ] **Step 1: ADR 13항 앞에 바뀜 표기를 붙인다**

`13. ` 바로 뒤에 넣는다.

```markdown
(2026-11-20 BY-1366로 바뀜: 분류는 `shop_tag`가 코드와 언어별 이름까지 정하고 응답의 `tags`로 준다. 37항 참고.) 
```

- [ ] **Step 2: ADR 37항을 더한다**

```markdown
37. 영상 공간에 재생 방식 `video_mode`(`LOOP`·`WATCH`)를 두고, 공간 분류를 `shop_tag`·`shop_item_tag` 표로 정한다(BY-1366, V87). 감시(`WATCH`)는 들어오기·머무르기·나가기 장면과 소리를 따로 재생하는 방식이고, 독서실 감독관은 그 첫 컨셉이다. 그래서 감독관은 분류가 아니라 방식으로 구분하고 분류를 붙이지 않는다. 감시에 넣기 애매한 형식은 분류가 아니라 방식의 새 값으로 만들고, 새 값은 폴더 규칙·재생기·업로드 스크립트를 함께 정할 때만 더한다. 영상 공간은 방식을 반드시 갖고 그 밖의 상품은 갖지 않는다(`ck_shop_item_video_mode`). 분류는 코드·한국어 이름·언어별 이름·순서·사용 여부를 서버가 갖고, 앱은 받은 이름을 그대로 보여 준다. 분류를 늘릴 때 앱을 다시 배포하지 않으려는 것이다. 쓰지 않는 분류(`active = false`)는 응답에서 빠지고 연결은 남는다. 분류 행은 상품처럼 마이그레이션으로 넣는다. `shop_item.tag` 칸과 응답의 `tag` 필드는 롤링 배포 중인 옛 서버가 같은 상품 쿼리로 읽고 웹 상점도 아직 읽어서, 웹이 `tags`로 바뀐 뒤 V88(BY-1367)에서 지운다.
```

- [ ] **Step 3: ERD를 고친다**

`Table shop_item`의 `tag` 줄과 `asset_type` 줄 뒤:

```dbml
  tag varchar(32) [note: 'deprecated. shop_item_tag로 대체, V88에서 지운다']
  ...
  asset_type varchar(8) [note: 'IMAGE | VIDEO']
  video_mode varchar(8) [note: 'LOOP | WATCH. 영상 공간이면 반드시 있고 아니면 NULL']
```

`Table shop_item` 블록 뒤:

```dbml
Table shop_tag {
  id bigserial [primary key]
  code varchar(64) [not null, unique]
  name varchar(60) [not null]
  names jsonb [not null, default: '{}', note: '다른 언어 이름. 없는 언어는 name(한국어)']
  sort_order integer [not null, default: 0]
  active boolean [not null, default: true, note: 'false면 응답에서 뺀다']
}

Table shop_item_tag {
  item_id bigint [not null, ref: > shop_item.id]
  tag_id bigint [not null, ref: > shop_tag.id]

  indexes {
    (item_id, tag_id) [pk]
    tag_id
  }
}
```

- [ ] **Step 4: 커밋 묶음**

`docs: ADR 0047과 ERD에 영상 방식·서버 분류를 적는다 (BY-1366)`.

---

### Task 4: 전체 검증

- [ ] **Step 1:** `./gradlew spotlessApply -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
- [ ] **Step 2:** `./gradlew check -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
  Expected: BUILD SUCCESSFUL, 실패 0.
- [ ] **Step 3:** 백엔드 채점표로 codex 채점을 돌리고 지적을 반영한다.
- [ ] **Step 4:** 퀴즈 게이트 여부를 사용자에게 묻고, 커밋 계획 표를 제시해 승인받는다.
