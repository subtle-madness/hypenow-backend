# super 초대코드 설계

> 상태: 🟢 활성 · 2026-08-04

## 목적

초대코드는 현재 코드 하나당 1명만 가입 가능(`used_at` 원자 스탬프). 특정 코드를 **super**로
지정하면 인원 제한 없이 여러 명이 같은 코드로 가입할 수 있게 한다. 마케팅 채널·제휴처에
단일 코드를 배포하는 용도.

## 결정 사항 (사용자 확정)

- 허용 인원: **무제한** (코드별 max_uses 지정은 하지 않음 — YAGNI).
- 지정 경로: 적재 시 플래그 + **기존 코드 승격·강등 API 둘 다**.
- 방식: A안 — `is_super boolean` 컬럼 하나. 별도 uses 테이블(B안)은 기각
  (가입자 추적은 기존 `signup_events`의 detail.signupCode·userId로 이미 커버).

## 스키마

새 Flyway 마이그레이션 (was `db/migration/app`, UTC 타임스탬프 채번):

```sql
ALTER TABLE app.signup_codes ADD COLUMN is_super boolean NOT NULL DEFAULT false;
```

컬럼 추가만이라 expand-contract 안전 (롤링 중 구 코드는 컬럼을 무시하고 기존 동작 유지).

## 소진 로직 (SignupCodeRepository)

**claim(code, userId)** — 가입 트랜잭션 내 호출, 순서 고정:

1. 기존 원자 UPDATE에 `AND NOT is_super` 추가:
   `UPDATE app.signup_codes SET used_by=:userId, used_at=now()
    WHERE code=:code AND used_at IS NULL AND NOT is_super`
   → 1행이면 통과 (일반 코드 경로, 기존과 동일).
2. 0행이면 `SELECT EXISTS(... WHERE code=:code AND is_super)` → true면 **상태 변경 없이 통과**.
   super 코드는 `used_at`·`used_by`를 영원히 스탬프하지 않는다 → 무제한.
3. 둘 다 아니면 false → 기존대로 `INVALID_SIGNUP_CODE` 403 (미발급·소진 단일 응답 유지).

핵심 가드는 UPDATE의 `AND NOT is_super`다 — 이게 없으면 super 코드의 첫 가입자가
`used_at`을 스탬프해버리고, 이후 강등 시 그 코드가 소진 상태로 굳는다.

**isUsable(code)** (사전 검증) — `used_at IS NULL OR is_super`.

동시성: super 경로는 읽기 전용이라 레이스 없음. 일반 경로는 기존 원자 UPDATE 보장 그대로.

## 어드민 API

1. **적재** `POST /admin/signup-codes` — `SignupCodeCreateRequest`에 `isSuper` 필드 추가
   (Boolean, 생략·null 시 false, 배치 전체에 적용 — `super`는 Java 예약어라 record 컴포넌트명
   불가). 기존 검증 규칙(접두사 정규식·≤500·중복 스킵) 불변.
2. **승격·강등** `PATCH /admin/signup-codes/{code}` — 기존 `isSent` 갱신 요청에 `isSuper`
   옵션 필드 추가. 둘 다 옵션이고 온 필드만 갱신(부분 갱신, COALESCE). 둘 다 없으면 400.
   record 요청 DTO의 null 체크는 컨트롤러/서비스에서 수동으로 (기존 컨벤션).
   DTO는 용도가 넓어지므로 `SignupCodePatchRequest`/`SignupCodePatchResponse`로 개명하고
   응답은 반영된 최종 상태 `(code, isSent, isSuper)`를 돌려준다 (UPDATE ... RETURNING).
3. **조회** `GET /admin/signups` — 응답 row에 `isSuper` 추가.

## 동작 규칙 (엣지 케이스)

- 이미 소진된 코드를 super로 승격 → 즉시 무제한 가입 가능 (`used_at` 무시, 기존 스탬프는 보존).
- super 강등 → 보존된 `used_at` 상태로 복귀. 스탬프 없으면 1명 더 가입 가능한 일반 코드.
- super 코드 가입자 추적은 `signup_events`(detail.signupCode, detail.userId)가 정본.
  `used_by`는 super 코드에선 항상 NULL(또는 승격 전 스탬프)이며 가입자를 대표하지 않는다.
- 알려진 한계: 어드민 유저 상세(`GET /v1/admin/users/{id}`)의 signupCode 역참조는
  `used_by` 기반이라 super 코드 가입자에겐 null이다 — 필요 시 signup_events 조회로 보완(이번 범위 밖).

## 테스트

- `SignupCodeIntegrationTest` 확장: super 코드로 2명 이상 가입 성공 / used_at 미스탬프 확인 /
  소진 코드 승격 후 재가입 가능 / 강등 후 일반 규칙 복귀.
- `AdminSignupCodeIngestIntegrationTest`: super 플래그 적재.
- `AdminSignupIntegrationTest`: PATCH 부분 갱신(isSuper만·isSent만·둘 다·둘 다 없음 400), 조회 노출.
- ⚠️ 가입 테스트 429 플레이키: signUp 하는 테스트 클래스는 `was.rate-limit.per-minute=100` 프로퍼티 필수.
