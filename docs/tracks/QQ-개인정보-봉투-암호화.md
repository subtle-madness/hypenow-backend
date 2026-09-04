# QQ — 개인정보 봉투 암호화

- **상태**: 🟢 활성 — PR 1 운영 반영(09-03) · PR 2(읽기 전환) 진행 중 · PR 3 후속
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
  (`UserRepository`·`PasswordResetRepository`·`AdminUserRepository`·`AdminSignupRepository`).
  `InquiryRepository`는 제외 — 애초에 읽기 경로가 없다(INSERT뿐, 운영자는 psql로 평문을
  읽어왔다 — §미해결 참고). 레코드·컨트롤러 시그니처는 불변.
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
7. 구 세션 principal_name 이전 확인(`SELECT count(*) FROM app.spring_session WHERE
   principal_name LIKE '%@%'` = 0) — 0이 아니면 마이그레이션이 아직 안 돈 것이거나
   email 형태의 principal_name을 만드는 다른 경로가 남아있다는 뜻.
8. 위 전부 통과하면 PR 2(읽기 전환 + bidx UNIQUE 마이그레이션) 진행 승인.

## PR 2 게이트 체크리스트 (운영 배포 전 순서 — [deploy/README.md §6-3](../../deploy/README.md) 상세)

1. **사전 확인 쿼리** — 대소문자만 다른 중복 이메일 없음
   (`SELECT count(*) FROM (SELECT lower(email) FROM app.password_resets GROUP BY 1 HAVING
   count(*)>1) d` = 0)과 4테이블 각 `*_enc IS NULL` = 0. **09-04 운영·스테이징 사전 점검
   결과: 중복 0·NULL 0 — 통과.**
2. **스테이징 정합 검증** — develop→staging 배포 후 `-Dcrypto.verify=true` 1회 기동 →
   로그 합계=0 확인.
3. **스테이징 수기 검증** — 기존 계정 로그인(대문자 이메일 포함, 정규화 확인) · 어드민
   목록/검색 · 비밀번호 재설정 1회 완주 · 중복 이메일 가입 거부(bidx UNIQUE) 확인.
4. **운영 정합 검증** — staging→main 승격 **전에** 운영에서 `-Dcrypto.verify=true` 1회
   기동 → 로그 합계=0 확인.
5. 위 전부 통과하면 운영 승격(staging→main).

## 미해결로 남긴 것

1. **기존 아카이브 행의 평문 잔존** — 트랙 NN(삭제 데이터 아카이브)의 `ArchiveWriter`는
   삭제 직전 행을 통째 이관한다. 이중 쓰기 기간(PR 1부터 계속) 동안 이관되는 행은 평문
   컬럼과 암호문 컬럼이 함께 아카이브 테이블에 남는다 — contract(PR 3) 이후 신규 아카이브는
   enc만 남지만, 그 전에 이미 쌓인 아카이브 행의 평문은 소급 정리 대상이 아니다.
2. **어드민 메모리 필터의 규모 한계** — 읽기 전환(PR 2)에서 `AdminUserRepository.findPage`의
   검색어 경로는 `WHERE` 없이 전체 SELECT 후 복호화·`contains` 필터·수동 페이지네이션이
   된다(암호문은 부분일치 검색이 불가능해서 — 블라인드 인덱스는 등가 검색만 지원).
   클로즈베타 규모(수백 명) 전제이며, 사용자 수가 늘면 재작업이 필요하다.
3. **세션 전환기 잔존(대부분 해소)** — Task 9(세션 principal 이메일→userId) 배포 시점에 이미
   살아있던 기존 email-principal 세션은 배포 후 첫 요청에서 spring-session-jdbc가
   PRINCIPAL_NAME을 자가 재기록해 이전된다. 그 사이 남는 공백(배포 후 한 번도 요청이 안 온
   구 세션)은 마이그레이션(`V20260903080645__session_principal_email_to_user_id.sql`)이
   `app.users`와 조인해 전량 이전한다 — 재로그인 강제 없이, 세션 만료(자연 소거)를 기다릴
   필요도 없이 해소. 세션 목록·강제 로그아웃(`SessionService.deleteOthers`/`deleteAll`)이
   이제 배포 이전 세션까지 포함해 정상 동작한다.
