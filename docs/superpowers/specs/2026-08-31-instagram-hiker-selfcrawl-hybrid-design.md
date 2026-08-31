> 상태: 🟢 활성 · 설계 확정, 구현 미착수 (2026-08-31)
> 범위: Phase 1(monitoring 결선)이 실행 1순위. Phase 2(crawler 이관)는 목표 아키텍처로 명시, 실행은 팀 승인 후 별도.
> 선행 실측: 메모리 `hiker-self-scraping-breakeven.md`(커버리지·표면별 안정성·전송/401·비용 전량). 관련 스펙: `2026-07-08-scrape-provider-abstraction-design.md`, `2026-07-26-profile-400-hiker-fallback-design.md`.

# 인스타그램 수집 아키텍처 — Hiker + 자체크롤 하이브리드 설계

## 1. 배경·동인

- **오늘의 장애 노출점**: monitoring은 현재 **100% Hiker 의존**(자체 폴백 0). 2026-08-27 Hiker 서버 다운이 사용자 2명을 직접 때렸다. monitoring 컨테이너엔 프록시·SELF env조차 없다.
- **crawler는 이미 자체크롤 주력**(프로필 86.5% SELF, 댓글 DIRECT, DataImpulse 레지덴셜, 재시도·서킷·16-way 동시성 보유). 즉 자체크롤 능력은 사내에 이미 있으나 monitoring이 못 쓴다.
- **표면·프록시 개선안이 별도 세션들에서 실측 확정**됨: 문서표면(embed/og) 채택·geo:kr 핀·모바일 K=1·에러 taxonomy. 이것들이 crawler 현행(미개선)보다 상위호환이다.
- **효율 동인**: Hiker 비용($600~1,000/100만콜) vs 자체 레지덴셜($20~200/100만콜) = 5~30배. 볼륨이 클수록 격차 확대.

**목표**: 효율(Hiker 비용·프록시 대역폭 최소화)과 안정성(Hiker 장애를 견딤)을 동시에 최대화한다.

## 2. 목표 / 비목표

**목표**
- 신 공유 모듈 `instagram-source`를 신설한다 — Hiker와 자체크롤을 한 인터페이스 뒤에 두고, **장애 시 상호 폴백(안정성)을 모듈 안에 가둔다**. 소비자 모듈은 어느 백엔드가 응답했는지 몰라도 된다.
- 모듈에 **검증 완료된 개선안**(문서표면·geo:kr·모바일 K=1·에러 taxonomy)을 처음부터 내장한다 — crawler 현행의 단순 복사가 아니라 상위호환.
- Phase 1: monitoring을 이 모듈의 소비자로 결선해 오늘의 장애를 방어한다.
- Phase 2(목표 명시): crawler도 이 모듈의 소비자로 이관 — IG 수집 엔진을 한 정본으로 수렴.

**비목표 (이번 범위 제외)**
- **하드게이트 3종**: ①해시태그 발견 ②tagged 발견 ③프리미엄 지표(저장·공유·리포스트). 발견 2종은 병렬 세션(`task_c86b0aa9`)이 비로그인 우회 조사 중, 프리미엄은 원 세션에서 논의 중. **둘 다 미결이라 이번 설계에서 제외** — 해당 경로는 현행 Hiker 그대로 두고 안 건드린다. 결론이 나오면 그때 모듈에 자체 백엔드 추가로 편입.
- **프리미엄 지표는 아예 없는 것으로 간주** — self 모드에서 저장·공유·리포스트 필드는 채우지 않는다. Hiker 이중 호출도 하지 않는다. (근거: Hiker에서도 세션복권 15~45%만 성공하는 불안정 필드이고 사용자 서빙 노출 증거 없음.)
- Phase 2(crawler 이관)의 실제 구현. crawler는 팀 소유·운영 중이라 팀 승인 후 별도 실행.

## 3. 핵심 결정 요약

| 결정 | 값 |
|---|---|
| 엔진 위치 | 신 공유 모듈 `instagram-source`(순수 fetch+DTO, DB 쓰기 없음). 경계 규칙의 새 예외(contract-analysis와 동류) |
| 소스 관계 | 자체크롤 1순위 + Hiker 폴백 (crawler와 동일 철학). 양방향 폴백 가능 |
| 소비자 | Phase 1 = monitoring만. crawler는 Phase 2에서 이관 |
| 프로필 표면 | og ↔ web_profile_info 둘 다 탑재, 런타임 토글. **기본값 `og`**, Phase 1 A/B로 운영 기본값 확정 |
| 프리미엄 지표 | 없는 것으로 간주 — 경로 자체 삭제 |
| 릴스 재생수 보강 | 삭제 — 단건 embed 조회수가 흡수 |
| 동기 등록 경로 | 타이트 사다리(자체 1순위 → 곧장 Hiker, 중간 표면 스킵) |

