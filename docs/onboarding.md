# 인수인계 문서 — Rate Limiter Service (현행 구현 기준)

## 1. 문서 목적과 범위
이 문서는 신규 입사자가 현행 코드 기준의 대기열(Waiting Room) 서비스 동작을 빠르게 이해하도록 돕기 위한 인수인계 문서입니다.

- 대상: 주니어 백엔드 개발자
- 범위: 현재 코드에 존재하는 대기열 시스템 전반
- 제외: 로컬 실행/운영 가이드

참고 문서로 `readme.md`, `BLOG.md`, `docs/first.md`가 있으나, 설계 히스토리 중심이며 현행 구현과 다를 수 있습니다. 본 문서는 **현행 코드 기준**으로만 서술합니다.

## 2. 서비스 개요
목표는 외부 병목을 보호하면서 사용자 경험을 유지하는 것입니다. 핵심 아이디어는 **입구에서 수용 가능한 인원만 즉시 입장시키고 나머지는 대기열로 보내는 방식**입니다.

- 대기열은 Redis ZSET으로 FIFO 순서를 유지합니다.
- 사용자는 폴링(poll)으로 자신의 순번과 입장 가능 여부를 확인합니다.
- 활성(입장) 세트는 수용 가능한 동시 처리량을 제한합니다.
- **폴링 추적기(poll-tracker)**는 “마지막 폴링 시각 기준으로 stale 사용자 정리”를 위한 인덱스 역할을 합니다.

## 3. 패키지 구조와 역할
현행 구현은 `com.jumunhasyeo.ratelimiter.queue` 패키지에 집중되어 있습니다.

- `queue/controller` : HTTP API 진입점
- `queue/service` : 비즈니스 로직
- `queue/repository` : Redis 및 Lua 스크립트 실행
- `queue/redis` : Redis 키 상수 정의
- `queue/dto` : 요청/응답 DTO
- `queue/config` : 설정 바인딩
- `queue/scheduler` : 정리(청소) 스케줄러

간단 흐름

```
Client -> QueueController -> QueueService -> WaitingQueueRedisRepository -> Redis(Lua)
```

## 4. 외부 API 계약
### 4.1 `POST /api/queue/enter`
- 요청
  - `QueueEntryRequest` : `userId` (필수)
- 응답
  - `QueueEntryResponse`
    - `allowed` : 즉시 입장 여부
    - `activeToken` : 즉시 입장 시 발급되는 토큰
    - `rank` : 대기 순번 (1-based)
    - `estimatedWaitSeconds` : 대기 시간 추정치
    - `pollIntervalSeconds` : 추천 폴링 간격(초)
- 상태 코드
  - `200 OK` : 즉시 입장
  - `202 Accepted` : 대기열 진입

### 4.2 `GET /api/queue/poll`
- 요청
  - `userId` (query)
- 응답
  - `QueuePollResponse`
    - `allowed` : 입장 허용 여부
    - `activeToken` : 입장 허용 시 발급되는 토큰
    - `rank` : 대기 순번 (1-based)
    - `estimatedWaitSeconds` : 대기 시간 추정치
    - `pollIntervalSeconds` : 추천 폴링 간격(초)

### 4.3 예외
- 대기열 미존재: `IllegalStateException` ("대기열에 존재하지 않는 사용자입니다.")

### 4.4 결제 콜백 (내부)
`POST /api/queue/payment/callback`

- 요청
  - `QueuePaymentCallbackRequest`
    - `userId` (필수)
    - `activeToken` (필수)
    - `status` (필수, `SUCCESS` | `FAIL`)
- 응답
  - `QueuePaymentCallbackResponse`
    - `result` : `REMOVED` | `REFRESHED` | `NOT_FOUND` | `MISMATCH` | `NOT_ACTIVE`
- 상태 코드
  - `200 OK` : 성공 처리/갱신
  - `404 Not Found` : active meta 없음
  - `409 Conflict` : userId 불일치 또는 active 없음
  - `400 Bad Request` : status 값 오류

## 5. 핵심 동작 흐름
### 5.1 Enter 흐름
1. Redis Lua 스크립트로 다음을 **원자적**으로 처리한다.
3. 이미 활성 사용자면 `ALREADY_ACTIVE` 반환.
4. 이미 대기열에 있으면 현재 rank 반환(`QUEUED:{rank}`).
5. 활성 세트 여유가 있으면 즉시 활성 등록 후 `ACTIVE:{token}` 반환.
6. 여유가 없으면 대기열에 등록하고 rank 반환.
7. 대기열 등록 시 **poll-tracker에 `lastPolledAt=now` 기록**.

