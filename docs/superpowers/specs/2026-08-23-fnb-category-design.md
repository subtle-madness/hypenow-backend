# F&B 카테고리 추가 설계 — 판정 2축화(뷰티+F&B)

> 상태: 🟢 활성
> 작성: 2026-08-23 · 브레인스토밍 확정안

## 0. 배경·목적

hypenow는 뷰티 인플루언서 단일 카테고리로 운영 중이다. F&B(식품/음료) 카테고리를 추가한다.

- **F&B 타깃**: 식품/음료 **제품** 시딩·협찬 대상 한국어 개인 크리에이터 + 요리·레시피
  크리에이터. 맛집 탐방형 개인도 제품 시딩 가능 모수로 포함. 매장 방문 체험단·F&B 회사는
  발굴 타깃이 아니다(회사·매장은 리스트업 세그먼트로만 분류).
- **복수 카테고리 허용**: 한 계정이 뷰티와 F&B 둘 다일 수 있다(축별 독립 판정).
- **서빙은 당분간 뷰티 유지**: 랭킹·상세·was API의 서빙 모수는 무변경. F&B는 판정·명단까지만
  백엔드로 준비하고, 카테고리 서빙 개편은 별도 트랙으로 미룬다.
- **기존 판정분 전체 F&B 재판정(백필)**: NOT_BEAUTY로 버려진 백로그에 숨은 F&B 모수를 확보한다.
  기존 뷰티 판정은 보존한다.
- **수집·시드 편입은 토글, 기본 off**: F&B 수집(Hiker 과금)은 판정 모수·비용을 확인한 뒤 켠다.

발굴(discover)·qualify는 카테고리 무관이라 코드 무변경 — F&B 키워드는 운영에서 등록한다.

## 1. 데이터 모델 — `influencer`에 F&B 축 병렬 컬럼

접근안: **뷰티 구조 병렬 복제 + 코드 일반화** (검토한 대안: 범용 `influencer_category` 테이블 —
카테고리 2개 시점엔 조인 전환 비용이 과함, 카테고리가 3개 이상으로 늘면 그때 이관; F&B 전용
잡 통째 복제 — 프롬프트·파서 이중화라 기각).

crawler Flyway 마이그레이션(UTC 채번) 1건, 컬럼 추가만(expand — 파괴 없음):

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `fnb_class` | text | 공용 5분류(CategoryClass) |
| `fnb` / `fnb_company` | boolean | 파생 boolean (beauty/beauty_company와 대칭) |
| `fnb_judged_at` | timestamptz | F&B 축 판정 시각 |
| `fnb_source` / `fnb_reason` / `fnb_basis` | text | 판정 출처(CLAUDE/MANUAL)·근거·주근거 |
| `fnb_caption_count` | smallint | 판정에 쓴 캡션 수 (추후 rejudge 재료) |

새 enum `CategoryClass { INFLUENCER, COMPANY, SERVICE, FOREIGN_INFLUENCER, NONE }` —
카테고리 중립 이름. 파생 규칙은 BeautyClass와 동일 구조(`fnb = INFLUENCER∨COMPANY`,
`fnb_company = COMPANY`)로 enum이 단일 원천.

**기존 `BeautyClass`와 beauty 축 저장값은 그대로 둔다** — BEAUTY_SERVICE·NOT_BEAUTY 문자열이
운영 DB에 박혀 있어 rename 위험이 통일 이득보다 크다. F&B에서 SERVICE는 식당·카페·베이커리 등
매장 공식 계정, COMPANY는 식품·음료 브랜드 계정에 대응한다.

## 2. 판정 — 프롬프트 1콜에 두 축 동시 판정

- `BeautyJudge` 포트의 `Verdict`에 `fnbClass`/`fnbReason`/`fnbBasis`를 추가한다.
  포트·잡 이름(`BeautyJob`, `JobName.BEAUTY`)은 **유지** — 크론 키·어드민·중지 플래그가 물려
  있어 rename 이득이 없다. 주석으로 "판정 잡(두 카테고리)"임을 명시.
- 프롬프트(`ClaudeCliBeautyJudge.buildPrompt` — claude-api·claude-cli·gemini 3개 어댑터가
  공유하는 단일 원천)를 두 카테고리 판정으로 확장. 출력은 계정당
  `{username, beauty:{class,reason,basis}, fnb:{class,reason,basis}}`.
