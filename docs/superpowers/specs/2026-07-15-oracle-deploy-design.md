# was+DB 오라클 클라우드 배포 설계 (백업·이식성 포함)

> 상태: 🟢 활성
> 작성: 2026-07-15 · 브레인스토밍 세션 결론

## 0. 배경과 결정

프론트(celfit-front.vercel.app)는 Vercel에 있고, 백엔드는 전부 로컬(맥)에서만 돈다.
프론트가 호출할 백엔드를 비용효율적으로 띄우기 위해 다음을 결정했다:

- **배포 범위는 was + analysis DB만.** crawler·analytics 배치는 당분간 로컬에서 수동 실행
  (미러가 클라우드 DB에 결과를 push). raw DB는 로컬 유지.
- **제공자는 Oracle Cloud Always Free** (월 $0, 상시 기동) — 가입·용량에서 막히면
  Vultr 서울(월 $6~12)로 폴백. AWS는 동일 구성이 월 $20+라 배제.
  - 오라클 무료 한도는 2026-06-15부로 A1 합계 **2 OCPU / 12GB로 축소**됨 — 처음부터 이 안에서 설계.
  - Always Free는 기간 무제한이지만 "오라클이 마음 바꾸기 전까지"로 간주 —
    **백업 + 30분 내 타사 재배포 가능한 구조**를 전제 조건으로 삼는다.
- 검토한 대안: ① Koyeb+Neon 무료 조합(콜드스타트 수십 초~분 단위라 데모에 부적합),
  ② 서울 VPS(확실하지만 유료), ③ AWS(비쌈). 상세 비교는 세션 기록 참고.

## 1. 대상 구성

- **오라클 A1.Flex 인스턴스 1대** — 홈 리전 **도쿄**(춘천 리전은 무료 A1 생성 불가),
  2 OCPU / 12GB, Ubuntu 24.04 **ARM64**, 부트볼륨 ~100GB (무료 한도 200GB 내).
- 인스턴스 위 **docker compose 스택 3컨테이너**:

  | 컨테이너 | 역할 | 비고 |
  |---|---|---|
  | `postgres:17-alpine` | analysis DB (분석 결과 + `app` 스키마) | 호스트 루프백에만 바인드 — 외부 비공개 |
  | `was` | Spring Boot REST API | `prod` 프로파일 신설 |
  | `caddy` | HTTPS 종단 + 리버스 프록시 | Let's Encrypt 자동 발급·갱신 |

- **DNS**: `api.hypenow.io` A레코드 → 인스턴스 공인 IP. (도메인 `hypenow.io` 확보 — 07-15.
  프론트는 `https://www.hypenow.io` — Vercel 커스텀 도메인.)
- **방화벽**: OCI Security List + ufw 이중 — 22(SSH 키 전용)/80/443만 개방. 5432는 열지 않는다.
- **was `prod` 프로파일**:
  - datasource → 컨테이너 postgres (자격증명은 서버의 `.env`, repo 미포함).
  - CORS 허용 오리진은 `https://www.hypenow.io` (dev 오리진들은 기본 프로파일에만).
  - **Flyway `*:missing` 완화 없이 엄격 검증** — ARCHITECTURE §8 "완화는 dev 국한" 미결이
    여기서 해소된다. 완화는 dev 프로파일에만 남긴다.
  - 태스크 G(✅) 인증이 세션 쿠키 기반 — 프론트(`www.hypenow.io`)와 API(`api.hypenow.io`)가
    **같은 등록 도메인의 서브도메인(same-site)**이라 세션 쿠키 전송은 자연 동작. 단 CSRF의
    XSRF-TOKEN 쿠키는 호스트 전용이라 www의 JS가 못 읽는다 — **Vercel rewrite로 같은 오리진화**가
    정식 연동 경로(런북 수록). 쿠키 왕복·로그인·저장 E2E를 배포 검증 항목에 포함한다.

## 2. 데이터 흐름 — 클라우드 DB를 채우는 방법

