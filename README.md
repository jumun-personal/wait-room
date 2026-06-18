## 개요
- 배경: 순간적인 트래픽 발생 시 주문 서버 혹은 PG API 처리량 제한으로 병목이 발생하고 사용자 요청은 지연되거나 실패하며 재시도 폭풍(Retry Storm)으로 인한 시스템 부하 및 사용자 이탈 가능 
- 해결: Redis ZSET 으로 대기열과 입장열을 통해 주문 진입량 조절
  - 대기번호 조회 기능으로 예측 가능한 대기 흐름으로 전환하여 이탈 가능성 완화 
  - 대기열 위치를 비교한 뒤 PG 결제창 호출 이전으로 배치하여 결제 정보 입력 전 차단 
  - Redis 장애 시에는 Redis호출을 차단하고 Gateway rate limiter로 주문 서버 보호
- 성과: 대기자 5,000명, 입장 처리 75RPS에서 순번 조회 p99 22.2ms로 확인 (JVM Warm-up 진행)

## 시스템 아키텍처

### 대기열 내부 동작

!image.png

- POST /api/queue/enter는 대기열 등록을 담당합니다.
- GET /api/queue/poll은 순번조회 질의와 하트비트를 담당합니다.
- Scheduler는 1초마다 waiting 사용자를 active로 승격합니다.

즉 enter와 poll은 상태를 읽고 기록하는 역할에 집중하고, 실제 waiting -> active 이동은 서버 스케줄러가 담당합니다.

### 대기열 서버 구성도

<img width="1910" height="528" alt="image" src="https://github.com/user-attachments/assets/1defdc5b-4f4b-431c-ba5d-728bf2a14796" />


- 대기열은 Gateway와 Order서버 사이에 위치합니다
- Redis가 잠깐 불안정할 때는 retry와 순번 조회의 경우 마지막 순번을 반환합니다.
- 장애가 길어지면 gateway가 queue를 우회하도록 fail-open 신호를 보냅니다.

### 레디스 데이터 모델

|  | 자료구조 | 용도 | 상세 |
| --- | --- | --- | --- |
|  | ZSET | 대기열 | member=userId, score=epochMillis (대기 시각) |
|  | ZSET | 활성 사용자 집합 | member=userId, score=만료 시각(expireAtMillis) |
|  | ZSET | 오래된 폴링 user 찾기 | member=userId, score=마지막 poll 시각(epochMillis) |
|  | STRING +TTL | token 저장 | 후속 요청에서 userId + token 일치 여부 검증 |

### 레디스 장애시 대응 전략

<img width="1928" height="908" alt="image" src="https://github.com/user-attachments/assets/5c7c4faa-ece9-4f45-bb20-73eaa54fc3fc" />


- 짧은 장애는 retry로 흡수합니다.
- retry 후에도 실패하면 일시 장애로 처리합니다.
- 실패가 더 누적되면 circuit breaker를 열고, queue 서비스는 503 + BYPASS(영구 장애) 신호만 보냅니다.
- 실제 fail-open 판단과 우회 수행은 Spring Cloud Gateway가 담당합니다.

---

## 성능 테스트 상세 결과

단일 노드(4-core) 환경에서 대기자 5,000명(10초 폴링, 약 500 RPS)과 동시 입장 75 RPS 조건에서

p99 22.2ms,  HTTP 실패율 0%로 안정 동작을 확인했습니다.

> 이미 대기하는 5000명이 계속 상태를 조회하면서 동시에 새로운 사용자가 초당 75명씩 들어오는 상황
한계: 단일 부하 발생기 환경에서 대기자 5,000명부터 k6 VU 할당 지연 경고가 관측되어, 더 높은 부하는 분산 부하 테스트 환경에서 재검증이 필요합니다.
> 
- **환경**
    - **4-core CPU, 12 RAM, Ubuntu24.04**
    - docker-compose **Redis, grafana, prometheus, redis-exporter** (up -d)
- **k6 기준**
    - **HTTP 실패율**: 0%
    - **평균 응답시간**: 4.21ms
    - **p95**: 10.3ms
    - **p99**: 22.2ms
    - **총 요청 수**: 77,489
    - **평균 처리율**: 554.84 req/s

구간 전체에서 실패 요청 없이 안정적으로 응답했고, 평균 응답시간도 낮게 유지되었습니다.
특히 p95, p99 구간도 크게 튀지 않아 부하가 걸린 상황에서도 응답 지연이 과도하게 증가하지 않음을 확인할 수 있었습니다.

### 서버 / 그라파나 대시보드

- **waiting**: 5,000 → 12,400
- **active 피크**: 1,000
- **Redis command p99 최대**: 9ms
- **전체 CPU 최대**: 47%
- **Spring process CPU 최대**: 0.6%
- **heap used 최대**: 64MiB
- **GC pause 최대 또는 p99**: 47ms

서버 지표 기준으로도 병목 없이 안정적으로 동작했습니다.
대기 인원은 5,000명에서 12,400명까지 증가했지만, active 사용자는 최대 1,000명 수준으로 통제되었고,이 과정에서 API 지연 시간도 낮은 수준을 유지했습니다.

또한 Redis 명령 처리 시간의 p99가 최대 9ms 수준으로 유지되어, 대기열 핵심 연산이 Redis에서 과도한 지연 없이 처리되고 있음을 확인했습니다.

리소스 사용량도 비교적 안정적이었습니다. 전체 CPU 사용률은 최대 47% 수준이었고,Heap 사용량 역시 최대 64MiB로 크지 않았으며,GC pause도 최대 또는 p99 기준 47ms 수준으로 측정되었습니다.
