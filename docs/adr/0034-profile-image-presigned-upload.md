# ADR-0034: 프로필 사진은 presigned PUT으로 S3에 바로 올리고, 웹과 같은 출처의 `/avatars/*` 경로로 서빙한다

- 상태: 승인
- 날짜: 2026-10-28
- 티켓: BY-1035 (스토리 BY-1031)
- 명세: `docs/superpowers/specs/2026-10-28-BY-1035-profile-image-design.md`
- 관련: BY-1034(사진 버킷·CloudFront `/avatars/*`), BY-1032(위키 정책), ADR-0015(API 버전), ADR-0018(룸 상태는 Postgres), ADR-0028(랭킹은 조회 때 집계)

## 맥락

프로필 아바타는 이니셜과 색상뿐이었다. 사용자가 사진을 올리고 바꾸고 되돌릴 수 있게 하고, 프로필·소셜룸·랭킹에 사진을 보여 준다.
웹이 512px JPEG로 줄여 올리므로 파일은 1MB를 넘지 않는다(BY-1037). 사진 버킷과 CloudFront 경로는 BY-1034가 만들었다.

## 결정

1. **서버는 바이트를 받지 않는다.** `POST /api/users/me/profile-image/upload-url`이 5분짜리 presigned PUT URL과 서버가 만든 키
   `avatars/{userId}/{UUID}.jpg`를 주고, 브라우저가 S3에 바로 올린다. API 태스크가 이미지 본문을 메모리에 올리거나 대역폭을 쓰지 않는다.
   키를 서버가 만들어 사용자는 자기 폴더 밖에 쓸 수 없다.
2. **등록은 사진 전용 경로로 나눈다.** `PUT /api/users/me/profile-image {key}`로 등록하고 `DELETE`로 되돌린다. 프로필 `PATCH`에 섞으면
   S3 확인과 파일 삭제가 닉네임 수정과 한 요청에 엉킨다.
3. **크기는 두 번 확인한다.** SDK가 presigned PUT에 `content-type`과 `content-length`를 함께 서명한다
   (`X-Amz-SignedHeaders=content-length;content-type;host`). 그래서 URL은 `image/jpeg`와 발급 때 요청한 정확한 크기에 묶이고,
   다른 크기·형식의 본문은 S3가 거부한다. 등록 때는 `HeadObject`로 실제 파일의 존재·크기(1MB 이하)·형식을 다시 본다.
   서명은 발급 때 받은 숫자만 묶고, DB가 참조하기 전에 실제 객체를 보는 것은 `HeadObject`뿐이다. 기준을 넘으면 그 파일을 삭제하고 400이다.
4. **업로드 주체에는 GetObject와 ListBucket도 준다.** `avatars/*`의 `s3:PutObject`·`s3:DeleteObject`에 더해 `s3:GetObject`와
   버킷의 `s3:ListBucket`이 필요하다. `HeadObject`는 `s3:GetObject`로 동작하고, `s3:ListBucket`이 없으면 S3가 없는 키에 404 대신
   403을 돌려준다. 그러면 올리지 않은 키로 등록할 때 "파일 없음" 400이 아니라 500이 된다. `ListBucket`은 prefix 조건 없이 버킷 ARN에
   준다. `HeadObject`의 존재 확인 요청에는 prefix가 실리지 않아 `s3:prefix` 조건을 걸면 맞지 않고 계속 403이 나기 때문이다.
   사진 전용 버킷이라 목록을 열어도 보이는 것은 사진 키뿐이다. 개발 서버는 버킷과 다른 AWS 계정에서 돌기 때문에 그 역할에는 같은 권한을
   버킷 정책과 역할 자체의 IAM 정책 양쪽에 모두 줘야 하고, 한쪽에라도 `ListBucket`이 없으면 없는 키에 여전히 403이 난다.
