# 해시태그 직접 수집 전환 설계 (감지 구조 폐기)

> 상태: 🟢 활성 · 2026-08-27 설계 확정(사용자 승인), 구현 전

## 0. 배경과 결정 요약

해시태그 파이프라인은 지금까지 "감지"까지만 했다 — 별도 테이블(`brand_hashtag_post`)에 발견
시점 관측값을 1회 저장하고 LLM 관련성 판정으로 노출을 걸렀을 뿐, 스냅샷·댓글·게시자 보강과
주기 재수집이 없었다. 이를 폐기하고 **해시태그 게시물을 tagged/direct와 같은 수집 풀에 직접
편입**한다.

2026-08-27 확정된 결정:

| 결정 | 내용 |
|---|---|
| 기존 스윕·감지 구조 | **완전 폐기** (LLM 관련성 판정 파이프라인 포함) |
| 편입 게이트 | **전부 편입** — 판정 없이 해시태그 매칭 게시물 전부 수집. 단 **브랜드 본인 계정 게시물은 규칙 기반 제외**(게시자 username = 브랜드 계정명) |
| 수집 기간 | 브랜드 계정의 collectionMonths **그대로 적용** (기존 90일 고정 윈도우 폐기) |
| 상한 | **브랜드당 1000개** (hashtag 소스 총량, tagged의 2000 상한과 별도 카운터) |
| FE 노출 | 기존 `/posts` 통합 목록에 **source=hashtag로 합류** |
| 기존 감지 데이터 | 새 풀로 **이관(승격)** |
| 사용자 격리 | **내 태그 매칭만 노출** (08-19 격리 정책 유지) |
| 구 `/hashtag-posts` | 전환 기간 동안 **새 풀로 리라우팅**, FE 전환 확인 후 다음 릴리스에 제거 |
| 스키마 접근 | **접근 A** — `brand_tagged_post` 풀에 흡수 (direct 합류의 `direct_registered_at` 패턴 동형) |

## 1. 스키마 (monitoring DB)

- `brand_tagged_post`에 `hashtag_detected_at timestamptz`(nullable) 추가. 기존
  `tag_detected_at`/`direct_registered_at`과 함께 세 개의 nullable 타임스탬프 조합으로
  tagged/direct/hashtag 성분과 겹침을 행 하나로 표현한다
  (`V20260818040742__brand_tagged_post_direct_source.sql` 패턴 동형).
- 매칭 태그 테이블 `brand_post_matched_tag(brand_id, short_code, tag)` 신설 — "이 게시물이
  어떤 해시태그로 잡혔나". 사용자 격리 필터의 재료. 스윕이 같은 게시물을 다른 태그로 재발견하면
  행이 누적된다(멱등 upsert).
- `brand_hashtag`(수집 대상 태그, 브랜드 스코프)는 그대로 유지 — "무엇을 수집할지"의 정본.
- 기존 `brand_hashtag_post`·`brand_hashtag_post_matched_tags`는 이번 릴리스에서 읽기/쓰기
  중단만, DROP은 참조 코드가 끊긴 **다음 릴리스**에서 (expand-contract).

## 2. 수집 파이프라인 (monitoring)

일일 스윕(`BrandSweepJob`) 3단계 골격(①tagged ②direct ③hashtag) 유지. ③을 "감지"에서
"수집"으로 교체:

1. **열거**: 태그별 Hiker `/v2/hashtag/medias/recent` — 페이지 내 기존 저장 게시물(dedup) 도달
   시 중단하는 현행 증분 열거 전략 유지(recent 스트림은 taken_at 비단조라 기간 컷으로는 중단
   판정 불가).
2. **필터**: 브랜드 `collectionCutoff`(collectionMonths)로 taken_at 사후 필터. 기존
   windowDays=90 고정은 폐기. **브랜드 본인 계정 게시물 제외**(게시자 username = 브랜드
   계정명, 규칙 기반 — LLM 불요).
3. **편입**: `brand_tagged_post` upsert — 신규 행이면 `hashtag_detected_at` 채워 삽입, 기존
   tagged/direct 행이면 `hashtag_detected_at`만 병기. `brand_post_matched_tag` 기록.
   **상한**: `hashtag_detected_at IS NOT NULL` 행 수가 브랜드당 1000이면 신규 편입 중단
   (최신 우선). 겹침 병기는 상한과 무관(행이 늘지 않음).
4. **보강**: 기존 `BrandCollectService.enrich`(게시자 프로필·댓글·광고 판정·스냅샷) 코드 재사용.
5. **재수집**: hashtag-only 행은 tagged 열거로 재발견되지 않으므로, direct의 단건 재수집
   경로(`sweepDirect`)를 "tagged 열거로 커버되지 않는 행(direct 또는 hashtag 성분)" 공용으로
   일반화해 같은 나이 티어 정책(`BrandCrawlPolicy.due`)을 적용한다.

