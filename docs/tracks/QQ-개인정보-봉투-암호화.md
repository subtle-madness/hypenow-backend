# QQ — 개인정보 봉투 암호화

- **상태**: 🟢 활성 — PR 1(Task 1~6, 9~11) 준비 완료, PR 2(읽기 전환 + bidx UNIQUE 마이그레이션)·
  PR 3(contract — 평문 컬럼 DROP)는 후속 PR로 분리
- **설계 문서**: [specs/2026-09-03-pii-envelope-encryption-design.md](../superpowers/specs/2026-09-03-pii-envelope-encryption-design.md)
- **구현 계획**: [plans/2026-09-03-pii-envelope-encryption.md](../superpowers/plans/2026-09-03-pii-envelope-encryption.md)
- **배포·키 운영**: [deploy/README.md §6-3](../../deploy/README.md)

## 목표

was `app` 스키마의 개인정보 컬럼(`users.email/name/nickname/phone_number`,
`inquiries.name/email/organization/message`, `password_resets.email`,
`signup_events.email/ip`)이 평문으로 저장돼 있었다. DB 유출 시 그대로 노출되는 구조를
봉투 암호화(AES-256-GCM DEK + OCI Vault KEK)로 바꾼다 — 등가 검색(로그인 조회 등)은
HMAC 블라인드 인덱스(`*_bidx`)로 대체해 암호화 후에도 UNIQUE·조회 성능을 유지한다.
부수 목표 2개: 세션 principal에서 이메일을 제거(userId 문자열로 대체, 재로그인 없이
무중단 전환)하고, `signup_events`(비회원 이메일 포함)에 90일 보존 배치를 넣는다.

## 범위

- **PR 1**(이 트랙 파일을 작성한 시점의 범위 — Task 1~6, 9~11): `FieldCipher` 코어
  (AES-256-GCM 암복호화 + HMAC 블라인드 인덱스), `CryptoConfig`(crypto.mode local|vault
  분기 + 운영 fail-closed 가드), DEK 프로바이더(`DekWrapper`/`VaultDekWrapper`/`DekStore` —
  vault 모드 첫 부팅의 DEK 자동 부트스트랩 포함), expand 마이그레이션(`*_enc`·`*_bidx`
  컬럼 + `app.encryption_keys`), users/inquiries/password_resets/signup_events 이중 쓰기,
  PII 백필 커맨드(멱등, `--crypto.backfill=true`), 세션 principal 이메일→userId 교체,
  signup_events 90일 보존 스케줄러, compose env·운영 문서.
- **PR 2**(Task 7 롤아웃 게이트 통과 후): 스테이징·운영에서 백필 완료 확인 →
  `bidx` UNIQUE 마이그레이션 적용 → 조회 경로를 `email_bidx`/복호화 기준으로 전환
  (`UserRepository`·`PasswordResetRepository`·`AdminUserRepository`·`InquiryRepository`).
  레코드·컨트롤러 시그니처는 불변.
- **PR 3**(다음 릴리스, contract): 평문 컬럼 DROP(`users.email/name/nickname/phone_number` 등).
  expand-contract 규율상 PR 2 배포 후 최소 한 릴리스 간격을 두고서만 진행.

## 범위 밖 (후속)

- crawler·analytics 쪽 개인정보 컬럼(이번 트랙은 was `app` 스키마 한정).
- KEK 자동 로테이션 실행(개요만 §6-3에 기록 — 실제 실행은 별도 검증 후).
- 기존 아카이브(`archive.archived_rows`, 트랙 NN)에 이미 평문으로 이관된 행의 소급 정리.

## 스테이징 게이트 체크리스트 (Task 7 — PR 2 진행의 선행 조건)

1. develop→staging 배포 후 vault 기동 로그 확인 — 최초 1회는
   `DEK 부트스트랩 — key_id=1 신규 래핑본 등록 시도`, 이후 재기동은 조용한 언래핑만.
2. `SELECT key_id, created_at FROM app.encryption_keys;` 로 행 존재 확인.
3. 신규 가입 1건 → `email_enc`/`email_bidx`/`name_enc`/`nickname_enc`/`phone_number_enc`가
   채워지고 평문과 함께 기록되는지 확인(이중 쓰기 검증).
4. `--crypto.backfill=true` 1회 기동(§6-3 명령) → 로그의 테이블별 처리 건수 확인.
5. `SELECT count(*) FROM app.users WHERE email_enc IS NULL;` = 0 확인
   (`inquiries`·`password_resets`·`signup_events`도 동일 패턴).
6. 운영 배포(staging→main) 후 1~5를 운영에서 반복.
7. 위 전부 통과하면 PR 2(읽기 전환 + bidx UNIQUE 마이그레이션) 진행 승인.

## 미해결로 남긴 것

1. **기존 아카이브 행의 평문 잔존** — 트랙 NN(삭제 데이터 아카이브)의 `ArchiveWriter`는
   삭제 직전 행을 통째 이관한다. 이중 쓰기 기간(PR 1부터 계속) 동안 이관되는 행은 평문
   컬럼과 암호문 컬럼이 함께 아카이브 테이블에 남는다 — contract(PR 3) 이후 신규 아카이브는
   enc만 남지만, 그 전에 이미 쌓인 아카이브 행의 평문은 소급 정리 대상이 아니다.
2. **어드민 메모리 필터의 규모 한계** — 읽기 전환(PR 2)에서 `AdminUserRepository.findPage`의
   검색어 경로는 `WHERE` 없이 전체 SELECT 후 복호화·`contains` 필터·수동 페이지네이션이
   된다(암호문은 부분일치 검색이 불가능해서 — 블라인드 인덱스는 등가 검색만 지원).
   클로즈베타 규모(수백 명) 전제이며, 사용자 수가 늘면 재작업이 필요하다.
3. **세션 전환기 잔존** — Task 9(세션 principal 이메일→userId) 배포 시점에 이미 살아있던
   기존 email-principal 세션은 세션 목록·강제 로그아웃 화면에서 보이지 않는다(principal_name
   매칭이 깨짐). 재로그인을 강제하지 않는 설계라 별도 마이그레이션 없이 세션 만료(자연 소거)로
   해소된다.

## 검증

- `./gradlew :was:test` — 전체 통과(PR 1 마무리 시점 실행 결과는 PR 본문에 기록).
- Vault 실통신(KEK 래핑·언래핑, DEK 부트스트랩 동시성)은 로컬·CI에서 검증 불가 — 위
  스테이징 게이트 체크리스트가 그 부분의 유일한 검증 지점이다. `DekStoreTest`는
  `FakeDekWrapper`(테스트 전용, Vault 무관)로 부트스트랩 로직 자체(조회/생성/ON CONFLICT
  동시성)만 검증한다.
