# 경쟁사 브랜드 광고 표기 판정 제거 (노출 + 판정 스킵)

> 상태: ✅ 구현됨 (2026-08-19, feat/ad-disclosure-competitor-skip — §5 수동 백필은 배포 후 잔여)

## 배경·결정

- 브랜드 모니터링의 광고 표기(ad disclosure) 판정이 경쟁사(competitor) 등록 브랜드에도 돌고, 응답에도 노출된다. 경쟁사에는 불필요하다고 판단해 **노출 제거 + 판정 자체 스킵**까지 하기로 결정(08-19 사용자 확정).
- 핵심 제약: `account_type`(own/competitor)은 **유저-브랜드 연결 행**(was `app.brand_monitorings`) 속성이다. 브랜드 풀은 유저 간 공유라 같은 브랜드가 A에겐 own, B에겐 competitor일 수 있다.
  - 따라서 판정 스킵 기준은 "**활성 own 연결이 하나도 없는 브랜드**"다(브랜드 단위 파생 플래그).
  - 노출 제거 기준은 "**조회 유저의 연결이 competitor**"다(연결 단위).

## 설계

### 1. monitoring: `brand_account.has_own_link` 파생 플래그 (expand)

- 신규 Flyway(monitoring 버전 공간, UTC 타임스탬프 채번): `ALTER TABLE brand_account ADD COLUMN has_own_link boolean NOT NULL DEFAULT true;`
- 기본값 true = "판정한다"가 안전 방향(동기화 실패 시 과판정으로 드리프트 — own 브랜드 미판정보다 낫다).
- `BrandRow`에 `hasOwnLink` 필드 추가.

### 2. was→monitoring 계약 확장 (정본은 was의 연결 원장)

- `POST /api/brands` 요청(`BrandRegisterRequest`)에 `accountType`(nullable, 기본 own) 추가.
  - monitoring 처리: **신규 생성 시** `has_own_link = (accountType != 'competitor')`. **기존 브랜드 재등록 시** own이면 true로 올리고, competitor면 변경 없음(내리려면 전체 진실이 필요하므로 안 내림).
- 신규 명령 `PUT /api/brands/{username}/own-link` 바디 `{ "hasOwnLink": boolean }` — 멱등 절대값 설정.
  - was가 연결 변이 커밋 후 원장에서 재계산해 push: `changeType`(양방향), `unregister`(마지막 유저가 아니어서 브랜드가 남는 경우). 등록(register)은 요청 필드로 커버되므로 별도 push 불필요.
  - 실패 처리는 `deregisterBrand`와 동일한 best-effort(warn 로그, 예외 전파 금지) 컨벤션.
- 드리프트 복구는 수동 SQL 런북(아래 §5) — 별도 resync 엔드포인트는 만들지 않는다.

### 3. monitoring: 판정 스킵 2지점

- 스윕 경로: `BrandCollectService.judgeAdDisclosuresSafely()`에서 브랜드의 `hasOwnLink == false`면 스킵(debug 로그). 킬 스위치 체크와 같은 층위.
- 백필 경로: `AdDisclosureJudgeService.backfillUnjudged()` 후보 쿼리에 `brand_account.has_own_link = true` 조인 필터 추가.
- 기존 판정 결과는 그대로 둔다(리셋 없음). own 연결이 생기면 플래그 true → 다음 스윕/백필에서 미판정분 자연 판정.

### 4. was: 경쟁사 조회자 노출 제거

- `BrandPostAssembler` 광고 필드 조립(현재 `exposeAdDisclosure && meta != null`)에 **조회 유저의 연결 accountType이 competitor면 제외** 조건 추가 — `adDisclosure=null`, `adViolations`/`adEvidence`는 빈 리스트.
- 게시물 목록·단건 등 광고 필드가 나가는 모든 조립 경로에 동일 적용(조회 유저의 연결 행 accountType을 스레딩).

### 5. 기존 데이터 백필 (런북 — 배포 후 1회 수동)

- was DB(app 스키마)에서 활성 own 연결이 있는 브랜드 username 집합을 뽑아, monitoring DB에서 그 외 브랜드를 `has_own_link=false`로 UPDATE.
- 마이그레이션이 기본 true라 백필 전에는 기존 동작 유지(과판정) — 안전.

### 6. 테스트

- monitoring: 등록 시 accountType별 플래그 초기화 / 기존 브랜드 own 재등록 시 승격 / own-link PUT 멱등 / 플래그 false 브랜드 스윕에서 판정 미호출 / 백필 쿼리 제외.
- was: changeType·부분 해지 시 own-link push 호출(및 실패 무해성) / competitor 조회자 응답에서 광고 필드 부재 + own 조회자는 기존대로.

## 비고

- 경계 준수: monitoring은 raw/analysis에 접근하지 않고, was가 명령으로 상태를 밀어주는 기존 패턴 그대로.
- expand-contract: 컬럼 추가만(expand). 파괴 변경 없음.
