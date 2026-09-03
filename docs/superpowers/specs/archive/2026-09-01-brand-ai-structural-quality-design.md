# 브랜드 AI 어시스턴트 구조적 품질 개선 (2026-09-01)

> 상태: ✅ 구현됨(2026-09-01, 골드셋 13/13 실측)

## 0. 한 줄 요약

개별 오답 패치의 두더지잡기를 끝내기 위해, 업계 리서치(4갈래, §7 출처)가 수렴한 구조를
도입한다: **용어→데이터 축 매핑을 프롬프트 밖 구조로 고정하고, 근사·소표본 경고를 모델
재량이 아니라 서버 강제로 만들고, 프리셋을 검증된 호출 경로로 승격하고, 실패를 자동
발견하는 eval 루프를 세운다.** 순서: ①구조 조각 → ②eval 루프 → ③모델 실험(②의 자로).

## 1. 배경 - 실측 실패와 그 일반 구조

2026-09-01 로컬 실측(ai_chat_logs conv 8):

- **"광고 게시물로 10명"** → 모델이 `keyword="광고"`(캡션 문자 검색)로 조용히 근사.
  1위 게시물 캡션이 "(광고,협찬❌❌❌)" - 광고가 아니라고 명시한 게시물이 광고 랭킹 1위.
- **"어울리는 인플루언서 추천"** → 정성 질문을 도달 배수 1위로 환원, 팔로워 3명·릴스
  1개 계정 추천.
- **릴스 표본 1개짜리 "평균"** → 소표본 극단값이 랭킹 상위 도배, 표본 수는 표에서 탈락.

셋의 공통 패턴 = **조용한 근사(silent approximation)**: 모델은 표현 못 하는 질문을
만나면 거절·확인 대신 가장 가까운 표현으로 티 안 내고 뭉갠다. 답이 그럴듯해서
사용자가 틀린 줄 모르는 것이 최악. 개별 인자·프롬프트 패치는 이 패턴을 못 잡는다 -
표면은 영원히 유한하므로 다음 두더지가 반드시 나온다(사용자 지적, 2026-09-01).

## 2. 리서치가 준 설계 근거 (요지)

- **dbt 2026 벤치마크**: 같은 모델에서 자유 생성 90.0% vs 시맨틱 레이어 98.2%
  (GPT-5.3: 84.1% vs 100%). 핵심은 실패 모드 차이 - 자유 생성은 "그럴듯하지만 틀린
  답을 조용히 반환", 시맨틱 레이어는 "범위 밖이면 명시적 거절". **구조가 모델 티어를
  이긴다.**
- **"Knowing but Not Showing"(arXiv 2605.25284)**: LLM은 모호성을 인지해도 확인 질문을
  거의 안 하며, **컨텍스트가 붙을수록 오히려 덜 묻고 더 자신 있게 틀린다**. "모호하면
  물어봐"를 프롬프트에 넣는 것은 구조적으로 불충분 - 투명화는 시스템 강제여야 한다.
- **Databricks Genie**: 용어를 "BUSINESS DEFINITIONS" 구조 명세로 고정 + 검증 경로
  (Trusted 라벨) 이원화. **Snowflake Cortex Analyst**: Verified Query Repository.
- **Tableau Pulse**: 계산은 결정론 엔진, LLM은 서술만 - 우리 "산수는 서버로"의 확장.
- **flash급 + 자유 SQL은 근거상 기각**: 최상위 모델도 Spider 2.0 21~59%, flash는
  다단계·고자유도에서 실측상 더 취약. 우리 enum 조합형 방향이 업계 정답 편.
- **eval 소규모 레시피**: 골드셋 30~50개 시작, "코드로 되는 건 코드로(툴·인자 채점),
  판단만 judge로", 👍👎는 품질 지표가 아니라 골드셋 발굴 필터. 실측: 이 루프로 8주
  치명 오류 80% 감소 사례.

## 3. 구조 조각 ① - 축 바인딩 인자

### 3-1. sponsorship 필터 인자 (list_posts · search_posts · aggregate_posts)

`sponsorship: enum["sponsored","organic","unknown"]` (BrandSponsorshipClassifier 값
도메인 그대로). resolveWindow에서 PostRef.sponsorship equalsIgnoreCase 필터로 적용 -
세 툴이 같은 판정을 공유한다. FE scope의 sponsorship(화면 필터, 강제)과는 별개 축이며
둘 다 있으면 교집합. list_hashtag_posts는 원천 컬럼이 없어 제외(기존과 동일).

목적: "광고 게시물" 질문에서 모델이 고를 수 있는 경로가 협찬 표기 축 하나뿐이도록
**인터페이스 설계로** 근사 여지를 없앤다(dbt "LLM은 조합만 고르고 실행은 엔진이" 원리).