## 4. 아키텍처 — 신 모듈 `instagram-source`

**성격**: 순수 수집 어댑터. IG/Hiker에서 fetch → DTO 반환. **DB 쓰기 없음.** 각 소비자는 여전히 자기 스토어에만 쓴다(경계 규칙 위반 없음). contract-analysis가 "순수 JDK record"로 공유 예외이듯, 이 모듈은 "순수 fetch 어댑터"로 공유 예외가 된다.

**노출 인터페이스** (Hiker 모양의 안정된 계약 하나):
```
InstagramSource
  ├─ fetchProfile(username)        → ProfileInfo      [자체/Hiker 이중 백엔드]
  ├─ fetchPost(shortCode)          → PostInfo         [자체/Hiker 이중 백엔드]  ← 볼륨 본체
  ├─ fetchRecentPosts(...)         → List<PostInfo>   [자체/Hiker 이중 백엔드]
  ├─ fetchComments(shortCode, ...) → List<CommentInfo>[자체/Hiker 이중 백엔드]
  ├─ resolveShare(url)             → MediaRef         [자체/Hiker 이중 백엔드]
  ├─ fetchTaggedPage(userId, page) → ...              [Hiker 단독 — 하드게이트]
  ├─ fetchAuthorProfile(userId)    → ProfileInfo      [Hiker 단독 — by-id 로그인 전제]
  └─ fetchHashtagRecentPage(...)   → ...              [Hiker 단독 — 하드게이트]
```

**모듈 내부 구성**:
```
InstagramSource (인터페이스)
  └─ FailoverInstagramSource (정책: 자체 1순위 + Hiker 폴백 + 서킷)
       ├─ SelfCrawlBackend
       │    ├─ 표면 전략: 문서표면(embed/og) / API표면(wpi·딥페이징)
       │    ├─ 프록시 로테이션: 레지덴셜 K≈3 / 모바일 K=1, geo:kr 핀, fastfail
       │    ├─ 재시도 + 서킷브레이커 (crawler 자산 계승·개선)
       │    └─ DirectComment GraphQL (crawler DirectCommentFetcher 계승)
       └─ HikerBackend (기존 monitoring HikerClient 로직 이식)
```

두 백엔드는 **동일 DTO**(ProfileInfo/PostInfo/CommentInfo)로 정규화한다 — DTO는 현재 monitoring의 것을 모듈로 승격.

## 5. 경로별 소스·표면 매핑 (산출물 1)

### 5-1. 자체크롤 주력 경로 (신 모듈이 처리 — 5경로)

| 경로 | 1순위 표면 | 프록시 | 폴백 사다리 | 획득 지표·안정성 |
|---|---|---|---|---|
| **게시물 단건 지표** (볼륨 본체) | `/embed/captioned/` 문서표면, doc_id 불필요 | 레지덴셜 geo:kr | embed → Hiker | 좋아요·댓글·**조회수(릴스 재생수 포함)** ~100% raw |
| **프로필 통계** | **og**(기본) ↔ wpi (토글·A/B) | og=레지덴셜 / wpi=모바일 K=1 | 1순위 표면 → Hiker (핫패스 2단) | 팔로워·팔로잉·미디어수·bio. og 80~90% / wpi 73%(모바일 ~100%) |
| **최근 게시물 열거** | 프로필 페이지 그리드(최근 12) | 레지덴셜 | 그리드 → 딥페이징(feed/user, 재시도) → Hiker | 게시물 목록. 문서표면 견고 |
| **댓글** | DirectComment GraphQL | 레지덴셜 K≈3 | Direct → Hiker | 댓글. crawler 운영 검증 자산 |
| **share 링크 해소** (등록 시 1회) | share URL 리다이렉트 추적 → code | 레지덴셜 | self → Hiker | 저volume·best-effort |

### 5-2. 제외 경로 (하드게이트 — 현행 Hiker 그대로, 안 건드림)

