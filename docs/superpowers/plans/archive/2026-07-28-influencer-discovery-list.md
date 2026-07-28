# 발굴 목록 GET /v1/influencers (스펙 6.21) — was 구현 계획

> 상태: ✅ 구현됨 (2026-07-28, 같은 세션에서 구현·테스트 완료)

**Goal:** "지금 뜨는 인플루언서" 명함 카드 그리드 — 서버 필터·정렬·오프셋 페이지네이션. 상세 리포트(6.5 개편)와 별개 트랙.

**Architecture:** 전부 was 조회 레이어(JdbcClient) — 6.1 `V1ContentRepository` 관용구(fromJoins/where 공유 빌더 + count 분리, beauty_taxonomy 중분류 확장, `/img/` COALESCE 이미지). 신규 마이그레이션·뷰 없음. 모수 = `account_summaries` 보유 계정(최근 12창 분석 계정).

**확정 결정 (2026-07-28):**
1. **effectiveFollowers** — 리포트 개편(07-27 계획)에서 확정한 휴리스틱 `followers × min(1, 계정 ER / 피어 중앙값 ER)`(피어 = 주 카테고리 × 팔로워 버킷, n<3이면 전체 중앙값 폴백)을 **같은 산식으로 쿼리 인라인 CTE 계산**. V39 뷰(미머지 redesign 브랜치)에 의존하지 않되 정의는 동일 — 산출 불가(ER 없음)면 null.
2. **email** — 크롤러 미수집(V31 주석 확정)이라 항상 null, `contact=open`은 0건 매칭. 수집은 crawler 트랙(팀원 담당) 갭으로 보고만.
3. **광고 정본** — `content_analyses.ad_type='sponsored'`(캡션 분류). `account_content_series.sponsored`(raw 플래그)는 쓰지 않는다 — 리포트 개편과 동일 결정.
4. **categoryShares 분모** — 창 내 "뷰티 판정 + 대분류 보유" 게시물 수(합계 100). mainCategory 필터의 20% 임계값도 같은 분모·같은 round 정수로 판정(표시값과 필터 일치).
5. **id = handle** — 6.4 확정("influencerId는 handle 그대로") 준용.
6. **tagline·bio 부재 시 빈 문자열**, avgViews(릴스 없음)·er·effectiveFollowers 산출 불가 시 null.
7. **인증** — 로그인 월 화이트리스트에 `GET /v1/influencers`만 추가(스펙 6.21 Public). `/v1/influencers/{id}`는 잠김 유지.

**파일 맵 (전부 `was/`):**

| 파일 | 작업 |
|---|---|
| `v1/influencer/InfluencerCard.java` | 생성 — 카드 DTO(+CategoryShare·RecentThumb) |
| `v1/influencer/V1InfluencerDiscoveryQuery.java` | 생성 — 파라미터 검증·정규화(6.1 V1ContentQuery 관용구) |
| `v1/influencer/V1InfluencerDiscoveryRepository.java` | 생성 — 본 쿼리(필터·정렬·페이지)+count+보강 3쿼리(shares·brands·thumbs) |
| `v1/influencer/V1InfluencerDiscoveryAssembler.java` | 생성 — 행 조합·스케일·KST 날짜 |
| `v1/influencer/V1InfluencerDiscoveryController.java` | 생성 — GET /v1/influencers, meta {total,limit,offset} |
| `config/SecurityConfig.java` | 수정 — GET /v1/influencers permitAll |
| 테스트 4종 + `LoginWallIntegrationTest` 케이스 추가 | 생성·수정 |

구현 순서: Query(+test) → Repository(+통합 test) → Assembler(+test) → Controller(+test)·Security → ARCHITECTURE §5.
