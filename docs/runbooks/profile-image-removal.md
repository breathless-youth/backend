# 프로필 사진 운영자 삭제 런북

신고된 프로필 사진을 운영자가 지울 때 쓴다. 관리 API는 없다(ADR-0033 결정 7).

| 환경 | 버킷 |
|---|---|
| 운영 | `focus-makers-prod-avatars-821365066487` |
| 개발 서버 | `focus-makers-staging-avatars-821365066487` |

## 절차

1. db-shell로 운영 DB에 접속한다.
2. 아래 SQL을 트랜잭션 안에서 실행하고, 한 줄만 바뀌었는지와 삭제할 키를 확인한 뒤 커밋한다. `RETURNING`은 바뀐 뒤 값을 돌려주므로 지우기 전 키는 서브쿼리로 받는다.

   ```sql
   BEGIN;
   UPDATE users u SET profile_image = NULL
   FROM (SELECT id, profile_image FROM users WHERE id = :userId FOR UPDATE) old
   WHERE u.id = old.id
   RETURNING u.id, old.profile_image;
   COMMIT;
   ```
3. 나온 키 하나만 `aws s3 rm s3://<버킷>/<키>`로 삭제한다. `--recursive`는 쓰지 않는다.
4. 엣지 캐시에서 바로 사라지도록 해당 경로의 캐시를 무효화한다. 운영 배포 ID는 infra 저장소 `web` 스택 출력 `cloudfront_distribution_ids`에 있다.

   ```bash
   aws cloudfront create-invalidation --distribution-id <운영 배포 ID> --paths "/<삭제한 키>"
   ```
5. 처리한 사용자 id와 시각을 신고 메일 스레드에 남긴다.

## 참고

- 무효화를 하지 않으면 지운 뒤에도 엣지 캐시가 끝날 때까지(최대 1시간) 같은 경로가 열릴 수 있다. 4번 절차가 이 대기를 없앤다.
- 소셜룸에 이미 들어와 있는 사람에게는 입장 때 복사한 사진 경로가 그대로 실린다. 파일을 지웠으므로 무효화가 끝나면 이니셜로 보인다.