| 경로 | 이유 |
|---|---|
| 태그드 열거 `fetchTaggedPage` | tagged 발견 = 로그인 벽 (병렬 세션 미결) |
| 해시태그 발견 `fetchHashtagRecentPage` | 해시태그 발견 = 로그인 벽 (병렬 세션 미결) |
| 작성자 프로필 by-id `fetchAuthorProfile` | userId 조회(users/{id}/info)=로그인 전제 + tagged에 종속. **후속 최적화 여지**: 태그드 payload에 username이 있으면 og/wpi 자체로 이전 가능 |

### 5-3. 삭제된 경로

- **릴스 재생수 보강**(`fetchClipCounts`, user/clips): 주 목적이 프리미엄 세션복권이었고(제거됨), FB 재생수 분해는 embed 단일 집계 조회수가 흡수. **401-prone API표면 경로 하나 제거** = 효율·안정성 이득.
- **프리미엄 지표 전체**(저장·공유·리포스트): 없는 것으로 간주.

### 5-4. 데이터 흐름

```
소비자(monitoring CollectService 등)
  → InstagramSource.fetchXxx()
    → [정책: 자체 1순위] SelfCrawlBackend
        → 표면 사다리(문서표면 → API표면) + 프록시 로테이션 + 재시도 + 서킷
        → 성공: 표면 무관하게 동일 DTO로 정규화
        → 실패(서킷 열림·전 표면 소진): HikerBackend 폴백
  → 소비자는 백엔드/표면 모름. 자기 스토어에만 저장.
```

## 6. monitoring 자체 폴백 도입 (산출물 2)

현재 monitoring 구조(스카우트 확인): `HikerClient`(9개 메서드) → `HikerHttp` 인터페이스 → `JdkHikerHttp`. 폴백 삽입점이 이미 인터페이스로 격리돼 있다.

**결선 방식**:
- monitoring의 `HikerClient` 호출부(CollectService·BrandCollectService·RegistrationService·ShareResolveService)를 `InstagramSource` 주입으로 교체. 시그니처가 거의 같아 호출부 변경 최소.
- 5-1의 5경로는 `InstagramSource`의 이중 백엔드 경로로, 5-2의 3경로는 `InstagramSource` 내 Hiker 단독 구현으로.
- monitoring 컨테이너에 **프록시 env 추가**: `DATAIMPULSE_RESIDENTIAL_PROXY_URL`, `DATAIMPULSE_MOBILE_PROXY_URL`(시크릿, crawler와 동일 조달). 현재 monitoring엔 `HIKER_API_KEY`만 있음.
- **모듈 공유 금지 원칙 감안**: crawler 자산을 monitoring이 직접 import하지 않는다 — 자산은 신 공유 모듈로 승격되고 두 모듈이 그 모듈에 의존. Phase 1에서는 monitoring만 의존.

**저장은 변화 없음**: 정규화된 DTO를 monitoring이 기존 `SnapshotWriter`/`SnapshotRepository`로 자기 스토어(post_snapshot·profile_snapshot·post_comment)에 저장. `fb_plays` 컬럼은 self 모드에서 null(내부 회계용, 서빙 미노출).

## 7. 효율 (산출물 3)

### 7-1. Hiker 잔존 최소화 (콜 kind 구성 기준)

| kind | 현재 비중 | 이행 후 |
|---|---|---|
| COMMENTS | 62% | **→ 자체(DirectComment)**. 최대 덩어리가 자체로 |
| PROFILE_BY_ID | 27% | Hiker 유지(하드게이트). username 확보 시 후속 이전 여지 |
| TAGGED | 5.6% | Hiker 유지(하드게이트) |
| 캠페인 프로필(by-username)·단건·기타 | 나머지 | 프로필·단건 → 자체 / 해시태그 → Hiker |

Hiker 잔존은 하드게이트 + 자체 폴백 미스분으로 수렴.

### 7-2. 콜 수 자체가 감소 (지표 이동이 아니라 감소)

- **프리미엄 제거로 `metrics-retry-max: 6` 재시도 전량 소멸** — 게시물마다 최대 6회 재시도가 사라짐.
- `fetchClipCounts` 계정별 보강 콜 소멸(embed 흡수).
- self 주력은 게시물당 embed 1콜로 확정 → **현재보다 총 콜 수 감소**.

### 7-3. 프록시 비용 경로별 배분

| 표면 | 프록시 | 근거 |
|---|---|---|
| embed·og·댓글·그리드 (볼륨 본체) | 레지덴셜 geo:kr | 문서표면=401 안 탐 → 싼 레지덴셜로 충분. geo:kr로 전송 ~100%·지연 -20% |
| web_profile_info 폴백 (소수) | 모바일 K=1 | 401-민감 API표면만. 볼륨 작아 블렌드 영향 제한 |

