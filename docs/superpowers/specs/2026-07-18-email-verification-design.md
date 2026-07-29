# 이메일 소유권 인증 설계 (스펙 6.17 해소)

> 상태: 🗄 대체됨 — 07-29 이메일 인증 기능 전면 제거(클로즈베타 정책 변경, 프론트가 인증 스텝 폐지).
> 백엔드 인증 스택(게이트·엔드포인트·mail 패키지) 철거, V7 테이블만 Flyway 이력으로 잔존(ARCHITECTURE §7 07-29)
> 작성: 2026-07-18 · 브레인스토밍 세션 결정 기록
> 선행 맥락: [2026-07-15 API 스펙 정렬 §5](2026-07-15-hypenow-api-spec-alignment-design.md)가 6.17을
> [TBD]로 보류 → 07-18 설계-구현 전수 감사에서 "양측(was·celfit-front) 실체 없음" 확인 후 착수

## 1. 배경 / 문제

- 프론트 가입 마법사 스텝5(이메일 인증)는 순수 프레젠테이션 — `sendVerificationEmailAction`은
  실발송 없이 성공만 반환하는 스텁이라, 형식만 맞으면 소유권 무검증으로 가입이 완료된다.
- was에는 메일 의존성·발송/검증 엔드포인트·인증 상태 저장이 전부 없다(스펙 6.17 [TBD]).
- 이 설계는 was 백엔드에 이메일 소유권 인증을 실구현해 6.17을 해소한다.

## 2. 확정 결정 (브레인스토밍 Q&A)

