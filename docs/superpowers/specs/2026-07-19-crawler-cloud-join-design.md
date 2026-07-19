# 크롤러 클라우드 합류 + 인프라 확정 설계

> 상태: 🟢 활성
> 작성: 2026-07-19 · 브레인스토밍 세션 결론
> 범위 제외: **raw 장기 아카이빙**(1년 보존·스토리지 타깃·롤링 프루닝)은 이 문서에서 다루지 않는다 —
> 후속 스펙으로 분리(§7). 본 문서는 크롤러의 인스턴스 합류와 그 전제가 되는 인프라 확정만 다룬다.

## 0. 배경 — 확인된 현 상태 (2026-07-19 실측)

- **서버(`hypenow-api`, 도쿄 A1 2 OCPU/12GB)는 이미 5컨테이너**: `postgres`(analysis)·
  `postgres-raw`(5433)·`analytics`(상주, 내장 크론 미러 04:30→분석 05:00→계정 카피 07:00 KST,
  LLM은 Gemini)·`was`·`caddy`. [07-15 오라클 배포 스펙](2026-07-15-oracle-deploy-design.md)의
  1단계에서 여기까지 진전된 상태이고, **crawler만 로컬 맥에 남아 있다.**
- **오라클 Always Free 한도(2026-06-15 축소 확정)**: A1 합계 2 OCPU/12GB(전량 사용 중),
  부트+블록 볼륨 합산 200GB(현재 부트 100GB만 사용 — **블록 볼륨 0개**), Object Storage 20GB(미사용).
  A1 증설 불가가 모든 결정의 전제.
- **실측 적재량**: 하루 1.0~1.25GB(이중 저장 포함), 방문 계정당 ~390KB/일.
  run_item 이중 저장 제거(아래) 후 **~200KB/일**. 어제(07-18) 기준 명단 12,837(뷰티 3,077)·
  게시물 29,842·raw DB 3.5GB(run_item 제외 시 1.9GB).
- **선행 완료**: COLLECT·REELS의 raw_run_item 이중 저장 제거(`JobName.archivesRunItems`) —
  `feat/skip-duplicate-run-items` 구현·테스트 완료. 하루 적재 절반 감소.
- **발견된 결함 2건**(이 설계의 전제 지식, 수정은 별도 태스크):
  ① 서버 rclone 미설정으로 **오프사이트 백업이 조용히 건너뛰어지는 중**(backup.sh의 조건 분기).
  ② base 뷰가 구스키마(`raw_post_detail`) 전제라 신형 수집분(raw_media_page)을 못 읽어
  **`v_contents` 0행 — FE 서빙과 신규 수집 데이터가 단절**(ARCHITECTURE §8 블로커의 실체).

## 1. 결정 요약

| # | 결정 | 근거 |
|---|---|---|
| 1 | **인스턴스 1대 유지** — 나누지 않는다 | 나누면 서빙이 1 OCPU/6GB로 반쪽. A1 상습 "Out of capacity"로 축소 후 재생성 실패 리스크. 격리 목적(디스크·메모리 폭주)은 볼륨 분리 + 컨테이너 메모리 제한으로 달성 |
| 2 | **DB는 자체 호스팅 Postgres 컨테이너 2개** (반영 완료) | 무료 관리형 Postgres는 용량 전멸(Neon/Supabase ~0.5GB), 오라클 무료 Autonomous는 Postgres 아님. 컨테이너 분리로 디스크·캐시 격리, 미러는 원래 DB 경계를 넘는 구조라 코드 무변경 |
| 3 | **crawler는 상주 컨테이너로 합류** | 어드민 `/ui`(루프백 8080)가 수동 트리거 창구. SSH 터널 접근 — 로컬 조작감 유지 |
| 4 | **운영은 수동 우선** — 크롤은 어드민 UI 수동 트리거 | 자동화(크론 체인)는 이후. 단 analytics 내장 크론(미러·분석)은 이미 가동 중이므로 유지 — 크롤을 수동으로 돌리면 다음 미러가 자동 반영하는 반자동 체제 |
| 5 | **블록 볼륨 100GB 부착, raw 데이터 이전** | 무료 200GB 전량 활용(부트 100+블록 100). raw 폭주가 OS·서빙 DB를 침범 못 하는 격리가 1대 유지 결정의 안전장치. 볼륨은 확장만 가능하므로 초기 분리가 유일한 기회 |
| 6 | **컨테이너 메모리 제한 도입** | 현재 전무. 서빙(was·postgres) 보호 — §2 예산표 |
| 7 | **CD는 락스텝 3이미지** | 모듈이 contract-analysis·analysis DB 스키마로 묶여 있어 같은 SHA로만 조합 검증됨 — §4 |
| 8 | **게시물 이미지는 OCI Object Storage + Vercel rewrite 캐시** | 인스타 CDN URL ~4일 만료 대응 + FE 과거 기간 화면의 전제. 무료 20GB는 리사이즈(WebP) 전제로 충분, 월 5만 요청 한도는 Vercel 엣지 캐시가 방어. Cloudflare 등 신규 벤더 불필요 |
| 9 | **기존 run_item 중복분(~2.1GB)은 raw 이사 덤프에서 제외로 정리** | DELETE+VACUUM 불필요 — 이사가 자연 정리 시점 |