비싼 모바일은 wpi 폴백 경로에만. 프로필 기본값 og면 모바일 사용은 더 적어짐.

### 7-4. doc_id-free

embed·og = doc_id 불필요 → 2~4주 회전 doc_id 유지보수 부담 소거. API표면(wpi·딥페이징)만 x-ig-app-id 사용(doc_id 아님). **회전 doc_id 유지보수 0.**

### 7-5. 비용·속도 비교 (메모리 실측)

- **비용**: 100만 콜 Hiker $600~1,000 vs 자체 레지덴셜 $20~90(오버헤드 2배 ~$200) = **5~30배 저렴**. 진짜 동인은 프록시비가 아니라 유지보수 인력이나, embed 채택으로 doc_id 유지보수가 사라져 부담 크게 낮음.
- **속도**: 자체 embed p50 ~3.3s, 프로필 og p50 1.8s(geo:kr). 모바일 경로만 꼬리 큼(→fastfail). **Hiker 자체 지연은 미측정 → Phase 1 벤치마크 항목.**
- **신뢰성**: 단건 지표에서 자체 embed ~100% vs Hiker 세션복권 15~45% — 자체가 비용·신뢰성·실효지연 모두 우위.

## 8. 안정성 (산출물 4) — 6계층 방어

| 계층 | 담당 실패 | 메커니즘 |
|---|---|---|
| 1. 표면 | 401 rate-limit 자체 | 문서표면(embed/og) 1순위 = 익명 한도를 구조적으로 안 탐 |
| 2. 전송 | ConnectError/TLS | geo:kr 핀 + fastfail(~3s) + 새 터널 1회 재시도 |
| 3. rate-limit | 잔여 401(API표면) | 경로별 티어: 문서·회복가능=레지덴셜 K≈3, 401-민감=모바일 K=1. sticky 금지 |
| 4. 백엔드 폴백 | 표면 붕괴·구조적 400·비가역 | 자체 → Hiker, 동일 DTO 정규화 |
| 5. 서킷브레이커 | 지속 장애 | 5연속 블록 트립 → 이후 요청 자체 스킵, 곧장 Hiker(캐스케이드 세금 회피) |
| 6. 에러 taxonomy | 오분류 | 각 실패를 클래스로 분류해 맞는 대응 라우팅 |

### 8-1. 에러 taxonomy → 대응 라우팅

```
구조적 400 (natgeo류)   → 재시도 없이 즉시 Hiker 폴백
회복가능 401 (익명한도)  → 티어 내 로테이트+재시도(K≈3 or 모바일 K=1)
전송 TLS/Connect        → geo:kr 적용 + 새 터널 1회 재시도
로그인 리다이렉트(HTML)  → 다음 표면 → Hiker
404 notFound           → 종료(삭제/비공개), 스킵. 폴백 안 함
```

### 8-2. 경로별 폴백 사다리

```
게시물 단건 : embed(레지덴셜 geo:kr, fastfail) → [서킷 열림 시 스킵] → Hiker
프로필      : [토글 1순위 표면, 기본 og] → Hiker   (핫패스 2단, 나머지 표면은 백그라운드 재조정만)
댓글        : DirectComment(레지덴셜 K≈3) → Hiker
동기 등록   : 자체 1순위 → Hiker  (중간 표면 스킵 = 타이트 사다리)
share 해소  : self redirect → Hiker
```

### 8-3. 지연 설계

- **대부분 요청은 폴백 미탑승**: embed ~100%·og 80~90% → P50·P90은 1순위 성공, 캐스케이드 비용은 실패 꼬리(P95+)에만.
- **fastfail(~3s)**로 죽은 프록시 매달림(12~26s) 절단.
- **티어별 시간 예산**으로 캐스케이드 최악값 고정.
- **배치(일일 스윕)는 지연 무관**(처리량 중심). **지연 민감 경로는 동기 등록 첫 수집뿐** → 타이트 사다리(자체 1순위 → 곧장 Hiker).
- 프로필 핫패스 2단화(1순위 표면 → Hiker)로 3단 캐스케이드 회피.

### 8-4. 서킷 입도

**표면별 서킷**(백엔드 내): self-embed 건강한데 self-wpi만 트립하면 embed는 계속 자체로. 여기에 **self-백엔드 전역 킬**(광범위 붕괴 시 자체 전체 차단).

### 8-5. 런타임 토글 + 킬 스위치