4. **DEK 에스크로 — PR 3(평문 DROP) 전에 반드시 수행** (09-03 결정: 방안 A). 키 계열 비대칭
   때문이다 — 백업 age 비밀키는 맥·비밀번호 관리자·Vault 3중이지만, DB 컬럼 키(DEK)는 OCI
   Vault의 `hypenow-pii-kek`로만 풀린다. OCI 테넌시를 통째로 잃으면 B2 백업 안의 래핑된 DEK를
   풀 방법이 없어 **암호화 컬럼(이메일·이름·전화)은 백업이 있어도 복구 불가**가 된다. PR 1·2
   동안은 평문 컬럼이 남아 있어 age 키만으로 완전 복원되지만, PR 3에서 평문을 지우는 순간
   이 의존이 실재한다. 대안 B(의존 수용 — 연락처는 재수집 가능)를 검토했으나 "최악의 경우"가
   백업의 존재 이유인데 그때 개인정보만 잃는 구조는 어정쩡해 A를 택했다.
   **절차(코드 변경 불필요 — 관리자 계정으로 맥에서 1회)**: 운영 DB의 래핑본을 꺼내
   Vault로 직접 언래핑해 비밀번호 관리자에 보관한다. 평문 DEK가 서버·디스크에 남지 않게
   파이프로만 다룬다.
   ```bash
   # ① 운영 DB에서 래핑된 DEK(base64) — 서버 경유 읽기 전용 조회
   ssh ubuntu@<IP> "docker exec deploy-postgres-1 psql -U <DB_USER> -d analysis -tAc \
     \"SELECT encode(wrapped_dek,'base64') FROM app.encryption_keys WHERE key_id=1\""
   # ② 맥에서 KEK로 언래핑(Administrators 권한) → 출력 한 줄(base64 64바이트)을 비밀번호 관리자에
   #    "hypenow PII DEK key_id=1" 보안 메모로 저장. 터미널 스크롤백·클립보드 기록은 지운다.
   oci --profile HYPENOW kms crypto decrypt --key-id <KEK_OCID> \
     --endpoint https://ezvjprllaacng-crypto.kms.ap-tokyo-1.oraclecloud.com \
     --ciphertext "<①의 출력>" --query 'data.plaintext' --raw-output
   ```
   복구 시나리오(OCI 유실 후 새 환경): `app.encryption_keys`에 새 KEK로 재래핑한 행을 넣거나,
   임시로 `crypto.mode=local` + `crypto.local-key-base64=<에스크로 값>`으로 기동하면 기존
   암호문이 그대로 풀린다(암호문 형식이 KEK와 무관 — `v1:<key_id>:…`는 DEK만 가리킨다).
   DEK 로테이션 시 에스크로도 갱신한다. 스테이징 DEK는 에스크로 대상 아님(재생성 가능).
5. **inquiries 읽기 경로 부재 — PR 3 전에 반드시 해결**. `InquiryRepository`는 INSERT뿐이고
   운영자는 지금까지 psql로 평문 `email`/`name`/`organization`/`message`를 직접 읽어왔다
   (`InquiryRepository.java:8` 주석). PR 3(contract)에서 평문 컬럼을 DROP하기 **전에**
   어드민 조회 API 또는 복호화 덤프 러너 중 하나를 먼저 만들어야 한다 — 안 그러면 문의
   내용을 영영 못 읽게 된다.
6. **`ArchiveTables.USER_PII` omit 목록에 `*_enc` 컬럼 누락**(`ArchiveTables.java:25-27`) —
   현재 목록은 평문 컬럼(`email`/`name`/`nickname`/`phone_number` 등)만 마스킹 대상으로
   잡고 있어, 탈퇴 아카이브(`archive.archived_rows`)에 `email_enc`/`name_enc`/
   `nickname_enc`/`phone_number_enc` 등 암호문이 그대로 남는다. PR 3에서 `USER_PII`와
   `ArchiveWriterTest.EXPECTED_USER_PII`(교차 검증용 하드코드 기대값)를 함께 갱신해야 한다.
7. **스펙 이탈 기록 — password_resets PK 교체는 PR 3(contract)로 이연**. 스펙 §전환 3은
   "`password_resets` PK를 `email`에서 `email_bidx`로 교체"를 전제했으나, PR 2에서는
   `ON CONFLICT (email)`이 여전히 평문 PK를 겨냥한다(`PasswordResetRepository.java:15-19`
   근거 — PK 교체는 평문 컬럼을 걷어내는 PR 3 몫으로 명시적으로 미뤘다). 두 UNIQUE
   키(평문·`email_bidx`)가 같은 1행을 가리키는 동안은 "이메일당 1행" 불변식이 깨지지
   않으므로 PR 2 기능엔 영향 없다.

## 검증

- `./gradlew :was:test` — 전체 통과(PR 1 마무리 시점 실행 결과는 PR 본문에 기록).
- Vault 실통신(KEK 래핑·언래핑, DEK 부트스트랩 동시성)은 로컬·CI에서 검증 불가 — 위
  스테이징 게이트 체크리스트가 그 부분의 유일한 검증 지점이다. `DekStoreTest`는
  `FakeDekWrapper`(테스트 전용, Vault 무관)로 부트스트랩 로직 자체(조회/생성/ON CONFLICT
  동시성)만 검증한다.
