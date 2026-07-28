# Redis 캐싱 도입 설계

> 상태: 🟢 활성 · 2026-07-28 브레인스토밍 세션에서 확정

## 1. 배경·동기

- was의 무거운 조회 3종(발굴 목록·랭킹 목록·AI 리포트)에서 응답 지연 체감.
- postgres(analysis DB)가 조회+세션+분석 결과를 다 받는 구조 — 트래픽 증가 전 부하 완충 필요.
- 특히 대시보드에서 목록 "다음 50개" 페이지 조회가 느림(오프셋 페이지네이션 + 무거운 쿼리).
- 현재 캐싱은 전무: 유일한 캐시는 `/v1/stats`의 HTTP `Cache-Control: max-age=1h` 헤더 1건.

## 2. 확정된 결정

| 결정 | 내용 | 근거 |
|---|---|---|
| Redis는 **순수 캐시 전용** | 세션은 Spring Session **JDBC 유지**, 이관하지 않음 | "강제 로그아웃 금지" 정책 — 세션을 Redis로 옮기면 마이그레이션 리스크 + 인증 경로 신규 SPOF. 다중 인스턴스 필요 시 재검토 |
| 무효화는 **TTL 백스톱만** | 미러 완료 시 즉시 무효화 연동 없음 | 분석 데이터는 새벽 미러 후 하루 불변 — 몇 시간 stale 허용(사용자 확인). 모듈 간 결합 회피 |
| 캐싱 방식 | Spring Cache 추상화(`@Cacheable`) + Redis 백엔드 | 표준 관용구, 코드 침습 최소. 목록 키만 커스텀 `KeyGenerator` |
| 장애 정책 | 전면 **fail-open** | Redis 다운 = 캐시 미스로 DB 직행. Redis는 SPOF 아님 |
| 다음 페이지 **프리페치** | 목록 2종에 N+1 페이지 비동기 선계산 | "다음 50개" 지연의 직접 해법. 부하가 실사용량에 비례(최대 2배), 조합 폭발 없음 |

## 3. 인프라

- **운영** `deploy/compose.yaml`: `redis:7-alpine` 서비스 추가.
  - `--maxmemory 256mb --maxmemory-policy allkeys-lru` (전부 캐시 키라 LRU evict 무해)
  - AOF·볼륨 영속화 **없음** — 재시작 = 콜드 캐시일 뿐. `restart: unless-stopped` + healthcheck.
  - 호스트 포트 비공개(내부 compose 네트워크만). was에 redis `depends_on`은 아예 두지 않음 — fail-open이라 redis 불능이어도 was는 기동·DB 직행해야 하며, 조건 완화식 depends_on조차 불필요한 결합이라 생략(구현 시 확정).
- **로컬** 루트 `compose.yaml`: redis 추가(6379).
- **was 의존성**: `spring-boot-starter-data-redis`(Lettuce).
- 접속 설정: `REDIS_HOST`/`REDIS_PORT` 환경변수(운영), 로컬 기본 localhost.

## 4. 캐싱 대상·키·TTL

| 캐시 이름 | 대상 | 키 | TTL |
|---|---|---|---|
| `influencer-report` | 인플루언서 AI 리포트 | `influencerId` | 6h |
| `content-report` | 콘텐츠 AI 리포트 | `contentId` | 6h |
| `influencer-discovery` | 발굴 목록 `GET /v1/influencers` (공통 부분) | 정규화 파라미터 해시 | 1h |
| `content-ranking` | 랭킹 목록 `GET /v1/contents` (공통 부분) | 정규화 파라미터 해시 | 1h |

- 키 네임스페이스: `hypenow:cache:<cacheName>:<key>`.
- 목록 키 생성: 필터·정렬·페이지 파라미터 record를 정규화(기본값 명시화, 순서 고정) 후 SHA-256 축약 — "같은 조건 = 같은 키" 보장. 커스텀 `KeyGenerator`.
- TTL 근거: 미러가 새벽(KST 06:00대) — 리포트 최악 stale 6h면 오전 중 자연 갱신, 허용 범위 내.
- 직렬화: **Jackson 3 JSON**(Boot 4 기준 `GenericJackson3JsonRedisSerializer` 계열). 자바 직렬화 금지(클래스 변경에 취약). 응답 DTO record 그대로 직렬화.
- 스탬피드 방어는 `@Cacheable(sync = true)` 한 줄만 — 그 이상 불필요.

## 5. 다음 페이지 프리페치 (목록 2종)

- 페이지 N 응답 반환 직후, 동일 필터·정렬 조건의 **페이지 N+1을 비동기로 선계산**해 캐시에 적재.
- 규칙: 캐시에 이미 있으면 스킵 · 마지막 페이지면 스킵 · 동시 트리거 중복 방지(캐시 락과 동일 메커니즘) · 프리페치 실패는 무시(fail-open과 동일) · 한 발(N+1)만, 그 이상 선행하지 않음.
- 미러 후 일괄 프리웜(크론)은 **제외** — 조합 선정 문제 대비 추가 가치 작음. 추후 "첫 페이지도 느리다" 체감 시 기본 조합 한정으로 재검토.

## 6. 개인화 분리 (목록 캐싱의 전제 리팩터링)

- 목록 응답의 사용자별 `isSaved`가 쿼리에 섞여 있으면 그대로는 캐싱 불가.
- 방식: ① 목록 쿼리에서 `isSaved` 조인 제거 → 공통 결과만 캐싱 ② 로그인 요청이면 캐시된 목록의 id들로 `saved_contents`/`saved_influencers`를 별도 경량 조회(PK IN, 밀리초급) ③ 서비스 레이어에서 오버레이.
- 부수 효과: 저장/취소 직후에도 `isSaved` 항상 실시간 정확(캐시 무관), 익명 사용자 간 캐시 완전 공유.
- 발굴 목록에 개인화 필드가 없다면 해당 경로는 이 단계 생략(구현 시 확인).

## 7. 장애 처리

- 커스텀 `CacheErrorHandler`: get/put/evict 예외 전부 삼키고 로그만 — Redis 장애 시 DB 직행.
- Redis 연결 타임아웃 짧게(수백 ms) — 장애 시 지연 전파 최소화.
- 세션·인증 경로는 Redis와 무관(JDBC 유지)이므로 Redis 장애가 로그인에 영향 없음.

## 8. 테스트

- Testcontainers Redis 통합 테스트: 히트/미스 · TTL 만료 · 동일 조건=동일 키(KeyGenerator) · fail-open(Redis stop 후 정상 응답) · 프리페치 적재 확인 · 개인화 오버레이 정확성(저장 직후 목록 반영).
- 기존 목록 API 응답 계약 불변 검증(개인화 분리 리팩터링 전후 동일 응답).

## 9. 배포

- PR 1개 범위: compose 2종 + 의존성 + 캐시 설정(직렬화·TTL·에러핸들러·KeyGenerator) + `@Cacheable` 4종 + 프리페치 + 개인화 분리.
- develop→main CD로 배포. 운영 배포 순서: redis 컨테이너가 compose로 함께 뜨므로 별도 선행 작업 없음. 배포 후 캐시 히트 로그·응답시간 스팟체크.

## 10. 범위 제외 (YAGNI)

- 세션 스토어 Redis 이관(다중 인스턴스 필요 시 재검토) · 미러 완료 시 즉시 무효화 연동 · rate-limit Redis 이관(단일 인스턴스라 불필요) · Caffeine 2단 캐시 · 미러 후 일괄 프리웜 · keyset 페이지네이션 전환(별도 트랙).