간단 도식

```
Enter
  -> active 존재? yes -> ALREADY_ACTIVE
  -> waiting 존재? yes -> QUEUED:{rank}
  -> active 여유? yes -> ACTIVE:{token}
  -> else -> enqueue + pollTracker 갱신 + QUEUED:{rank}
```

### 5.2 Poll 흐름
1. active 여부 확인. 이미 활성이라면 `ALREADY_ACTIVE`.
2. poll-tracker 갱신.
3. waiting에서 rank 확인. 없으면 `NOT_IN_QUEUE`.
4. 활성 수용량이 남으면 대기열에서 제거 후 active 등록.
5. 그렇지 않으면 rank 반환.

간단 도식

```
Poll
  -> active 존재? yes -> ALREADY_ACTIVE
  -> rank 없음 -> NOT_IN_QUEUE
  -> rank < available -> ADMITTED:{token}
  -> else -> rank
```

## 6. Redis 데이터 모델
### 6.1 ZSET
- `q:waitroom:waiting`
  - 대기열
  - score: `epochMillis`
  - member: `userId`
- `q:waitroom:active`
  - 현재 활성 사용자
  - score: `entryMillis`
  - member: `userId`
- `q:waitroom:poll-tracker`
  - **폴링 추적용 인덱스**
  - score: `lastPolledAtMillis`
  - member: `userId`
  - 용도: `cleanup-stale.lua`에서 오래된 폴링 사용자 빠르게 탐색/정리

### 6.2 HASH
- `q:waitroom:active-meta:{activeToken}`
  - `userId`: 활성 사용자 식별자
  - TTL 적용 (active TTL과 동일)

### 6.3 STRING
- `q:waitroom:lock:cleanup`
  - 스케줄러 분산 락
- `q:waitroom:max-active-tokens`
  - 런타임 max active tokens (운영자가 직접 변경)
  - 값은 정수 문자열 (예: `"1000"`)

## 7. Lua 스크립트 상세
### 7.1 `queue-enter.lua`
역할: 진입 또는 대기열 등록을 원자적으로 수행.

입력
- KEYS: activeKey, waitingKey, pollTrackerKey, activeMetaKey
- ARGV: userId, nowMillis, maxTokens, activeToken, activeMetaTtl

반환
- `ALREADY_ACTIVE`
- `ACTIVE:{token}`
- `QUEUED:{rank}`

핵심
- active 존재 여부, waiting 중복 여부, 활성 수용 가능 여부를 하나의 스크립트에서 처리
- 대기열 등록 시 poll-tracker에 `lastPolledAt` 기록
- active 전환 시 `active-meta`에 `userId` 기록

### 7.2 `queue-poll.lua`
역할: 순번 확인과 입장 전환 처리.

입력
- KEYS: activeKey, waitingKey, pollTrackerKey, activeMetaKey
- ARGV: userId, nowMillis, maxTokens, activeToken, activeMetaTtl

반환
- `ALREADY_ACTIVE`
- `NOT_IN_QUEUE`
- `ADMITTED:{token}`
- `rank` (문자열 숫자)

핵심
- rank가 활성 수용량보다 앞이면 waiting에서 제거 후 active에 추가
- 매 폴링마다 poll-tracker 갱신
- admitted 시 `active-meta`에 `userId` 기록

### 7.3 `cleanup-stale.lua`
역할: 폴링이 오래 끊긴 사용자 정리.

입력
- KEYS: waitingKey, pollTrackerKey
- ARGV: nowMillis, thresholdMillis, batchSize

동작
- poll-tracker에서 `ZRANGEBYSCORE`로 오래된 사용자 찾기
- waiting/poll-tracker 제거 (stale 정리)

### 7.4 `cleanup-active.lua`
역할: active TTL 만료 사용자 제거.

입력
- KEYS: activeKey
- ARGV: nowMillis, ttlMillis

동작
- active ZSET에서 TTL 경과 사용자 제거

### 7.5 `active-callback.lua`
역할: 결제 콜백 처리 (성공 시 제거, 실패 시 TTL 갱신).

입력
- KEYS: activeKey, activeMetaKey
- ARGV: userId, nowMillis, activeTtlSeconds, action(SUCCESS|FAIL)

반환
- `REMOVED` | `REFRESHED` | `NOT_FOUND` | `MISMATCH` | `NOT_ACTIVE` | `INVALID_ACTION`