| # | 질문 | 결정 |
|---|---|---|
| 1 | 인증 시점·미인증 처리 | **가입 전 인증 완료 강제** — 인증을 마쳐야 가입 자체가 성립. 미인증 유예·기능 제한 없음 |
| 2 | 소유권 증명 수단 | **6자리 코드 입력** — 마법사 안에서 완결(링크 방식은 반진행 마법사 복귀 문제로 탈락) |
| 3 | 발송 인프라 | **Resend** — HTTPS API 발송(오라클 SMTP 차단 무관), 무료 100통/일·3천/월로 가입 코드 게이트 MVP에 충분 |
| 4 | 구현 범위 | **백엔드 먼저** — 프론트 배선은 REST 전환(celfit-front PR #18 계속) 때 함께 |
| 5 | 증명 전달 방식 | **서버 상태** — `email_verifications` 행 verified 마킹 + 가입 시 1회 소비. 계약 변경 최소.<br>(검증 토큰 방식은 서명 키 관리·계약 확장 비용으로 탈락 — "인증 후 미가입 몇 분 틈에 제3자 가입" 이론상 위험은 가입 코드 게이트가 상쇄) |

## 3. 사용자 흐름

1. 스텝5에서 "인증 메일 발송" → `POST /v1/auth/email-verification/send` → 메일로 6자리 코드 수신
2. 코드 입력 → `POST /v1/auth/email-verification/confirm` → 성공 시 서버가 해당 이메일을 verified 마킹
3. 가입 `POST /v1/auth/signup` — **요청 계약 무변경.** was가 verified 행(유효 30분)을 확인하고
   가입 성공 시 1회 소비(삭제). 미인증이면 403.

## 4. API 계약

`/v1/auth/**`는 기존 화이트리스트(permitAll)에 이미 포함 — 신규 경로도 자동 공개.
envelope·CSRF(XSRF 쿠키+헤더)는 기존 `/v1/auth/*`와 동일 적용.

### POST /v1/auth/email-verification/send

- 요청: `{ "email": string }` → 성공 204 (본문 없음)
- 에러:
  - 400 `VALIDATION` — 이메일 형식 오류
  - 409 `EMAIL_ALREADY_EXISTS` — 이미 가입된 이메일(조기 반환 — 기존 signup 409와 동일 코드·동일 수준 노출이라 수용)
  - 429 `RATE_LIMITED` — 레이트리밋
  - 502 `EMAIL_SEND_FAILED` — Resend API 장애(코드 행은 저장하지 않음 — 발송 실패 시 재시도 유도)

### POST /v1/auth/email-verification/confirm

- 요청: `{ "email": string, "code": string }` → 성공 204
- 에러:
  - 400 `INVALID_CODE` — 불일치·만료·시도 초과·발송 이력 없음 **모두 동일 코드**(사유 비구분 — 열거 방지)
  - 429 `RATE_LIMITED`

### POST /v1/auth/signup (기존 확장)

- 검증 순서: 429(레이트리밋) → 403 `INVALID_SIGNUP_CODE`(가입 코드) → 400(필드 검증)
  → **403 `EMAIL_NOT_VERIFIED`(신설 — verified 행 없음/만료)** → 409 `EMAIL_ALREADY_EXISTS`(기존 코드)
- 가입 성공 직후 해당 이메일의 `email_verifications` 행 삭제(1회 소비 — 원자성 불요,
  삭제 실패로 잔존해도 verified 30분 만료로 무해).

## 5. DB — `V7__email_verifications.sql` (app 스키마, was 소유 Flyway)

```sql
CREATE TABLE app.email_verifications (
    email           text PRIMARY KEY,        -- lower 정규화(users와 동일 규칙)
    code_hash       text NOT NULL,           -- SHA-256(code) hex
    code_expires_at timestamptz NOT NULL,    -- 발급 +10분
    attempts        int  NOT NULL DEFAULT 0, -- confirm 오입력 횟수, 5회 초과 시 무효
    verified_at     timestamptz,             -- confirm 성공 시각. 유효 30분(가입 마감)
    created_at      timestamptz NOT NULL DEFAULT now()
);
```

- 재발송 = upsert: 코드 재생성·`code_expires_at` 갱신·`attempts` 0 리셋·`verified_at` NULL 초기화.
  → 재발송하면 이전 코드·이전 인증 상태는 무효(마지막 발송만 유효).
- 코드는 평문 저장 안 함. 6자리 코드는 엔트로피가 낮아 해시가 완전 방어는 아니며,
  주 방어선은 **짧은 TTL(10분) + 시도 제한(5회) + 레이트리밋**이다. BCrypt는 과함 — SHA-256으로 충분.
- `users` 테이블 **무변경** — 가입 전 강제 정책이라 가입된 계정은 전원 인증 완료 상태.
  verified 컬럼은 두지 않는다(YAGNI — 필요해지면 확장점 §9).
- 만료 행 정리: 별도 스케줄러 없이 send upsert가 자연 덮어쓰기. 잔존 행은 무해(가입 시 소비되거나
  만료 판정으로 사문화). 대량 누적 시 정리는 확장점.

## 6. 코드 생성·검증 규칙

- 코드: `SecureRandom` 6자리 숫자(000000~999999, 선행 0 허용 — 문자열 취급).
- confirm 판정 순서: 행 존재 → `attempts < 5` → `code_expires_at` 미경과 → 해시 일치.
  실패 시 `attempts + 1`(해시 불일치일 때만 — 만료·부재는 카운트 무의미), 응답은 전부 400 `INVALID_CODE`.
- verified 유효: `verified_at + 30분` 안에 가입해야 함. 초과 시 signup이 403 `EMAIL_NOT_VERIFIED`
  (재발송→재확인으로 복구).

## 7. 발송 어댑터 (was 평탄 패키지 — `com.celfit.was.mail`)

```
MailSender (인터페이스)          send(to, subject, text)
 ├─ ResendMailSender            Spring RestClient → POST https://api.resend.com/emails
 │                              (Bearer ${RESEND_API_KEY}) — 신규 의존성 0
 └─ LoggingMailSender           API 키 미설정 시 활성 — 수신자·코드를 INFO 로그로 출력
                                (로컬 개발·통합 테스트에서 코드 캡처용)
```

- 빈 선택: `was.mail.resend-api-key` 값 존재 시 Resend, 없으면 Logging (`@ConditionalOnProperty` 게이트).
- 설정 키 (application.yml):

```yaml
was:
  mail:
    resend-api-key: ${RESEND_API_KEY:}     # 빈 값 → LoggingMailSender
    from: "hypenow <no-reply@hypenow.io>"
```

- 메일 내용: 한국어 플레인텍스트. 제목 `[hypenow] 이메일 인증 코드`,
  본문에 코드 6자리 + "10분 안에 입력" 안내. 템플릿 엔진 불사용(문자열 조립).
- Resend 실패(비 2xx·타임아웃): 예외 → 502 `EMAIL_SEND_FAILED`. DB 행은 발송 성공 후에만 저장
  (실패했는데 코드가 유효해지는 상태 방지).

## 8. 레이트리밋 (기존 `RateLimiter` 재사용 — 분당 고정 윈도우·인메모리)

| 경로 | 키 | 한도 |
|---|---|---|
| send | 이메일 | 분당 1회 (재발송 쿨다운) |
| send | IP | 분당 5회 |
| confirm | IP | 분당 10회 |

## 9. 확장점 (비범위 — 필요 시 별도 설계)

- 비밀번호 재설정(프론트 "준비 중" 인라인) — 같은 send/confirm 골격 재사용 가능.
- 이메일 변경 시 재인증(`PATCH /v1/me`는 현재 이메일 변경 미지원이라 해당 없음).
- `users.email_verified_at` 감사 컬럼, 만료 행 배치 정리, HTML 메일 템플릿.
- 프론트 배선(celfit-front): 스텝5에 코드 입력 필드 + send/confirm 호출 + signup 403
  `EMAIL_NOT_VERIFIED` 처리 — REST 전환(PR #18 계속) 범위.

## 10. 테스트 (기존 패턴 — webmvc-test·Testcontainers·실 API 불호출)

- 해피패스: send → (LoggingMailSender에서 코드 캡처) → confirm 204 → signup 201, 행 소비 확인.
- 미인증 signup → 403 `EMAIL_NOT_VERIFIED` (verified 행 없음 / verified 30분 초과 두 경우).
- confirm: 오입력 5회 초과 무효, 코드 만료, 발송 이력 없는 이메일 — 전부 400 `INVALID_CODE`.
- 재발송: 이전 코드 무효·attempts 리셋·verified 초기화 확인. 이메일 분당 1회 429.
- send: 기가입 이메일 409, 형식 오류 400, Resend 실패 시 502 + 행 미저장(fake 어댑터로 실패 주입).
- 검증 순서: 가입 코드 불일치가 이메일 인증보다 먼저 403 나는지(§4 순서) 확인.

## 11. 운영 체크리스트 (코드 밖 — 배포 전 수행)

1. Resend 가입 → hypenow.io 도메인 인증(DNS에 SPF·DKIM 레코드 추가, DMARC 권장).
2. `RESEND_API_KEY`를 서버 `.env`에 등록 + `deploy/compose.yaml` was 환경변수에 추가.
3. **배포 순서 주의**: 이 기능이 운영에 배포되면 `/v1/auth/signup`은 인증 선행 없이는 403.
   현재 프론트는 백엔드 미배선이라 무영향이지만, REST 전환 시 스텝5 배선이 **선행**돼야 한다.
   curl 운영 검증도 send→confirm 선행 필요.
4. 문서 반영: API 스펙 정렬 문서의 6.17 [TBD]는 이 문서가 해소(상태 헤더 상호 링크),
   ARCHITECTURE §5 G·§7 결정 기록 갱신 — 구현 PR에 포함.