5. **웹과 같은 출처의 경로로 서빙한다.** 응답은 `base-url + "/" + key`이고 `base-url` 기본값은 빈 문자열이라 `/avatars/...` 상대 경로다.
   웹 응답이 COEP `require-corp`라 다른 출처 이미지는 CORP 헤더가 없으면 브라우저가 막는다. CloudFront가 웹과 같은 배포에서
   `/avatars/*`를 버킷으로 보내므로 같은 출처가 되어 헤더가 필요 없다. PC용 웹처럼 다른 도메인에서 사진을 써야 하면
   `app.profile-image.base-url`을 절대 URL로 바꾸고, 그때 `/avatars/*` 응답에 CORP 헤더를 함께 넣는다.
6. **이전 파일은 DB 커밋 뒤 바로 지운다.** 삭제가 실패하면 경고 로그만 남기고 요청은 성공이다. 남은 파일은 어디서도 참조하지 않는다.
   같은 키를 다시 보내면 아무것도 지우지 않는다.
7. **운영자 삭제는 관리 API 없이 런북으로 한다**(`docs/runbooks/profile-image-removal.md`). 관리자 인증 체계가 없고, 새 엔드포인트는
   공격 지점을 늘린다. 신고가 잦아지면 BY-1039에서 다시 본다.
8. **소셜룸은 입장 시점 사진, 랭킹은 조회 때 사진을 싣는다.** 룸은 닉네임처럼 입장 때 사진 키를 `room_participations.profile_image`(V31)에
   복사한다. 랭킹은 닉네임 조인 쿼리에서 `users.profile_image`를 같이 읽는다.

## 결과

- `ProfileResponse`·`RoomMember`·랭킹 `BoardEntry`·`BoardNeighbor`·`DayGroupRow`에 nullable `profileImageUrl`이 실린다. 필드 추가라 기존 클라이언트는 영향이 없다.
- 방에 있는 동안 사진을 바꾸면 다음 입장부터 반영된다. 이전 파일을 바로 지우므로 이미 방에 있는 사람들 화면에서는 엣지 캐시가 끝난 뒤(최대 1시간) 다음 입장 전까지 이니셜로 보인다.
- 랭킹 캐시 때문에 사진 변경은 최대 60초 늦게 반영된다.
- 운영은 `APP_PROFILE_IMAGE_BUCKET`에 기본값이 없다. 이 코드가 나가는 릴리즈보다 먼저 infra 저장소에서 ECS 태스크 정의에 이 환경 변수를
  넣어야 한다. 없으면 기동이 실패한다.
- 운영 태스크 역할과 개발 서버 EC2 인스턴스 역할 모두 결정 4의 권한(`PutObject`·`GetObject`·`DeleteObject`, `ListBucket`)이 필요하다.
- 버킷 이름이 틀려도 `HeadObject`가 404를 받아 "업로드된 사진이 없습니다" 400으로 보인다. 모든 등록이 이 400으로 실패하면
  `app.profile-image.bucket`부터 확인한다.
- `base-url` 끝의 `/`는 코드가 떼어 낸다. 남겨 두면 `//avatars/...`가 되어 브라우저가 `avatars`를 호스트 이름으로 읽기 때문이다.
- 탈퇴 시 사진 삭제는 탈퇴 기능이 생길 때 다룬다.

## 고려한 대안

- **서버가 multipart로 받아 S3에 올린다**: 크기·형식 검사를 서버가 직접 하지만, API 태스크가 이미지 트래픽을 그대로 받는다.
- **이미지 전용 도메인**: 캐시 정책을 따로 둘 수 있지만 도메인·인증서·CORS가 늘고 지금 얻는 것이 없다. `base-url`로 나중에 옮길 수 있다.
- **이전 파일을 수명 주기 규칙으로 늦게 지운다**: 방 안 화면이 더 오래 보이지만, 참조하지 않는 파일을 남기는 기간과 규칙이 생긴다.
- **관리자 삭제 API**: 관리자 인증부터 만들어야 한다. 신고 빈도가 낮은 지금은 런북이 싸다.
- **`s3:ListBucket` 없이 403을 "파일 없음"으로 본다**: 권한은 줄지만 권한 설정 실수와 없는 파일을 구분할 수 없어, 설정이 틀려도 사용자 오류로 묻힌다.