- 태그 등록(PUT/POST) 직후·브랜드 등록 직후의 **즉시 스윕 트리거는 유지** — 새 수집 경로로
  연결. 전용 executor 분리(#506) 유지.
- LLM 관련성 판정(verdict) 파이프라인·관련 코드 제거.

## 3. was 노출 (/posts 통합)

- `source` 3원화: `resolveSource` 우선순위 **direct(등록자 관점) > tagged > hashtag**.
  `meta.counts`에 `hashtag` 추가. `source=hashtag` 필터 지원.
- **사용자 격리**: hashtag-only 행만 "조회자의 장부 태그(`app.brand_hashtag_tags`) ∩ 매칭
  태그(`brand_post_matched_tag`) ≠ ∅"일 때 노출. tagged/direct 성분이 있는 행은 기존 규칙
  그대로(브랜드 공유 / direct-only는 등록자 한정). 조합은 was 코드에서(물리 분리 DB — 조인
  불가, 시스템 경계 §4-3).
- 현행 감지 목록의 fail-open(장부 비면 전부 노출)은 폐기 — §4의 시딩·백필로 모든 사용자
  장부에 최소 자동 태그가 생겨 필요 없어진다.
- **서빙 창**: tagged와 동일 규칙 — 수집(자산)은 브랜드 창(유저 간 max), 서빙은 각 사용자의
  신청 collectionMonths 창으로 잘라 노출(`linkWindowStart` 재사용). 태그 등록 시점은 노출
  판정에 쓰지 않는다(시간축은 게시물 업로드 시각 — 나중에 태그를 등록한 사용자도 자기 창 내
  기수집분을 그대로 본다, 2026-08-27 확정).
- 구 `GET /accounts/{id}/hashtag-posts`: 응답 형태 유지한 채 통합 풀에서 서빙(리라우팅) —
  FE 전환 전에도 화면이 낡지 않는다. FE 전환 확인 후 다음 릴리스에 제거.

## 4. 태그 장부 갭 수정 (전제 조건 — 같은 트랙에서 선행)

격리 필터가 사용자 장부에 의존하므로, 08-27 진단된 "자동 등록 태그가 장부에 기록되지 않는"
갭 수정이 이 설계의 전제 조건이다. 승인된 내용:

- **링크 생성 시 시딩**: was가 신규 브랜드 링크 생성 직후 그 사용자의 장부에 **계정명 유도
  태그 1종만** 기록. monitoring `BrandHashtagTags.derive`와 같은 규칙을 was에 복제(기존
  normalizeTag 복제 관용구 동형). 멱등 재-POST 경로에서는 시딩하지 않는다(지운 태그 부활 방지).
  (설계 초안의 "own이면 브랜드명 유도 태그"는 계획 수립 중 기각 — monitoring은 08-17 축소
  이후 brandName을 시드하지 않으므로, was 장부에만 심으면 스윕 안 되는 태그가 노출되고 다음
  PUT 합집합이 그 태그를 monitoring에 되밀어 08-17 결정을 되돌린다.)
- **백필**: 기존 활성 링크 전원의 장부에 계정명 유도 태그를 멱등 삽입 (was `db/migration/app`,
  UTC 채번).
- **승계 규칙 변경**: `ensureSeeded`의 "장부가 완전히 비었으면 monitoring 태그 전체 승계"를
  "**아무 사용자에게도 귀속되지 않은 태그만** 조작 사용자에게 승계"(diff 기반)로 변경 — 구
  규칙은 백필 후 영영 발동하지 않아 격리 개정 이전의 무주 태그가 영구히 무주(어느 GET에도
  안 보이고 관리 불가)로 남는다. diff 조건은 08-19 "최초 조작자 승계" 정책을 백필과 공존
  가능한 형태로 보존한다. (구현 중 교정: 무주 태그가 PUT 전체 교체에서 monitoring으로부터
  사라질 수 있는 것은 승계 방식과 무관한 08-19 계약 자체의 성질 — 승계된 태그가 진행 중인
  PUT·전체 삭제의 대상이 되는 것은 구 전량 승계와 동형이며 의도된 동작이다.)

## 5. 기존 데이터 이관

monitoring Flyway 마이그레이션 1개(UTC 채번):

- `brand_hashtag_post` → `brand_tagged_post` upsert. 신규 행은 발견 시각을
  `hashtag_detected_at`으로, 겹침 행(이미 tagged/direct)은 `hashtag_detected_at` 병기만.
- verdict 무관 전량 이관하되 **SELF(브랜드 본인) 판정분은 제외**(§0 본인 게시물 제외 결정과
  정합). 브랜드당 최신순 1000 상한 적용.
- `brand_hashtag_post_matched_tags` → `brand_post_matched_tag` 이관.
- 이관분 보강: 게시자·댓글·스냅샷이 비어 있으므로, 스윕에 **미보강 행 우선 보강 배치**(스윕당
  건수 제한)를 둬 점진 충전 — 나이 기반 due만으로는 오래된 이관분의 첫 보강이 늦다.

## 6. 비용·리스크

- 편입 게이트가 없어 브랜드당 최대 1000건 × (게시자·댓글 콜) — 이관 직후 첫 스윕이 피크.
  기존 "전역 동시 콜 14" 예산과 executor 분리 안에서 스로틀(스윕당 보강 상한).
- 동명이인·무관 게시물 노이즈가 판정 제거로 화면에 그대로 노출된다 — 제품 결정(전부 편입)에
  따른 수용. 문제가 되면 노출 단계 필터를 후속으로 재도입할 수 있는 구조(매칭 태그·게시자
  메타 보존)로 남긴다.
- FE 계약 변경(통합 목록 source=hashtag, counts.hashtag, 구 탭 폐기 예정) — 프론트 협의·통지
  필요.
- 구 감지 테이블 DROP은 다음 릴리스(expand-contract, migration-guard 규칙 준수).

## 7. 테스트

- monitoring: 새 ③단계 수집 서비스 단위·통합(Testcontainers) — 기간 컷, 1000 상한, 본인 제외,
  겹침 병기, 매칭 태그 누적, 이관분 우선 보강.
- was: resolveSource 3원화, 격리 필터(교집합 규칙·fail-open 폐기), counts.hashtag, 구
  엔드포인트 리라우팅 등가성.
- 장부 갭: 링크 생성 시딩, 재-POST 비부활, diff 승계, 백필 멱등.
- SQL: 이관 마이그레이션 — 겹침·상한·SELF 제외 케이스.
