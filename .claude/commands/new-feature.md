---
description: 도메인 기능 스캐폴딩 (엔티티+repo+service+controller+테스트)
---

"$ARGUMENTS" 기능을 다음 순서로 구현해줘:

1. 먼저 도메인 이름과 필요한 필드/API를 정리해서 나에게 확인받기
2. `src/main/resources/db/migration/`에 Flyway 마이그레이션 추가
3. `project.study.<도메인>/` 패키지에 엔티티, Repository, Service, Controller, DTO(record) 생성
4. 서비스 단위테스트 + Testcontainers 통합테스트 작성
5. `./gradlew check` 실행해서 전부 통과할 때까지 수정
6. 변경 요약과 함께 커밋 메시지 제안