## 2. 목표 구성 — 컨테이너 6개

```
[오라클 A1 1대 · 2 OCPU/12GB]
├─ caddy            80/443 공개            (기존)
├─ was              serving, 힙 2g         (기존)
├─ postgres         analysis DB → 부트볼륨  (기존)
├─ postgres-raw     crawler DB → 블록 볼륨 100GB (★데이터 이전)
├─ analytics        상주·내장 크론·8082 루프백 (기존)
└─ crawler          ★신설 상주·8080 루프백 — 수집 잡 + 어드민 /ui
```

| 컨테이너 | 메모리 제한(안) | 비고 |
|---|---|---|
| was | 2.5g | 힙 2g 고정(유휴 회수 방어) — 현행 |
| crawler | 1.5g | 힙 1g 내외, APIFY_TOKEN·Hiker 키는 서버 `.env` |
| analytics | 1g | 힙 768m — 현행 |
| postgres | 1g | shared_buffers 256MB로 상향(서빙 안정) |
| postgres-raw | 2g | shared_buffers 1GB(배치 읽기) |
| caddy | 128m | — |
| 합계 | ~8GB + OS ~1GB | 12GB 내 여유 ~3GB |

- 크롤(수동·주간)과 분석 크론(새벽)이 시간대로 자연 분리되어 피크 중첩 없음.
- 1,500계정/일 규모 기준 raw 성장 ~300MB/일 → 블록 볼륨 100GB로 **약 10개월 여유**.
  프루닝 정책은 아카이빙 스펙(§7)과 함께 확정 — 그전까지는 볼륨 사용률만 모니터링.

## 3. 마이그레이션 순서

역할 분담: 오라클 콘솔 조작(블록 볼륨 생성·부착)은 사용자, 나머지(compose·스크립트·이사)는 구현 작업.
클라우드 리소스 변경은 사용자 지시 전 실행하지 않는다.

1. **[0단계] 오프사이트 백업 개통** — rclone 리모트 설정으로 backup.sh의 Drive 업로드 활성화.
   유일하게 순서가 급한 항목(현재 app 스키마가 오라클 단일 장애점). 타깃(Drive 유지 vs B2 전환)은
   미결 — 아카이빙 스펙에서 확정하되, 개통 자체는 어느 쪽이든 즉시 가능.
2. **블록 볼륨 부착·이전** — 100GB 생성·부착(콘솔) → `/mnt/raw` 마운트 → postgres-raw 정지 →
   `pg-raw-data` 볼륨 데이터를 `/mnt/raw/pgdata`로 이동 → compose 볼륨 경로 교체 → 기동·검증.
3. **메모리 제한 적용** — §2 표대로 compose에 `mem_limit`(+ postgres `shared_buffers`) 반영.
4. **crawler 합류** — Dockerfile(was 패턴) + compose 서비스(루프백 8080, `postgres-raw:5432` 연결,
   `.env`에 APIFY_TOKEN 등 반입) + deploy.sh 3이미지 확장(§4).
5. **raw DB 이사** — 로컬 `crawler` DB 덤프(**COLLECT·REELS·RESNAPSHOT의 raw_run_item 제외**,
   ~1.9GB) → postgres-raw 복원 → 행 수 대조 검증. 로컬 크롤 중단 → 서버 어드민 UI로 첫 크롤 실행.
   로컬 DB는 이사 검증 완료까지 보존(보험) 후 dev 전용으로 전환.
6. **북키핑 정합 확인** — KST 달력일 재방문 선정이 서버 시간대에서 동일하게 동작하는지,
   Hiker·Apify 과금 카운터가 이어지는지 첫 실행에서 확인.

## 4. CD — 락스텝 3이미지

- **매 배포 = 같은 git SHA로 was·crawler·analytics 3이미지 빌드·push** (`:latest` + `:sha-<short>`).
  buildx 캐시로 무변경 모듈은 수 초. 배포 정본(compose 등) rsync도 Apply 단계에 포함.