### 3-2. minSample 인자 (aggregate_posts, groupBy 시)

`minSample: integer` - 그룹의 릴스 표본 수(viewsSampleCount) 하한. 지정 시 미달 그룹을
정렬 전에 제외하고, 제외 수를 `filteredOutBySample`로 페이로드에 명시(조용한 절단 금지).
기본값 없음(0) - "1개짜리 바이럴 발굴"류 질문을 막지 않기 위해 강제하지 않고, 프리셋이
지정한다(§5).

## 4. 구조 조각 ② - 서버 강제 caveat

툴 페이로드에 서버가 **`caveats: string[]` 배열을 강제 삽입**한다. 모델 재량 고지는
지켜지지 않는다는 연구 실증(§2)에 따라, 고지 의무를 데이터 쪽에 싣는다:

- **keyword 사용 시** (search_posts·aggregate_posts): "keyword는 캡션 문자 매칭입니다.
  광고·협찬 여부 판정이 아닙니다 - 협찬 여부는 sponsorship 인자를 쓰세요."
- **소표본 그룹 반환 시** (aggregate groupBy): "반환된 그룹 중 N개는 릴스 표본이
  1개뿐입니다. 순위 해석에 주의하고 각 행에 표본 수를 함께 표기하세요."
- caveats가 없으면 빈 배열이 아니라 필드 생략(토큰 절약).

프롬프트 규칙 신설: **"툴 결과에 caveats가 있으면 그 내용을 반드시 답변에 반영하고
관련 한계를 고지한다."** - 재량("알아서 명시하라")이 아니라 특정 필드에 대한 기계적
의무로 좁힌다.

## 5. 구조 조각 ③ - 용어 사전 (BrandAiGlossary)

Genie "BUSINESS DEFINITIONS" 패턴. 새 클래스 `BrandAiGlossary`가 "[용어 정의]" 섹션을
생성해 시스템 프롬프트에 상시 주입한다(프리셋과 무관하게 전 질문 적용). 산발적 규칙이
아니라 **한 파일에서 관리되는 용어→축 매핑 정본**:

- 광고 게시물 / 협찬 게시물 / 스폰서드 = sponsorship 축의 "sponsored"(협찬 표기 판정).
  캡션에 '광고'라는 글자가 있는 것과 무관하다 - keyword로 세거나 거르지 않는다.
- 오가닉 = sponsorship "organic".
- 참여율 = engagementRate(서버 계산값). 도달 배수 = reachMultiple(서버 계산값).
  직접 계산하지 않는다.
- 조회수 = 릴스만(피드는 항상 null).
- 반응이 좋다 / 인기 = 릴스 조회수 기준을 우선하고, 참여율을 병기할 수 있다.
- 인플루언서·작성자 랭킹 = aggregate_posts(groupBy=author) 1회. get_author 반복 금지.
- 랭킹·비교 표에는 각 행의 표본 수(viewsSampleCount 또는 postCount)를 반드시 포함한다.
- **정의에 없는 용어를 데이터 축에 대응시켜야 할 때는, 어떤 축으로 근사했는지 답변에
  명시한다.**

기존 프롬프트 규칙 중 이 사전과 중복되는 조각(규칙 12~14의 산식 언급 등)은 사전으로
이관해 이중 정본을 만들지 않는다.

## 6. 구조 조각 ④ - 프리셋 verified 플랜 (선실행 주입)

Genie Trusted Assets 패턴의 우리식 적용. 프리셋에 **검증된 호출 플랜**(사전 정의된 툴
호출 시퀀스)을 부여하고, 에이전트 루프 시작 전에 서버가 그 플랜을 **선실행해 결과를
대화에 주입**한다(functionCall+functionResponse 쌍으로). 이후 루프는 기존과 동일 -
모델은 주입된 결과 위에서 답하되 필요하면 추가 조회도 가능하다.

- 핵심 수치가 검증된 호출에서 나오므로 프리셋 질문은 툴 선택·인자 조합을 틀릴 수 없다.
- 플랜 부여 대상(1차): efficient_influencers = aggregate_posts(groupBy=author,
  orderBy=reachMultiple, limit=10, **minSample=2**) / sponsored_vs_organic =
  aggregate_posts(groupBy=sponsorship) / top_posts = list_posts(sort=performance_desc).
  tagged_posts_analysis·paid_amplify는 지시문 유지(정성 비중이 커서).