app_setting 기반, 매 요청 재확인, 가역:
- `ig-source.primary` — self / hiker (경로별 or 전역)
- `ig-source.proxy-tier` — 경로별 residential / mobile, geo:kr on/off
- `ig-source.profile-surface` — og / wpi (기본 og, A/B용)
- 서킷 임계치
- **★ 킬 스위치 `ig-source.force-hiker=true` → 즉시 전 경로 Hiker.** 자체크롤이 운영에서 깨지면 한 줄로 즉시 원복

### 8-6. 관측성

`(경로 × 백엔드 × 표면 × 결과클래스)` 단위 Micrometer 메트릭 — 자체 성공률·폴백률·401률·전송실패율 실시간. monitoring의 `TimedHikerHttp`(`external.call`) 계승·확장. Grafana에서 "자체가 Hiker를 얼마나 대체 중인지" 상시 가시화.

## 9. 이행 단계

**Phase 1 (실행 1순위 — 오늘의 장애 방어)**
1. 신 모듈 `instagram-source` 신설: 인터페이스·DTO·FailoverInstagramSource·SelfCrawlBackend(문서표면 우선)·HikerBackend(monitoring 로직 이식).
2. 자체크롤 저수준: JdkInstagramWebClient 상당 + 프록시 로테이션(레지덴셜 K≈3/모바일 K=1, geo:kr, fastfail) + DirectComment. crawler 자산을 참조하되 개선안 내장(embed 문서표면·에러 taxonomy).
3. monitoring 결선: HikerClient 호출부를 InstagramSource로 교체, 프록시 env 추가.
4. 토글·킬 스위치·메트릭 배선.
5. **검증**: geo:kr 엔드포인트층 A/B, 프로필 og/wpi A/B, Hiker 지연 벤치마크, dev/staging e2e.

**Phase 2 (팀 승인 후 별도 실행 — 정본 수렴)**
- crawler를 `instagram-source` 소비자로 이관. SelfProfileFetcher/SelfWithHikerFallback/JdkInstagramWebClient가 모듈로 흡수되고 crawler는 호출자만 남음.
- 운영 중(86.5%)이라 expand-contract·토글 점진 이행. 팀 소유이므로 리더 승인 필수.

## 10. 열린 리스크·검증 항목

1. **geo:kr 엔드포인트층 영향 미측정** — 전송층(robots.txt)만 100% 검증. 실엔드포인트(web_profile_info) 401 영향은 KR 풀이 작아 경합↑ 가능. **Phase 1 실엔드포인트 A/B 필수**, 통과 전 geo:kr 기본 off 가능.
2. **Hiker 자체 지연 미측정** — 자체 vs Hiker 지연 우열은 Phase 1 벤치마크로 확정.
3. **IG 표면 변경 리스크** — embed는 장수 엔드포인트라 doc_id API표면보다 낮으나 0은 아님. **Hiker 폴백 상시 유지 + 킬 스위치**가 방어.
4. **모바일 프록시 꼬리 지연**(26s 행 관측) — fastfail 필수. wpi 폴백에만 쓰므로 영향 제한.
5. **경계 규칙 새 예외** — 신 공유 모듈은 contract-analysis 외 첫 공유 모듈. "순수 fetch+DTO, DB 쓰기 없음" 성격을 엄격히 유지해 경계 침식 방지.
6. **하드게이트 편입** — 병렬 세션(발견 우회·프리미엄) 결론 시 모듈에 자체 백엔드 추가로 편입. 인터페이스는 이미 그 메서드를 노출(현재 Hiker 단독 구현).

## 11. 부록 — 현 구조 실측 (스카우트, 2026-08-31)

- **monitoring**: `HikerClient` 단일 진입점 → `HikerHttp` → `JdkHikerHttp`. 데코레이터(Timed/Recording/Counting). SELF·프록시 env 전무. DTO: PostInfo/ProfileInfo/AuthorInfo/CommentInfo/MediaRef.
- **crawler**: SelfProfileFetcher(web_profile_info 직접)·SelfWithHikerFallbackProfileFetcher·JdkInstagramWebClient(프록시 로테이션·HTTP2·x-ig-app-id)·DirectCommentFetcher. ProxySource enum(DIRECT/APIFY/DATAIMPULSE_RESIDENTIAL/DATAIMPULSE_MOBILE), app_setting 토글(proxy.source/profile.source/comment.source). InstagramWebClient·ProfileFetcher 두 포트 뒤에 격리 → 저수준~중수준 분리 가능, CrawlExecutor(과금·저장)는 crawler 도메인 결합.