핵심
- active meta로 userId 매칭 검증
- SUCCESS: active 제거 + meta 삭제
- FAIL: active score 갱신 + meta TTL 갱신
## 8. 스케줄러 및 정리 로직
`QueueCleanupScheduler`는 고정 주기로 실행되며, `RedisSchedulerLock`을 통해 **단일 인스턴스만** 정리를 수행합니다.

- stale poll 사용자 제거
- active TTL 만료 사용자 제거

분산 락은 `SET NX EX` 기반이며, 인스턴스 간 중복 청소를 방지합니다.

기본 주기 및 임계치 (application.yml 기준)
- `queue.cleanup-interval-ms`: 5000 (5초 주기)
- `queue.max-poll-interval-seconds`: 30 (권장 폴링 최대 간격)
- stale 기준: `maxPollIntervalSeconds * 3` (기본 90초)
- `queue.active-ttl-seconds`: 600 (active TTL 10분)

## 9. 대기 시간 추정 로직
`QueueServiceImpl.estimateWaitSeconds`에서 대기 시간을 계산합니다.

- 처리량(초당) = `maxActiveTokens / estimatedProcessingSeconds`
- 대기 시간(초) = `rank / 처리량`
- 최소 1초 보장

주의
- rank는 1-based로 응답됩니다.
- 추정치는 평균 처리량 기반의 간단 추정치입니다.

### 9.1 폴링 간격 추천 로직
`estimatedWaitSeconds` 기반으로 클라이언트 폴링 간격을 조정합니다.

- 10분 이상: 30초
- 5분 이상: 20초
- 1분 이상: 10초
- 1분 미만: 5초

10분 이상 구간의 최대값은 `queue.max-poll-interval-seconds`로 설정됩니다.

## 10. 설정값 요약 (application.yml)
아래는 기본 운영 프로파일 기준입니다.

- `queue.max-active-tokens`: 1000
- `queue.active-ttl-seconds`: 600
- `queue.max-poll-interval-seconds`: 30
- `queue.cleanup-interval-ms`: 5000
- `queue.cleanup-lock-ttl-ms`: 3000
- `queue.estimated-processing-seconds`: 3
- `queue.metrics-interval-ms`: 1000

로컬 프로파일(`application-local.yml`)에서는 `admission.*` 값이 테스트 용도로 상향됩니다.

런타임 동적 변경
- `q:waitroom:max-active-tokens` 값은 **항상 존재해야 합니다**.
- 값이 없거나 비정상(0 이하)이면 예외가 발생합니다.

## 11. poll-tracker 사용 예시
1. 사용자가 대기열에 들어오면 `poll-tracker`에 `lastPolledAt=now` 기록  
2. 폴링이 계속되면 매 요청마다 `poll-tracker`가 갱신됨  
3. 폴링이 끊기면 `cleanup-stale`이 `now - (maxPollIntervalSeconds * 3)` 기준으로 제거  

즉, **대기열 순서(waiting ZSET)와 정리 기준(poll-tracker ZSET)을 분리**해 정확성과 효율을 동시에 확보합니다.

## 10. 에러 및 경계 조건
- waiting에 없는 사용자가 poll하면 `IllegalStateException` 발생
- cleanup 또는 상태 불일치로 waiting에서 사라지면 NOT_IN_QUEUE 흐름으로 처리 가능

## 11. 테스트 요약
`QueueServiceTest`는 다음을 보장합니다.

- active 여유가 있으면 즉시 입장
- 이미 active면 allowed 응답
- 대기열 등록 시 rank 반환
- 대기 시간 추정 계산
- poll 시 입장 허용/대기 순위 반환
- 대기열 미존재 예외

`WaitingQueueRedisRepositoryTest`는 Lua 스크립트 동작을 통합적으로 검증합니다.

## 12. 주요 설정 값
`application.yml`의 `queue.*` 설정

- `max-active-tokens` : 동시 활성 수용량
- `active-ttl-seconds` : active TTL
- `max-poll-interval-seconds` : 권장 폴링 최대 간격 (stale 기준은 이 값의 3배)
- `cleanup-interval-ms` : 정리 주기
- `cleanup-lock-ttl-ms` : 락 유지 시간
- `estimated-processing-seconds` : 대기 시간 추정에 사용되는 평균 처리 시간

참고
- `approve.*` 설정은 현행 코드에서 사용되지 않으므로 정리 대상입니다.

## 13. 참고 문서 안내
- `readme.md` : 프로젝트 분석 보고서 성격
- `BLOG.md` : 설계 히스토리와 의사결정 기록
- `docs/first.md` : 초기 아키텍처 설계 문서

현행 구현을 이해할 때는 본 문서를 기준으로 판단하세요.
