# 개인정보 봉투 암호화(트랙 A) 설계

> 상태: 🟢 활성

## 배경·목표

멘토링 피드백(보안 및 인프라)의 구조를 구현한다: **대칭키(DEK)로 DB 원문을 암호화하고, 그
키를 KMS의 상위 키(KEK)로 한 번 더 암호화**해 평문 키가 디스크에 존재하지 않게 한다.
위협 모델은 "DB가 읽히는 사고"(SQL 인젝션, 자격증명 유출, 덤프 평문 노출 등) — 방화벽·백업
암호화(09-02, PR #709)가 못 덮는 마지막 층이다. 법적 의무(비밀번호 암호화)는 BCrypt로 이미
충족 상태이므로, 이 트랙은 의무 이행이 아니라 이메일·이름·전화번호 등 연락 가능 식별자의
자발적 보호다.

KMS는 신규 도입 없이 **기존 OCI Vault(`hypenow-vault`, ap-tokyo-1)를 재사용**한다 — 백업 키
때 만든 것으로, 소프트웨어 보호 키·시크릿 150개까지 Always Free.

## 비범위

- **crawler DB**(스크랩 데이터 속 바이오 이메일 등): 분석 뷰·LLM 파이프라인 전체가 평문을
  읽는 구조라 저장 암호화 시 분석 계층이 깨진다. 백업 암호화(09-02)가 오프사이트 유출을 이미
  덮는다. 제외.
- **트랙 B**(API 키·환경변수의 Vault 이관): 별도 트랙. 이 설계의 IAM 구조(컴파트먼트 분리)를
  재사용해 후속 진행.
- **개인정보 처리방침 문서**: 코드 작업 아님, 별도 진행.

## 대상 컬럼 (확정 범위)

| 테이블 | 컬럼 | 처리 | 비고 |
|---|---|---|---|
| `users` | email | enc + **bidx**(UNIQUE·로그인 조회) | 로그인 키 |
| `users` | name, nickname, phone_number | enc | 조회 없음 — bidx 불요 |
| `inquiries` | name, email, organization, message | enc | 비회원 문의. 어드민 목록은 앱에서 복호화 |
| `password_resets` | email | **PK를 email_bidx로 교체** + email_enc | 조회가 이메일 등가 매치뿐 |
| `signup_events` | email | enc + bidx(어뷰징 추적 조회 유지) | 비회원 이메일 포함 |
| `signup_events` | ip | enc | |
| `spring_session` | principal_name | **암호화 대신 값 교체** — principal을 이메일→userId 문자열로 | 아래 §세션 |

- `users.password_hash`(BCrypt), `password_resets.code_hash/token_hash`(해시)는 이미 안전 — 대상 아님.
- `users.company_name`·`usage_purpose`는 조직 정보라 v1 제외(개인 식별자 아님).

## 키 계층 (봉투 암호화)

```
[OCI Vault KEK (AES-256, SOFTWARE)] ──decrypt API──▶ [DEK 번들 (메모리에만)] ──▶ 컬럼 암호문
        키가 Vault 밖으로 안 나옴                      AES-256-GCM 키 + HMAC 인덱스 키
```

- **KEK**: `hypenow-vault`에 신규 마스터 키 `hypenow-pii-kek`(AES-256, protection-mode
  SOFTWARE — 무료, 로테이션 지원). 멘토 언급은 비대칭이었으나 봉투 암호화 표준(AWS KMS 등)인
  대칭 KEK를 채택 — 본질(DEK는 래핑된 채 저장, KEK는 KMS 밖 유출 불가)은 동일.
- **DEK 번들**: AES-256-GCM 데이터 키 + HMAC-SHA256 블라인드 인덱스 키(용도 분리로 2개).
  생성 후 KEK로 래핑해 `app.encryption_keys(key_id smallint PK, wrapped_dek bytea,
  created_at)`에 저장. **평문 DEK는 디스크·로그 어디에도 저장 금지.**
- **부팅 시**: was가 OCI SDK(instance principal)로 Vault `decrypt`를 호출해 DEK를 언래핑,
  메모리에만 보관. 부팅 1회 호출 — 운영 중 Vault 무의존.
- **IAM**: dynamic group `hypenow-instances`에 **이 KEK 하나에 대한 `use keys`만** 부여
  (`where target.key.id = <KEK OCID>` 조건). 백업 복호화 키는 시크릿(별개 리소스 타입 +
  정책 없음)이라 서버는 계속 읽기 불가 — 09-02 구조 유지.

## 암호문·블라인드 인덱스 형식

- 암호문 컬럼(`*_enc` text): `v1:<key_id>:<base64(iv)>:<base64(ct+tag)>` — 버전·키 접두사로
  키 로테이션 시 신구 공존 가능. IV는 값마다 랜덤(GCM 96bit) — 같은 평문도 다른 암호문.
- 블라인드 인덱스(`*_bidx` text): `HMAC-SHA256(인덱스 키, 정규화 평문)`의 base64. 등가
  검색·UNIQUE 제약 전용, 역산 불가. 이메일 정규화는 기존 규칙(lower) 재사용.
- 검색 의미론 변화: DB 레벨 부분 검색(ILIKE) 불가. **어드민 유저 검색은 앱 메모리 필터로
  유지** — `AdminUserRepository`의 `email ILIKE OR name ILIKE`를 "전체 로드(수백 행) → 복호화 →
  부분일치 필터"로 교체해 검색 UX 무변화(브랜드 검색은 company_name 비암호화라 그대로).
  클로즈베타 규모(현재 104명) 전제 — 수천 명 초과 시 재설계 필요(스펙 한계로 명시).
  로그인 등 서비스 경로는 정확 일치뿐이라 bidx로 충분.

## 세션 principal 교체 (재로그인 없음)

`AppUserDetails`의 직렬화 형상(필드 선언·serialVersionUID)은 **그대로 유지**하고:

1. `getUsername()`이 email 대신 `String.valueOf(userId)` 반환 — 신규 세션부터
   `principal_name`에 숫자 id만 저장.
2. 생성자에서 email 필드에 null 저장 — 신규 세션 blob에도 이메일 미포함.
3. `getUsername()`/email로 사용자를 찾던 코드 전수 조사 후 userId 조회로 교체
   (`getUserId()` 기존 제공).

기존 세션은 형상 불변이라 그대로 역직렬화 — **전원 재로그인 없음**. 기존 세션 blob의 이메일
잔존은 세션 만료 시 행 삭제로 자연 소거(원안이던 필드 제거는 레포 주석이 역직렬화 파손을
경고 — 채택 안 함).

## signup_events 보존기간 (암호화와 병행)

- **90일 경과분 일 배치 삭제** — 암호화는 유출 대비, 파기는 법적 의무(목적 달성 시 지체 없이
  파기, 비회원 이메일의 무기한 보유 근거 없음)로 서로 다른 층이라 둘 다 한다.
- 구현: was 스케줄러(기존 스케줄링 관용구 재사용) `DELETE FROM app.signup_events WHERE
  created_at < now() - interval '90 days'`. 삭제 건수 로그.

## 스키마 전환 — expand-contract (레포 규칙)

1. **expand** (릴리스 N): `*_enc`·`*_bidx` 컬럼 추가(전부 NULL 허용), `encryption_keys` 테이블
   생성. 코드는 **이중 쓰기**(평문 + 암호문) + 읽기는 아직 평문. `email_bidx` UNIQUE 인덱스는
   백필 완료 후 생성(부분 백필 상태 충돌 방지).
2. **백필**: 앱 레벨 커맨드(암호화가 앱 계층이라 SQL 불가) — 전 행 암호화 컬럼 채움 + bidx
   UNIQUE 인덱스 생성 확인. 클로즈베타 규모라 수 초.
3. **읽기 전환** (릴리스 N 또는 N+1): 조회를 `*_enc`/`*_bidx` 기준으로 전환. `password_resets`
   PK 교체 포함.
4. **contract** (릴리스 N+2): 평문 컬럼 DROP — `-- allow-destructive` + 보정 UPDATE 짝 규칙
   준수(migration-guard v2).

## 코드 구조 (was 평탄 패키지)

- `FieldCipher` 단일 서비스: `encrypt(String)→String` / `decrypt(String)→String` /
  `blindIndex(String)→String` + 부팅 시 DEK 언래핑. 적용은 리포지토리(JdbcClient) 계층에서
  명시적 호출 — 컨버터 마법 없음.
- **로컬·테스트 모드**: `crypto.mode=local`이면 Vault 없이 설정 주입 고정 키 사용(테스트
  프로파일 기본). 운영은 `crypto.mode=vault`.
- **실패 모드**: 부팅 시 Vault 불통이면 재시도(지수 백오프 수 회) 후 기동 실패 — 암호화 무결성
  우선. 운영 중에는 메모리 DEK로 Vault 무의존이라 영향 없음. 복호화 실패(손상 암호문)는 해당
  값 null 반환 대신 예외 — 조용한 데이터 소실 방지.

## 테스트

- `FieldCipher` 단위: 라운드트립, 같은 평문 다른 암호문(IV 랜덤), bidx 결정성, 버전 접두사 파싱.
- 통합(Testcontainers): 가입→로그인(bidx 조회) 왕복, UNIQUE 위반(중복 이메일), 어드민 목록
  복호화, password_resets 흐름, 백필 커맨드 멱등성, 이중 쓰기 기간 신구 read 정합.
- 세션: principal 교체 후 기존 형식 세션 역직렬화 호환(직렬화 blob 고정 픽스처).
- signup_events: 90일 경계 삭제 배치.

## 롤아웃 순서

1. Vault KEK 생성 + IAM 정책(키 1개 한정) — 인프라, 코드와 독립
2. expand 마이그레이션 + FieldCipher + 이중 쓰기 (PR 1)
3. 스테이징 백필 → 운영 백필 → 읽기 전환 (PR 2)
4. 세션 principal 교체 + signup_events 보존 배치 (PR 1~2에 동승 가능 — 독립적)
5. contract: 평문 컬럼 DROP (PR 3, 다음 릴리스)