```
[맥] crawler ──쓰기──▶ 로컬 raw DB
[맥] analytics ──로컬 raw 읽기──▶ SSH 터널 ──▶ [오라클] analysis DB  (Flyway + 미러 push)
[오라클] was ──analysis DB 읽기──▶ Vercel 프론트
```

- analytics에 **클라우드 타깃 프로파일** 추가 — analysis 쪽 JdbcTemplate 연결 문자열만 교체,
  코드 무변경(§4-3 타입 미러 구조 그대로).
- 접속은 **SSH 터널**(`ssh -L 15432:localhost:5432` 래퍼 스크립트) — Postgres를 인터넷에
  노출하지 않는 가장 단순한 방법. Tailscale 등 별도 도구는 도입하지 않는다.
- 클라우드 DB 초기 스키마는 덤프 복원이 아니라 **Flyway 실행**(소스가 정본)으로 만든다 —
  분석 결과 스키마는 로컬 analytics를 클라우드 타깃으로 1회 실행, `app` 스키마는 was가 기동 시 자체 Flyway로.
  이후 미러로 데이터 채움.

## 3. 백업 — "오라클이 사라져도 잃는 건 없음"

- 서버 cron: **매일 `pg_dump` → 압축 → 7일 롤링 보관**.
- 맥에서 launchd(또는 수동 스크립트)로 최신 덤프를 **로컬로 pull** — 계정이 통째로 회수돼도 사본은 손안에.
- 복구 계층: 분석 결과는 로컬 raw DB에서 미러 재실행으로 전량 재생성 가능.
  **진짜 복구 불가 데이터는 `app` 스키마(계정·저장·메모 — 태스크 G)** — 덤프가 이것을 지킨다.

## 4. 이식성 — 클라우드 마이그레이션 대비

- 배포 정본은 전부 repo `deploy/` 디렉토리: `compose.yaml`, `Caddyfile`,
  서버 셋업 스크립트(docker 설치~기동), 운영 README(재배포 절차서).
- was 이미지는 **multi-arch(arm64+amd64) 빌드 → GHCR push** — 오라클(ARM)이든
  폴백 VPS(x86)든 동일 이미지. 배포 = 로컬 `buildx` push → 서버 `compose pull && up -d` (스크립트 1개).
- **이사 절차 = 새 VPS 셋업 스크립트 → 백업 복원 → DNS A레코드 변경. 목표 30분.**

## 5. 유휴 회수·비용 가드

- 무료 전용 계정은 7일 유휴(CPU·네트워크·메모리 모두 10% 미만) 시 인스턴스 정지 —
  JVM `-Xmx2g` 등 상주 메모리 확보로 메모리 조건(12GB의 10% = 1.2GB)을 자연 회피.
- 자리 잡으면 **PAYG 전환**(유휴 회수 면제, 한도 내 계속 $0) + **Budget 알림 $1** 설정.
  PAYG에서 구 한도(4/24) 사용 가능하다는 지원 답변이 있으나 문서와 충돌 — 2/12 안에서만 쓴다.

## 6. 역할 분담

| 사용자(직접) | 구현(클로드) |
|---|---|
| 오라클 가입·카드 검증 (도쿄 홈 리전) | `deploy/` 전체 (compose·Caddyfile·셋업 스크립트·README) |
| 인스턴스 생성 (제공되는 체크리스트 따라) | was `prod` 프로파일 |
| DNS A레코드 추가 | analytics 클라우드 타깃 프로파일 |
| GHCR 토큰 발급 | 터널·배포·백업 스크립트, 인스턴스 생성 가이드 |

## 7. 검증 기준

- 프론트(`https://www.hypenow.io`)에서 API 호출로 랭킹·상세·인플루언서 화면 정상 렌더.
- 로그인·저장(G) 플로우가 rewrite 경유로 동작 (쿠키 왕복 E2E).
- 로컬 미러 push → 클라우드 DB 반영 → 프론트 갱신 확인.
- 백업 덤프를 로컬 postgres에 복원해 무결성 확인 (복원 리허설 1회).
