# 부하테스트 (k6) — BY-462

운영 서버(ECS Fargate **0.5 vCPU / 1 GiB**, 단일 태스크, **오토스케일 없음**)가
동시 접속 부하에서 어디까지 버티는지, 병목이 어디서 생기는지 측정한다.
목표: **동시 "공부 중" 유저 1000명**.

## 도구

[k6](https://k6.io) — HTTP + WebSocket을 한 스크립트로 다루고, 커넥션당 리소스가
가벼워 수천 개 웹소켓을 한 부하생성기로 만들 수 있다. STOMP 네이티브 지원은 없어
`lib/stomp.js`에서 텍스트 프레임을 직접 조립한다.

```bash
brew install k6        # macOS
# 또는 https://k6.io/docs/get-started/installation/
```

## 파일

| 파일 | 내용 |
|---|---|
| `lib/stomp.js` | STOMP over k6/ws 헬퍼 (CONNECT/SUBSCRIBE/SEND/하트비트) |
| `lib/api.js` | setup용 REST 헬퍼 (유저 등록, 방 생성) |
| `lib/options.js` | 공통 램프(계단식) 옵션, STOMP 메시지 파서 |
| `s1-checkpoint.js` | 시나리오 A — 체크포인트 HTTP만 (HikariCP 풀 한계) |
| `s2-room.js` | 시나리오 B — STOMP 룸만 (전역 락·브로커·시그널) |
| `s3-combined.js` | **시나리오 C — 결합(현실). 핵심.** |

각 스크립트의 `setup()`이 부하 전에 유저(`POST /api/users`)와 방(`POST /api/rooms`)을
미리 만든다. 인증이 비활성(permitAll)이라 토큰 없이 `userId`만으로 호출한다.
`s2/s3`는 VU를 6명씩 같은 방에 몰아넣어 방 내부 부하를 만든다(방 수 = PEAK/6).

## 실행

환경변수:
- `BASE_URL` (기본 `http://localhost:8080`) — REST 대상
- `WS_URL` (기본 `ws://localhost:8080/ws`) — STOMP 대상
- `PEAK` (기본 1000) — 목표 최대 동시 VU
- `HOLD` (s2/s3, 초) — 웹소켓 커넥션 유지 시간

```bash
# 로컬 1차(병목 구조 확인) — 작은 PEAK로 문법·흐름부터 검증
k6 run -e PEAK=50 -e HOLD=60 loadtest/s3-combined.js

# 목표 부하(staging)
k6 run -e BASE_URL=http://<stg-private-ip>:8080 \
       -e WS_URL=ws://<stg-private-ip>:8080/ws \
       -e PEAK=1000 -e HOLD=300 loadtest/s3-combined.js
```

시나리오 A/B는 병목을 분리해서 볼 때:
```bash
k6 run -e PEAK=1000 loadtest/s1-checkpoint.js                 # 체크포인트만
k6 run -e PEAK=1000 -e HOLD=60 loadtest/s2-room.js            # 룸만
```

### 부하 생성기 주의
- 1000 웹소켓 커넥션이면 파일 디스크립터 한계에 걸릴 수 있다 → `ulimit -n 100000`.
- k6 도는 머신이 CPU/네트워크로 먼저 막히면 서버 한계를 못 본다. 부하생성기는
  **테스트 대상과 같은 VPC 내 별도 EC2**(예: c7g.large 이상)에서 돌린다.

## 관측 (부하 도는 동안 동시에 볼 것 — 병목 위치를 알려준다)

**앱 (Spring Boot Actuator):** `management.endpoints.web.exposure.include: health,info,metrics`
가 이미 설정돼 있어 `/actuator/metrics/*`가 열려 있다(인증 비활성이라 토큰 불필요).
Prometheus 형식이 아니라 개별 JSON 엔드포인트이므로 부하 중에 폴링해서 본다:
```bash
watch -n 2 '
for m in hikaricp.connections.pending hikaricp.connections.active \
         tomcat.threads.busy jvm.gc.pause; do
  v=$(curl -s http://<host>:8080/actuator/metrics/$m | jq ".measurements[0].value")
  printf "%-35s %s\n" "$m" "$v"
done'
```
`hikaricp.connections.pending`가 0 위로 올라가면 → 풀(기본 10) 포화, 커넥션 대기 = 병목.

**ECS (CloudWatch):** CPU / 메모리. staging 태스크가 같은 클러스터에 뜨면 지표는 자동
수집되지만, 알람 7개는 prod 서비스 기준이라 staging엔 안 울린다 → 그래프로 직접 관찰.
0.5 vCPU라 CPU부터 포화될 가능성이 높다.

**앱 액세스 로그 끄기 (부하 전 필수):** 요청당 INFO 한 줄을 남기는 `RequestLoggingFilter`가
켜져 있으면 k6 요청 수만큼 CloudWatch Logs 수집량(GB당 과금)이 붙는다. staging 태스크 정의의
환경변수로 부하 도는 동안만 WARN으로 내린다 (이미지 재빌드 불필요):
```
LOGGING_LEVEL_PROJECT_STUDY_COMMON_LOGGING_REQUESTLOGGINGFILTER=WARN
```
prod는 그대로 INFO 유지 — 유저별 흐름 추적이 이 로그의 존재 이유다.

**RDS (느린 쿼리):** prod `default.postgres17` 파라미터 그룹에 `pg_stat_statements`가
이미 `shared_preload_libraries`에 로드돼 있다(스냅샷 복원 staging도 상속 → 리부트 불필요).
DB에서 확장만 만들면 된다:
```sql
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;  -- 1회
SELECT pg_stat_statements_reset();                   -- 부하 시작 직전 초기화
-- 부하 후 느린 쿼리 top
SELECT query, calls, mean_exec_time, total_exec_time
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 20;
```
`log_min_duration_statement`(느린 쿼리 로깅)는 선택사항 — pg_stat_statements가 전 쿼리
통계를 잡으므로 생략 가능하다(켜려면 default 그룹 수정 불가라 커스텀 파라미터 그룹 필요).

## 합격 기준(SLO) — 스크립트 thresholds

| 지표 | 기준 |
|---|---|
| `checkpoint_duration` p99 | < 500ms |
| `room_subscribe_to_snapshot` p99 | < 1000ms |
| `ws_connect_success` | > 99% |
| `http_req_failed` | < 1% |

## 예상 병목 (측정으로 검증할 가설)

1. `RoomService` 전역 `synchronized` 락 — 모든 룸 연산 직렬화 (웹소켓 1순위)
2. HikariCP `maximum-pool-size` 기본값 10 — 체크포인트 동시성
3. 하트비트 스케줄러 `poolSize=1` / `roomHistoryExecutor` 단일 스레드
4. 0.5 vCPU CPU 포화 (WebRTC 시그널 릴레이 mesh + JSON 직렬화)

---

## 임시 staging 구성 런북 (일회성)

> ⚠️ **보안:** staging RDS는 **prod 스냅샷 복원**이라 실제 유저 데이터를 담는다.
> 게다가 앱은 인증 비활성(permitAll) 상태다. **8080을 퍼블릭에 열지 말 것.**
> 부하생성기를 VPC 내부 EC2에 두고 태스크는 프라이빗 유지, 테스트 후 즉시 테어다운.

확인된 prod 값(2026-08 기준):
- Region `ap-northeast-2`, Cluster `focus-makers-prod-cluster`, Service `focus-makers-prod-api`
- Task def `focus-makers-prod-api:8` (cpu `512` / memory `1024`)
- 서브넷 `subnet-0cb302d7bcf064808`, `subnet-0b96f3564a6688492` / SG `sg-02be7e706b7092006`
- RDS `focus-prod-db` (db.t4g.micro), 최신 스냅샷 예: `rds:focus-prod-db-2026-08-27-17-07`

```bash
REGION=ap-northeast-2

# 1) RDS 복원 (스냅샷 → focus-stg-db, 동일 사양). ~10-15분
aws rds restore-db-instance-from-db-snapshot --region $REGION \
  --db-instance-identifier focus-stg-db \
  --db-snapshot-identifier rds:focus-prod-db-2026-08-27-17-07 \
  --db-instance-class db.t4g.micro --no-multi-az --no-publicly-accessible
# 복원 완료 후 엔드포인트 확인
aws rds describe-db-instances --region $REGION --db-instance-identifier focus-stg-db \
  --query 'DBInstances[0].Endpoint.Address' --output text
# ※ 마스터 계정/비번은 스냅샷과 동일하므로 datasource는 host만 교체하면 된다.

# 2) task def 복제 → datasource만 staging RDS로 override 한 focus-makers-stg-api 등록
#    (SPRING_DATASOURCE_URL 환경변수를 container definition에 추가.
#     기존 task def JSON을 받아 image/URL만 바꿔 register-task-definition)

# 3) VPC 내부에 부하생성기 EC2 1대 (같은 서브넷, k6 설치)

# 4) staging 태스크 1개 기동 (프라이빗 IP)
aws ecs run-task --region $REGION --cluster focus-makers-prod-cluster \
  --launch-type FARGATE --task-definition focus-makers-stg-api \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-0cb302d7bcf064808],securityGroups=[<stg-sg>],assignPublicIp=DISABLED}'
# 태스크 ENI의 프라이빗 IP를 BASE_URL/WS_URL로 사용

# 5) 부하생성기 EC2에서: 유저 시드 → 부하 실행 → 관측

# 6) 테어다운
aws ecs stop-task --region $REGION --cluster focus-makers-prod-cluster --task <taskArn>
aws rds delete-db-instance --region $REGION --db-instance-identifier focus-stg-db \
  --skip-final-snapshot --delete-automated-backups
# 임시 SG/EC2도 정리
```

비용은 db.t4g.micro + Fargate 0.5vCPU 몇 시간이라 테어다운하면 무시 가능한 수준.