- brandId는 플랜에 하드코딩하지 않고 실행 시 세션 brandId로 치환.
- 플랜 실행 실패 시 기존 자유 경로로 폴백(주입 없이 지시문만).
- 선실행 호출도 tool_calls 로그·툴 회수에 포함(관측 일관성).
- **08-31 "B(프리셋 전용 서버 조립 경로) 기각"과의 관계**: B는 툴 콜링 자체를 우회하는
  별도 경로라 기각했고 그 판단은 유지한다. 이 설계는 루프를 유지한 채 첫 호출만
  고정하는 것으로, 코드 경로가 갈라지지 않는다. 리서치(검증 경로 이원화가 업계 공통
  패턴)가 재검토의 근거다.
- 답변에 "검증된 경로" 라벨을 붙이는 FE 노출(Genie Trusted 라벨)은 FE 협의 후속.

## 7. eval 루프 (1단계 범위)

### 7-1. 골드셋 - `was/eval/goldset.json`

케이스 12~15개로 시작(리서치 권고 30~50의 하한 아래지만 실측 사고 전 부류 포함):
실측 사고 3건(광고 10명·어울리는 추천·소표본 랭킹) + 기존 검증 시나리오(10명·30명·
기간 비교·캡션 카운트·협찬 vs 오가닉·댓글 여론·N>limit).

케이스 스키마:

```json
{
  "id": "ad-posts-top10",
  "question": "광고 게시물로 10명 정리해줘",
  "presetId": null,
  "expectTools": [{"name": "aggregate_posts", "argsInclude": {"groupBy": "author", "sponsorship": "sponsored"}}],
  "forbidTools": [{"name": "aggregate_posts", "argsInclude": {"keyword": "광고"}}],
  "expectAnswerContains": [],        // SQL 정답 수치(러너가 실행해 치환) 또는 고정 문자열
  "groundTruthSql": "SELECT count(DISTINCT ...) ..."   // 선택 - 수치 검증용
}
```

### 7-2. 러너 - `was/eval/run.sh`

analytics/test/run.sh 하니스 컨벤션을 따른다. 로컬 was(8081)+로컬 DB 전제.

- 케이스별: POST /v1/brand-monitoring/ai/messages(완결 JSON 경로) → ai_chat_logs 최신
  행 조회(psql, `PG_CONTAINER` 오버라이드 지원) → 채점.
- 채점은 **전부 결정론**(1단계): (a) expectTools 부분 매치, (b) forbidTools 불일치,
  (c) groundTruthSql 실행값이 답변 텍스트에 포함(콤마 포맷 변형 허용, 허용오차는
  정수 카운트라 불요). LLM judge는 2단계(범위 밖).
- 시작 시 app_setting의 일일·분당 한도를 임시 상향하고 종료 시 복원(로컬 전용).
- 산출: 케이스별 PASS/FAIL 표 + 실패 상세(기대 vs 실제 툴 호출).
- 모델 실험(③)은 이 러너를 BRAND_AI_MODEL 바꿔 재실행하는 것으로 수행한다.

### 7-3. 범위 밖 (후속으로 명시)

온라인 judge 샘플링(프로덕션 로그 상시 채점)·👎 트리거 자동화·모호성 분류 파이프라인
(AmbiSQL류)은 운영 배포 후 후속. 👎 필드는 이미 계약에 있으므로 수집만 계속.

## 8. 구현 순서·주의

1. §3(인자) → §4(caveat) → §5(사전) → §6(프리셋 플랜) → §7(eval) 순. 각 단계 테스트
   동반(TDD), 기존 통합 테스트 회귀 확인.
2. 브랜치: feat/brand-ai-tool-limits-redesign 위에 계속(미머지 상태라 같은 트랙).
3. 검증 완료 기준: 러너 1회 완주 + 실측 사고 3건 케이스 PASS.
4. push까지만, PR 금지(사용자 지시).

## 9. 리서치 출처 (요지별 대표)

- dbt, "Semantic Layer vs. Text-to-SQL: 2026 Benchmark Update"
- Anthropic Engineering, "Writing effective tools for AI agents" / "Code execution with MCP"
- Databricks Genie trusted assets 문서·실전 패턴(SKILL.md), Snowflake Cortex Analyst
  구조(semantic model·VQR·classification agent)
- arXiv 2605.25284 "Knowing but Not Showing", arXiv 2508.15276 AmbiSQL,
  arXiv 2501.10858 Adaptive Abstention, TrustSQL(2403.15879)
- Honeycomb "All the Hard Stuff Nobody Talks About…" 및 후속기
- LinkedIn SQL Bot·Uber QueryGPT·Pinterest 엔지니어링 블로그
- Arize(judge 신뢰도 검증 절차), LangSmith(온라인 eval 샘플링), Braintrust(로그→골드셋
  워크플로), Nordnet 분석 에이전트 검증기 실전기
