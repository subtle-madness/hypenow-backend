> 상태: 🟡 개요 · 상세 계획 미작성 (2026-08-31)
> 범위: Phase 1 마일스톤 C(점진 개통·검증). **선행: 마일스톤 A·B 완료 필수.**
> 설계 정본: `docs/superpowers/specs/2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md`(§5·§8-5·§10). 선행 실측: 메모리 `hiker-self-scraping-breakeven.md`.

# 인스타그램 수집 하이브리드 — Phase 1 마일스톤 C(점진 개통·검증) 개요

> **주의:** 개요 문서. 착수 시 상세도로 재작성한다. 이 마일스톤은 코드 신규가 적고 **런타임 토글·메트릭·검증(A/B·벤치·e2e)**이 본체다.

**Goal:** 자체크롤을 운영에서 안전하게 켠다 — 런타임 토글·킬 스위치·Micrometer 메트릭을 배선하고, 스펙 §10의 열린 리스크(geo:kr 엔드포인트층·Hiker 지연)를 실측으로 닫은 뒤, dev/staging e2e로 개통한다.

## 1. 런타임 토글 + 킬 스위치 (스펙 §8-5)

app_setting 기반, 매 요청 재확인, 가역. **기준값은 crawler Flyway가 아니라 monitoring Flyway로 시드**(monitoring은 자체 `db/migration` 공간 — CLAUDE.md 독립 버전공간 4개 중 하나). 신규 마이그레이션은 **UTC 타임스탬프 채번**(`date -u +%Y%m%d%H%M%S`, `V<...>__ig_source_toggles.sql`, `ON CONFLICT DO NOTHING`).

| 키 | 값 | 기본 |
|---|---|---|
| `ig-source.primary` | self / hiker (경로별 or 전역) | **hiker**(개통 전) |
| `ig-source.proxy-tier` | residential / mobile, geo:kr on/off | 경로별(문서표면=레지, wpi=모바일) |
| `ig-source.profile-surface` | og / wpi | **og** |
| `ig-source.circuit-threshold` | 서킷 임계치 | 5 |
| **`ig-source.force-hiker`** | true → 즉시 전 경로 Hiker | **한 줄 롤백 스위치** |

`ProxySourceSetting` 패턴 참조(crawler `app_setting` key `proxy.source`, 매 요청 `current()` 재확인). monitoring엔 `AppSettingRepository` 상당이 있는지 확인 후 없으면 최소 구현.

## 2. 관측성 (스펙 §8-6)

- `(경로 × 백엔드 × 표면 × 결과클래스)` 단위 Micrometer 메트릭 — 자체 성공률·폴백률·401률·전송실패율.
- 기존 `TimedHikerHttp`의 `external.call`(태그 `api=hiker`, `operation`, `outcome`) 관용구 계승·확장 — 자체 백엔드도 같은 `external.call`에 `api=self`, `surface=embed|og|wpi|direct-comment`, `tier=residential|mobile`, `outcome=ok|401|4xx|transport|login-redirect`로.
- `application.yml` `management.metrics.distribution` 블록에 신 태그 히스토그램 노출(기존 `external.call: true` 재사용). Grafana에서 "자체가 Hiker를 얼마나 대체 중인지" 상시 가시화.

## 3. 검증 (스펙 §10 열린 리스크 닫기)

1. **geo:kr 엔드포인트층 A/B** — 실엔드포인트(web_profile_info·embed)에서 geo:kr on/off 401·성공률·지연 비교. 통과 못 하면 geo:kr 기본 off로 개통(전송층은 이미 100% 검증). 하니스: `poc/selfscrape-harness`(taxonomy·rotation-sweep·transport-ab 모듈 상비).
2. **프로필 og/wpi A/B** — 기본 og 확정 검증(og 80~90% vs wpi 73%/모바일 100%). 운영 기본값 확정.
3. **Hiker 지연 벤치마크** — 자체 embed p50 ~3.3s·og 1.8s(geo:kr) vs Hiker(미측정) 대조. 자체가 신뢰성뿐 아니라 실효지연도 우위인지 확정.
4. **dev/staging e2e** — 캠페인 추적·브랜드 스윕 경로에서 self 토글 on → 스냅샷 지표(좋아요·댓글·조회수) 정확·안정, 장애 주입 시 Hiker 폴백 동작, 킬 스위치 즉시 원복 확인. dev 스모크 계정·CSRF 로그인·실데이터 시딩(메모리 `dev-staging-testing`).

## 4. 부수 정리(개통 후)

- **불필요 재시도 경로 축소**(스펙 §7-2): 프리미엄 제거로 `metrics-retry-max: 6` 재시도 소멸, `fetchClipCounts` 보강 콜 소멸(embed 흡수). self 개통 후 CollectService의 fb 재시도(`retryFbForNewReels`·`retryClipsOnce`) 경로를 self 모드에서 우회. **단 Hiker 폴백 경로가 남는 한 완전 삭제는 신중히** — 토글 self일 때만 스킵.
- **인터페이스 개명**(마일스톤 A에서 미룬 순수 리네임): 필요 시 `resolveMediaByUrl`→`resolveShare` 등 스펙 §4 이름으로. 순수 리네임 단계로 격리(행동 불변).

## 5. 열린 리스크

- geo:kr KR 풀이 작아 16-way 동시성서 IP 반복→401↑ 가능(스펙 §10-1) — A/B 필수, 통과 전 off.
- monitoring `AppSettingRepository`·Flyway 공간 존재 여부 착수 시 확인(없으면 최소 배선 선행).
- 개통은 **경로별 점진**(예: 댓글부터 → 프로필 → 단건). 전역 self 일괄 전환 금지.

## 완료 기준(개요)

- [ ] 토글·킬 스위치 app_setting 시드(monitoring UTC Flyway) + 매 요청 재확인 배선.
- [ ] `(경로×백엔드×표면×결과)` Micrometer 메트릭 노출, Grafana 가시화.
- [ ] geo:kr 엔드포인트층 A/B·프로필 og/wpi A/B·Hiker 지연 벤치 실측 완료 → 운영 기본값 확정.
- [ ] dev/staging e2e(정확·안정·폴백·킬스위치) 전 항목 통과.
- [ ] 경로별 점진 개통, `ig-source.force-hiker` 한 줄 롤백 검증.

**운영 승격·PR·배포는 사용자 명시 승인 후.** 이 마일스톤도 push까지만.