- F&B 판정 기준(프롬프트): 식품/음료 제품 리뷰·요리/레시피 콘텐츠의 한국어 개인 크리에이터 =
  INFLUENCER, 맛집 탐방형 개인도 INFLUENCER, 매장 공식 계정(식당·카페·베이커리 등) = SERVICE,
  식품·음료 브랜드 = COMPANY. 언어 규칙(한국어 판정·카오모지 등)은 뷰티와 공유 문단으로 1벌만.
- 출력이 계정당 ~2배 → `MAX_TOKENS` 8192→16384 상향(haiku 지원 범위), `JUDGE_CHUNK` 50 유지.
  응답 길이 문제가 실측되면 청크만 낮춘다.

## 3. 신규 유입·백필 — 선정·적용 규칙

- **신규**(`beauty IS NULL`): 선정 무변경, 판정 결과를 **두 축 모두 적용**. 콜 수 증가 없음.
- **백필**(beauty 판정됨 ∧ `fnb IS NULL`): 새 선정 쿼리로 배치 한도의 남는 자리를 채운다
  (신규 우선 — 기존 rejudge와 같은 순서 규칙). 프롬프트는 동일하게 두 축을 판정하지만
  **적용은 F&B 축만** — 기존 뷰티 판정(MANUAL 포함)은 절대 덮지 않는다.
- 기존 beauty rejudge 3종(재료 갱신·캡션 0건)은 무변경. F&B 축 rejudge는 재료
  (`fnb_caption_count`·`fnb_judged_at`)가 쌓이므로 필요 시 대칭 확장(이번 범위 제외).
- 백로그 소화: QUALIFIED 판정완료 전체가 대상 — 새벽 크론 배치 한도로 여러 날에 걸쳐 자연
  소화, 급하면 어드민 수동 트리거로 당긴다(기존 기능).

## 4. 수집·시드 게이트 — 토글, 기본 off

- `app_setting` 키 `fnb.pipeline-enabled`(기본 `false`) — crawler 마이그레이션으로 시드
  (`ON CONFLICT DO NOTHING`, V16 패턴).
- off(기본): collect·reels·similar 선정 쿼리 전부 현행 그대로(뷰티만). F&B는 판정 명단만 쌓인다.
- on: `findCollectTargets`/`findReelsTargets`/SIMILAR 시드 선정에 `OR (fnb ∧ ¬fnb_company)`
  술어가 붙은 확장 쿼리로 전환(Java에서 토글을 읽어 분기). 어드민 예상 비용 카드도 토글 반영.
- 토글 on은 운영 수동 UPDATE(런타임 토글은 수동 허용 컨벤션).

## 5. 어드민·관측

- 명단 필터: beauty_class 필터에 더해 F&B 축 필터(fnb_class별·미판정) 추가.
- 수동 오버라이드(`InfluencerBeautyController`): F&B 축 오버라이드 추가(MANUAL 보호 동일).
- 대시보드 카운터(`StatusService`): F&B 인플루언서 수·F&B 미판정(백필 잔여) 수 추가 —
  백필 진행률이 여기서 보인다.
- `BeautyJob.Summary`에 F&B 카운트 추가(로그·완료 메시지).

## 6. 분석 뷰·서빙 — 사실상 무변경

- 서빙 모수(01/02/20 뷰의 `beauty ∧ ¬beauty_company`)·미러·was API 전부 무변경.
- `v_base_influencer`에 `fnb`, `fnb_company` 노출만 추가(추가는 자유 — ARCHITECTURE §4-5).
  현재 소비자 없음, 추후 카테고리 서빙 개편의 재료.

## 7. 테스트

- `BeautyJobTest`: 두 축 적용(신규) / F&B만 적용·뷰티 보존(백필) / 백필 선정 순서.
- parse 테스트: 양축 JSON·한 축 누락·5분류 외 값 방어(기존 패턴 확장).
- 수집 게이트: 토글 off면 선정 무변경, on이면 F&B 포함 — CollectJob/SimilarJob 테스트에 케이스 추가.
- SQL 하니스: `00_base` 테스트에 fnb 컬럼 노출 확인.

## 8. 배포 순서

한 PR로 가능(expand만·토글 off라 롤링 안전): 마이그레이션(컬럼+토글 시드) → 코드 →
develop → staging → main. 배포 직후부터 새벽 판정 크론이 신규 2축 판정 + 백필을 자동 시작.
수집 편입은 백필 규모·비용 확인 후 토글 on.