- 컨테이너 성격별 적용: 상주(was·crawler)는 `up -d`가 바뀐 것만 재기동, analytics는 상주라
  재기동 대상이나 크론 시각을 피해 배포(새벽 04~07시 회피)하면 무중단.
- **DB 변경 섞인 배포 순서**: analytics Flyway는 기동 시 적용되므로 미러 DDL 변경 배포는
  analytics 재기동(마이그레이션) → 다음 미러 실행 → was 재기동 순. 뷰 SQL 변경은 psql 수동 적용
  후 미러 실행(현행 컨벤션 유지).
- **성숙도 로드맵**: ① 현행 — deploy.sh 수동 실행 → ② GitHub Actions가 develop push마다
  build+push 자동(적용은 수동 ssh 한 줄) → ③ apply·스모크·자동 롤백까지 자동(크롤 자동화와 동시기).
- 롤백: `:sha` 태그 되돌리기(현행 README §5 절차, 대상만 3이미지). DB는 forward-only.

## 5. 검증 기준

- 서버 어드민 UI(터널)에서 크롤 잡 실행 → postgres-raw 적재 확인 → 새벽 미러가 analysis 반영
  → `api.hypenow.io` 응답에 신규 데이터 노출 (단, base 뷰 재작성(§7) 전에는 신규 수집분이
  뷰에 안 잡히는 것이 정상 — 뷰 태스크 완료 후 최종 확인).
- 크롤 실행 중 was p95 응답과 메모리 상한 준수(`docker stats`) 확인.
- raw 이사 후 로컬 대비 행 수 일치(제외한 run_item 제외) + 서버 첫 크롤이 기존 북키핑에 이어짐.
- 디스크 격리 확인: postgres-raw의 데이터 디렉토리가 블록 볼륨(`/mnt/raw`) 위인지 `docker inspect`로 확인.

## 6. 이 설계로 해소되는 것

- 크롤·분석·서빙 전 파이프라인의 맥 의존 제거(썸네일 4일 만료 내 분석 보장 포함 — ARCHITECTURE §6).
- ARCHITECTURE §8 "미러 갱신 주기" 미결 — analytics 내장 크론으로 해소된 상태를 공식화.
- raw 디스크 폭주로부터 서빙 격리(볼륨·메모리 이중).

## 7. 명시적 제외·후속 태스크

| 항목 | 성격 |
|---|---|
| **raw 장기 아카이빙 스펙** — 1년 보존, 타깃(Drive 2TB vs Backblaze B2 vs Hetzner), append-only 일일 증분 + 소형 가변 테이블 미니 덤프 이중 구조, 볼륨 사용률 기반 프루닝 | 후속 스펙 (본 문서 제외 범위). 실측: 하루 압축 ~350MB(현 규모)·계정당 ~120KB(무A)/~60KB(A) |
| **base 뷰 신형 스키마 재작성** — raw_media_page payload에서 게시물 전개, v_contents 소생 | 별도 태스크. **현 시점 analytics 수정 금지**(사용자 방침) — FE 서빙 단절(§0-②) 해소의 선행 조건 |
| **이력 미러 누적화** — contents upsert·content_metric_snapshots append 전환 | analytics 재구축 시. raw 롤링과 FE 표시 기간을 분리(2만 계정 시대의 "두 달치 화면" 전제) |
| **이미지 재호스팅 파이프라인** — 수집 직후 리사이즈(WebP)→Object Storage, FE는 `/img/*` rewrite | crawler 확장 태스크 (결정 §1-8 구현) |
| **방문 정책 티어링 검토** — 2만 계정 시대의 방문 빈도·과금 설계 | 제품 결정 대기(현 방침: 전원 매일) |
| **크롤 자동화(크론 체인)** + CD 3단계 승격 | 수동 운영 안정화 후 |

## 8. 실측 부록 (2026-07-16~19, 근거 숫자)

- 하루 적재(on-disk): 927MB → 1,079MB → 1,254MB (이중 저장 포함, 방문 2.1~3.0천 계정/일)
- 구성: raw_run_item ~50%(이중 저장분 — 제거됨) / raw_media_page ~30% / raw_profile ~20%
- 프로필 payload의 92%가 내장 타임라인(비압축 244KB 중 224KB)
- 계정당 하루: on-disk ~390KB(무A)/~200KB(A)
- 규모→블록 볼륨 100GB 롤링 창: 1.5천/일 ~10개월 · 3천/일 ~5개월 · 2만/일 ~20일(A 적용 기준)
- raw_run_item 정체: 전 1.75GB 중 원형 가치는 SIMILAR 3MB뿐(나머지는 타입 테이블과 완전 중복)
