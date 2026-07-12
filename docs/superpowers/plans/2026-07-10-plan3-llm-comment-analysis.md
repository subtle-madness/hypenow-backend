# Plan 3: LLM 파이프라인 1 — 댓글 분석 Implementation Plan

> 상태: 🟢 활성 — 태스크 F·B2 참고 자료. 단 enrichment 모듈 소속·미러 방식은 ARCHITECTURE §4가 우선
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 게시물별 댓글(최대 50개)을 한 번의 structured-output LLM 호출로 감성 4분류(POSITIVE/NEUTRAL/NEGATIVE/IRRELEVANT)·구매의도·반응 키워드로 분류해 crawler DB에 저장하고, analytics 뷰로 집계해 드로어 `commentAnalysis` 블록을 was 응답에 additive하게 붙인다.

**Architecture:** crawler 모듈 안에 신규 bounded context `enrichment`를 추가한다(별도 Gradle 모듈 아님). 파이프라인은 `raw_comment`(AGGREGATE 산출물)를 입력으로 받아 → **정규식 프리필터로 명백한 스팸을 IRRELEVANT로 선(先)확정** → 나머지를 Anthropic Java SDK의 structured outputs로 게시물 단위 배치 분류 → `comment_analysis` 테이블에 저장한다. 저장 결과를 `analytics.v_post_comment_analysis` 뷰가 게시물 단위로 집계하고 `MaterializationService`가 analysis DB로 미러한다. was는 Plan 1의 `GET /api/posts/{shortCode}` 응답에 `commentAnalysis` 필드를 additive하게 추가한다. 게시물 1건 = 트랜잭션 1개로 처리해 부분 성공을 보존하고, `(comment_id, model)` 존재 검사로 멱등성을 보장한다.

**Tech Stack:** Java 21 / Spring Boot 4.1, JPA + Flyway(Postgres 17), Anthropic Java SDK `com.anthropic:anthropic-java:2.48.0`(GA structured outputs), Testcontainers(`postgres:17-alpine`), analytics SQL 테스트 하니스(`analytics/test/run.sh`), was JdbcClient + Jackson 3(`tools.jackson.*`).

**사전 조건 / 의존성:**
- **Plan 1이 먼저 병합돼 있어야 한다** (Task 9 was 부분 한정). Plan 1이 만든 `was/src/main/java/com/celfit/was/postdetail/{PostDetailRow,PostDetailRepository,PostDetailAssembler,PostDetailResponse,PostDetailController}`, `config/{ClockConfig,WebConfig}`, `was/src/test/java/com/celfit/was/IntegrationTest.java`, was `build.gradle`의 testcontainers 3종을 **수정/재사용**한다. Task 0~8(스파이크·crawler·analytics)은 Plan 1과 독립이다.
- 로컬 Docker에 `crawler-postgres-1` 컨테이너 기동(`docker compose up -d`, 포트 5433, DB `crawler`/`analysis`).
- `crawler/.env`에 `ANTHROPIC_API_KEY=sk-ant-...` 추가 + 실행 프로세스 env에 주입(아래 "환경변수 주의" 참조).

**환경변수 주의 (조사 근거):** 이 리포에는 `.env`를 JVM 프로세스 env로 자동 로드하는 코드가 없다(`spring.config.import` 없음, IDE envFile 없음, gradle env 플러그인 없음). `APIFY_TOKEN`과 **똑같이** 개발자가 셸 export(`export ANTHROPIC_API_KEY=...`) 또는 IDE EnvFile로 프로세스 env에 미리 세팅해야 `application.yml`의 `${ANTHROPIC_API_KEY:}` 플레이스홀더가 값을 읽는다. `.env`에 적기만 하면 자동 주입될 거라 가정하면 안 된다.

**핵심 설계 판단 (근거는 각 Task + Self-Review 참조):**
1. **`keywords`는 `jsonb`로 저장** (로드맵의 `text[]` 문구 대신). 근거: (a) 이 코드베이스에 `text[]`↔Java 매핑 사례가 전무한 반면 jsonb↔컬렉션 매핑은 `RawComment.payload`(`@JdbcTypeCode(SqlTypes.JSON)`)로 이미 확립됨. (b) 집계 뷰가 게시물 단위 칩(jsonb)만 미러하므로 저장 배열 형식은 미러 DDL에 노출되지 않음. (c) 칩 집계 SQL은 `06_hashtags_comments.sql`의 `jsonb_array_elements_text` 관용구를 그대로 재사용.
2. **`comment_id` 단독 PK + `prompt_version` 컬럼**(로드맵의 `comment_id` PK 준수, 조사 §7의 버전 멱등키 요구를 MVP 범위에서 최소 반영) — 한 댓글 = 한 행. 멱등 키는 `(comment_id, model, prompt_version)` 존재 검사. **`prompt_version`은 system 프롬프트 + 통제 키워드 사전을 함께 커버하는 단일 버전 라벨**(조사 §7의 `keyword_dict_version`을 folding — 사전을 바꿔도 이 값을 올려야 함). 이로써 **프롬프트/사전을 바꾸면 같은 model이라도 멱등 검사가 miss → 재분석이 트리거**되어 낡은 결과가 새 프롬프트 산출물과 조용히 섞이는 문제(조사 §7 함정)를 해소한다. **트레이드오프(MVP 한계, Self-Review에 명시):** PK가 `comment_id` 단독이라 model/prompt_version이 바뀌면 merge가 **기존 행을 덮어써** A/B·롤백·모델별 재집계는 불가하다(조사 §7의 non-overwrite 권고는 MVP에서 따르지 않음). 과거 산출을 보존하려면 후속에 PK를 `(comment_id, model, prompt_version)` 복합키로 승격하고 뷰가 '현재 버전'만 집계하도록 확장해야 한다. row별 `model`/`prompt_version`은 crawler DB에 남으므로 감사·수동 무효화(delete 후 재분석)는 가능하다.
3. **동기 per-post `messages().create()`** 경로 사용(Message Batches API 아님). 게시물 1건 = `@Transactional` 1개로 HTTP+저장을 감싸 per-post 원자성 확보, EnrichmentJob이 게시물 사이를 이어가며 부분 성공 보존. Batches(50% 할인)는 Task 0 결과에 따른 후속 최적화로 유보. **비용 함의(중요):** 출하 경로는 동기·**프롬프트 캐시 미적용**(systemPrompt에 `cache_control` 블록 없음)·**Batches 미사용**이므로 실비는 캐시·배치 할인이 전혀 없는 **Base**다. 기본 모델이 Opus(판단 4)이므로 출하 기본값 실비 ≈ **$61/1k** 게시물, 스파이크 통과 후 haiku로 내리면 ≈ **$12.2/1k**. 조사 §5가 인용하는 **$6.1/1k는 Batches(−50%) 도입 후에야 도달 가능한 후속 목표치**이며 현재 코드 경로로는 도달 불가(판단 3의 Batches 유보를 철회하고 어댑터를 Batches 경로로 재설계해야 함). 캐시 절감도 `cache_control`이 없어 현재 0.
4. **모델은 프로퍼티**(`crawler.enrichment.model`, 기본 `claude-opus-4-8`). haiku 다운그레이드는 yml/env 한 줄 변경. 하드코딩 금지. **주의:** 코드 기본값이 Opus라 아무 조정 없이 켜면 실비는 위 판단 3의 $61/1k 기준이다.
5. **비용 안전장치:** `enrichment.batch-limit`(1회 처리 게시물 상한, **500 초과는 설정 검증에서 거부** — 오설정 백스톱) + `enrichment.max-comments-per-call`(호출당 댓글 상한, 기본 50) + `enrichment.dry-run`(프리필터만 돌리고 LLM 호출·저장 생략, **스케줄 활성 전 필수 선행 점검**). 일일 호출 상한은 영속 카운터가 필요해 MVP 유보(수동 트리거 + batch-limit + batch-limit 상한 검증 + dry-run으로 지출 상한 확보). 스케줄 활성(`schedule.enabled=true`) 시 부팅 로그에 경고를 남긴다.

**목업 볼륨 정합(중요 — 50 상한과 목업 214의 모순 해소):** 파이프라인은 이중 상한(`aggregate.comments-per-post=50` + `enrichment.max-comments-per-call=50`)으로 **게시물당 `analyzedCount` ≤ 50**을 강제한다. 따라서 목업의 '214개 분석 / 발림성 41 / 27개(12.6%)'는 **50 상한 확정과 구조적으로 양립 불가**하다(214는 pre-cap 시절 예시). **확정안이 '현행 50 유지'를 못박았으므로 목업 수치를 '최대 50개 분석' 기준으로 정정**하고(드로어 카피도 "최근 최대 50개 댓글 분석"으로), 모든 테스트 픽스처(특히 was 쪽 Task 9)의 `analyzedCount`·칩 count·구매의도 비율을 **≤50 정합 값**으로 맞춘다. 더 큰 볼륨이 제품상 꼭 필요하면 두 상한을 함께 상향하고 비용(판단 3 재계산)을 별도 승인받아야 하며, 그 결정 전에는 214를 응답/픽스처 어디에도 노출하지 않는다.

**참고 파일 (컨벤션 출처):**
- 배치·청킹·재시도 골격: [crawling/application/service/AggregateJob.java](../../crawler/src/main/java/com/celfit/crawler/crawling/application/service/AggregateJob.java)
- 런타임 설정: [settings/application/service/SettingsService.java](../../crawler/src/main/java/com/celfit/crawler/settings/application/service/SettingsService.java)
- jsonb+generated 엔티티: [crawling/domain/RawComment.java](../../crawler/src/main/java/com/celfit/crawler/crawling/domain/RawComment.java)
- 빈·프로퍼티 등록: [common/config/CrawlerConfig.java](../../crawler/src/main/java/com/celfit/crawler/common/config/CrawlerConfig.java)
- fail-fast 자격증명: [crawling/adapter/out/apify/JdkApifyHttp.java](../../crawler/src/main/java/com/celfit/crawler/crawling/adapter/out/apify/JdkApifyHttp.java)
- 잡 트리거·락: [crawling/application/service/JobService.java](../../crawler/src/main/java/com/celfit/crawler/crawling/application/service/JobService.java), [JobLock.java](../../crawler/src/main/java/com/celfit/crawler/crawling/application/service/JobLock.java)
- 통합테스트·fake: [crawler/.../IntegrationTest.java](../../crawler/src/test/java/com/celfit/crawler/IntegrationTest.java), [AggregateJobTest.java](../../crawler/src/test/java/com/celfit/crawler/crawling/application/service/AggregateJobTest.java), [FakeApifyRunner.java](../../crawler/src/test/java/com/celfit/crawler/FakeApifyRunner.java), [JobApiTest.java](../../crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/JobApiTest.java)
- 뷰·SQL 테스트: [analytics/views/06_hashtags_comments.sql](../../analytics/views/06_hashtags_comments.sql), [analytics/test/06_hashtags_comments.test.sql](../../analytics/test/06_hashtags_comments.test.sql), [analytics/seed/dummy.sql](../../analytics/seed/dummy.sql)
- 미러: [MaterializationService.java](../../analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java)

---

### Task 0: 정확도·비용 스파이크 (모델 선택 게이트) — 코드 착수 전 필수

이 Task는 **측정**이라 red→green TDD 대신 "각 Step = 관측 가능한 산출물"로 구성한다. **스크래치 스크립트는 리포에 커밋하지 않는다**(scratchpad에 둔다). **결과 마크다운만 커밋**한다. 이 결과가 이후 모든 Task의 기본 모델(`crawler.enrichment.model`)을 결정한다.

**Files:**
- Create (커밋 안 함, scratchpad): `<scratchpad>/spike/gold.jsonl`, `<scratchpad>/spike/classify_spike.py`, `<scratchpad>/spike/score.py`
- Create (커밋함): `docs/superpowers/spikes/2026-07-10-comment-classification-spike.md`

- [ ] **Step 1: 골드셋 구축 (계층 추출 + 2인 라벨링) — 게이트로 쓸 만큼 표본을 확보**

crawler DB의 실 `raw_comment`에서 **여러 게시물·브랜드에 걸쳐** 계층 추출한다. **표본 수는 하드 게이트를 통계적으로 분해할 수 있어야 한다**: n=20에서 recall 0.90을 재면 Wilson 95% CI가 대략 [0.70, 0.97]로 **0.90과 0.70을 구분 못 한다**(직접 계산). 따라서 게이트 대상 클래스는 **각 ≥50 양성 표본**을 강제 확보한다 — 구체적으로 **IRRELEVANT ≥50, purchase_intent 양성 ≥50, 나머지 감성 클래스(POSITIVE/NEUTRAL/NEGATIVE) 각 ≥30**. 자연분포가 IRRELEVANT/POSITIVE에 편중하고 purchase_intent가 희소하므로 계층 오버샘플로 이 최소치를 맞춘다(총 ~250~300건). 키워드는 28어휘 중 다수가 희소하므로 속성별 F1은 **표본이 충분한 상위 어휘에 한해** 보고하고 micro-F1을 주지표로 쓴다. 아래로 후보를 뽑는다:

```bash
docker exec -i crawler-postgres-1 psql -U crawler -d crawler -tAc \
  "SELECT rc.id, rc.content_id, replace(rc.text, E'\n', ' ')
   FROM raw_comment rc
   WHERE rc.text IS NOT NULL AND length(trim(rc.text)) > 0
   ORDER BY random() LIMIT 400" > "$SPIKE/candidates.tsv"
```

라벨러 2명이 독립 라벨 후 조정. `<scratchpad>/spike/gold.jsonl` 형식(한 줄 = 한 댓글):

```json
{"id": 12345, "text": "발림성 최고 근데 가격이…", "category": "POSITIVE", "purchase_intent": false, "keywords": ["발림성","가격"]}
{"id": 12346, "text": "맞팔해요 놀러오세요", "category": "IRRELEVANT", "purchase_intent": false, "keywords": []}
```

- [ ] **Step 2: 라벨러 간 일치도(Cohen's κ) 측정 → 합격 밴드 앵커 확정**

`category`에 대해 2인 라벨의 Cohen's κ를 계산한다(사람끼리 흔들리면 그게 모델 상한). κ를 pass 기준의 앵커로 기록한다: **모델 성능이 사람-사람 κ 밴드 안이면 합격**(모델에 사람 상한 초과를 요구하지 않음).

- [ ] **Step 3: 스파이크 분류 스크립트 작성 (opus vs haiku, 동일 프롬프트·스키마)**

`<scratchpad>/spike/classify_spike.py` — Task 6에서 쓸 것과 **동일한** system 프롬프트/JSON 스키마/필드 순서(`category`→`purchase_intent`→`keywords`, IRRELEVANT를 enum 첫 값)를 사용한다. **프로덕션과 동일하게 50개 단위로 청킹해 여러 번 호출하고 결과를 합산한다**(골드셋 전체를 1회 호출로 보내면 max_tokens=4096를 넘겨 structured output이 중간에서 잘리고 `json.loads`가 죽거나 결과가 조용히 유실된다 — 137건이면 이미 초과). 청킹은 system 프롬프트 상각·문맥 길이도 프로덕션(게시물당 50 배치)과 정합시킨다. 두 모델(`claude-opus-4-8`, `claude-haiku-4-5`)을 같은 골드셋에 돌리고 청크별 `count_tokens`로 실측 토큰을 합산한다. **`stop_reason == "max_tokens"`면 잘림으로 판정해 즉시 실패**시킨다.

```python
import os, json, sys
import anthropic  # pip install anthropic (스크래치 전용, 리포 의존성 아님)

MODEL = sys.argv[1]  # "claude-opus-4-8" | "claude-haiku-4-5"
CHUNK = 50  # 프로덕션 max-comments-per-call과 동일
client = anthropic.Anthropic(api_key=os.environ["ANTHROPIC_API_KEY"])

SYSTEM = open(os.path.join(os.path.dirname(__file__), "system_prompt.txt"), encoding="utf-8").read()
SCHEMA = {
  "type": "object", "additionalProperties": False, "required": ["results"],
  "properties": {"results": {"type": "array", "items": {
    "type": "object", "additionalProperties": False,
    "required": ["id","category","purchase_intent","keywords"],
    "properties": {
      "id": {"type": "integer"},
      "category": {"type": "string", "enum": ["IRRELEVANT","POSITIVE","NEUTRAL","NEGATIVE"]},
      "purchase_intent": {"type": "boolean"},
      "keywords": {"type": "array", "items": {"type": "string", "enum": [
        "발림성","제형","흡수력","밀착력","지속력","커버력","발색","보습","유분감","건조함","자극","트러블",
        "향","가격","가성비","색상","톤","퍼스널컬러","용기","용량","미백","주름개선","진정","각질",
        "건성","지성","복합성","민감성"]}}}}}}}

gold = [json.loads(l) for l in open(os.path.join(os.path.dirname(__file__), "gold.jsonl"), encoding="utf-8")]

def chunks(xs, n):
    for i in range(0, len(xs), n):
        yield xs[i:i+n]

all_results, in_tok, out_tok = [], 0, 0
for batch in chunks(gold, CHUNK):
    user = "아래 각 댓글을 분류하라. [] 안 숫자가 id다. id를 그대로 반향하라.\n" + \
           "\n".join(f"[{g['id']}] {g['text']}" for g in batch)
    tok = client.messages.count_tokens(model=MODEL, system=SYSTEM,
            messages=[{"role":"user","content":user}])
    resp = client.messages.create(model=MODEL, max_tokens=4096, system=SYSTEM,
            messages=[{"role":"user","content":user}],
            output_config={"format":{"type":"json_schema","schema":SCHEMA}})
    if resp.stop_reason == "max_tokens":   # 잘림 = 실패(청크를 더 줄이거나 스키마 축소)
        sys.exit(f"FAIL: chunk truncated (stop_reason=max_tokens, n={len(batch)})")
    text = "".join(b.text for b in resp.content if b.type == "text")
    out = json.loads(text)   # 잘리지 않았으므로 완전한 JSON
    all_results.extend(out["results"])
    in_tok += tok.input_tokens
    out_tok += resp.usage.output_tokens

json.dump({"model":MODEL, "input_tokens":in_tok, "output_tokens":out_tok, "results":all_results},
          open(os.path.join(os.path.dirname(__file__), f"pred_{MODEL}.json"),"w"), ensure_ascii=False)
print(f"{MODEL}: in={in_tok} out={out_tok} n={len(all_results)} (chunks of {CHUNK})")
```

> ⚠️ 이 스크립트의 `output_config`/`count_tokens` 인자는 스파이크 검증용이다. **Task 6의 Java 어댑터가 최종 진실**이며, 여기 목적은 정확도·비용 수치 측정 뿐이다. `system_prompt.txt`는 Task 6 `systemPrompt()`와 동일 내용으로 만든다. **청킹으로 토큰·정확도가 프로덕션(게시물당 50 배치)과 정합**한다.

- [ ] **Step 4: 채점 스크립트 작성 + 실행**

`<scratchpad>/spike/score.py` — 입력 id로 조인해 지표 산출: 4분류 **macro-F1** + 클래스별 P/R/F1 + confusion matrix, **IRRELEVANT recall/precision**, purchase_intent **precision·recall 분리**, keywords **micro-F1**(28어휘), 포맷 실패율(malformed JSON / `len(results)!=len(input)` / 입력 id 집합≠출력 id 집합 / 사전 외 키워드 비율), 비용(입력·출력 토큰 × 단가). **게이트 지표(IRRELEVANT recall/precision, intent P/R)에는 Wilson 95% 신뢰구간을 반드시 병기**한다(아래 `wilson()`). 게이트 판정은 **점추정이 아니라 Wilson CI 하한이 기준선 이상**인지로 한다.

```python
import math
def wilson(k, n, z=1.96):   # k 성공 / n 시행의 Wilson 95% CI (하한, 상한)
    if n == 0: return (0.0, 0.0)
    p = k / n; d = 1 + z*z/n
    c = (p + z*z/(2*n)) / d
    h = (z*math.sqrt(p*(1-p)/n + z*z/(4*n*n))) / d
    return (max(0.0, c-h), min(1.0, c+h))
# 각 게이트 지표는 (점추정, Wilson_하한, Wilson_상한, n)을 함께 출력한다.
```

```bash
cd "$SPIKE"
export ANTHROPIC_API_KEY=sk-ant-...
python classify_spike.py claude-opus-4-8
python classify_spike.py claude-haiku-4-5
python score.py gold.jsonl pred_claude-opus-4-8.json pred_claude-haiku-4-5.json
```

비용 단가(2026-06-24 캐시표): Opus 4.8 `$5/$25` per MTok, Haiku 4.5 `$1/$5` per MTok. **게시물 1,000건 추정**(조사 §5): 입력 4.7M / 출력 1.5M. 검산:
- **Base Opus** = 4.7M×$5 + 1.5M×$25 = 23.5 + 37.5 = **$61.0/1k** ← **출하 기본값 실비**(판단 4: 코드 기본 모델 Opus, 동기·무캐시·무배치).
- **Base Haiku** = 4.7×$1 + 1.5×$5 = 4.7 + 7.5 = **$12.2/1k** ← 스파이크 통과 후 haiku로 내렸을 때의 실비.
- **Batches −50%**: Opus $30.5 / **Haiku $6.1** ← **후속 목표치일 뿐, 현재 코드 경로로는 도달 불가**(판단 3에서 Batches 유보). $6.1을 헤드라인 실비로 인용하지 말 것.

**한국어 토큰(~1.75 tok/자, 댓글 ~25자)은 추정** — 이 Step의 `count_tokens` 실측(청크 합산)으로 ±40% 흔들림을 확정하라. 프롬프트 캐시로 입력 상각을 노리려면 어댑터가 systemPrompt를 `cache_control` 블록으로 감싸야 하는데 현재 어댑터엔 없어 캐시 절감은 0이다(별도 후속 작업).

- [ ] **Step 5: 결과 마크다운 작성 + 커밋 (스크립트는 커밋 안 함)**

`docs/superpowers/spikes/2026-07-10-comment-classification-spike.md`에 방법론·골드셋 구성·κ·두 모델 지표표·비용 실측·**합격 판정**·**결정**을 기록한다. **판정 규칙 = Wilson 95% CI 하한이 기준선 이상**(점추정이 우연히 넘는 걸 게이트로 오인하지 않기 위함). 기준선(κ 확인 후 보정)과 그 근거:

| 지표 | 기준선(CI 하한) | 근거 |
|---|---|---|
| 4분류 macro-F1 | ≥ **0.70** | 클래스 불균형 하 accuracy 대체; 사람-사람 κ 밴드로 앵커 |
| **IRRELEVANT recall** | ≥ **0.90** | **하드 게이트** — 스팸이 감성%·칩·구매의도 분모를 오염. **역산 근거:** 허용 오염률 ≤10%를 목표로 하면 놓친 스팸 비율(=1−recall) ≤0.10 → recall ≥0.90. n≥50 확보로 CI 하한이 0.90을 실제로 분해 가능 |
| IRRELEVANT precision | ≥ 0.80 | 오탐(진성→IRRELEVANT)이 relevant 표본을 갉아먹는 상한. 프리필터 오탐과 합산해 관리 |
| purchase_intent precision | ≥ **0.70** | 구매의도는 희소·고가치 신호라 거짓양성 억제 우선; n≥50 양성으로 CI 반폭 ±0.15 이내 |
| purchase_intent recall | ≥ **0.60** | 희소 신호 회수. precision보다 완화 |
| keyword micro-F1 | ≥ **0.60** | 28어휘 통합 micro(속성별은 표본 충분한 상위 어휘만 참고) |
| 포맷 실패율 | ≈ 0 | 구조화출력이면 0이어야; 아니면 재시도 경로 필수 |
| 모델 vs 사람 | 사람-사람 κ 밴드 내 | 모델에 사람 상한 초과를 요구하지 않음 |

> **표본이 각 클래스 ≥50에 못 미치면**(구매의도 양성 확보 실패 등) 그 지표는 **'통과/탈락 게이트'가 아니라 '모델 선택 방향 근거(+CI)'로 격하**하고 md에 그 한계를 명시한다(CI 하한이 기준선을 분해 못 하면 게이트로 못 씀).

**결정 규칙:** 위 게이트를 **모두** 통과(CI 하한 기준)하는 한 저비용 모델(Haiku 4.5)을 택한다 → `crawler.enrichment.model`을 haiku로. 미달 시 선택지: ① 프롬프트 수정(few-shot·경계 정의 보강) 후 재측정, ② Opus 4.8로 상향, ③ "LLM 불확실" 댓글만 Opus 라우팅, ④ 정규식 프리필터 강화(IRRELEVANT recall 미달 시). 어느 경우든 코드 기본값은 Opus로 두고 스파이크가 haiku 통과를 확인하면 설정만 내린다.

> **필드 순서 트릭 관련(격하):** 스키마 첫 필드를 `category`로 두는 것은 correctness에 load-bearing이 **아니다** — 하위 집계(뷰의 `WHERE category<>'IRRELEVANT'`, PostAnalyzer의 `irrelevant ? List.of() : ...`)와 정규식 프리필터가 이미 스팸을 1단계로 제외하기 때문이다. 따라서 필드 순서는 **'모델 정확도에 도움 될 수 있는 선택적 프롬프트 기법'**으로만 취급하고, 계약상 미보장이므로 게이트 근거로 쓰지 않는다. 정말 검증하려면 score.py에 원시 출력의 키 emit 순서를 파싱해 category 선행률을 리포트하는 측정을 추가하되, 이는 correctness 게이트가 아니다. IRRELEVANT recall 자체가 미달하면 명시적 2-스테이지(Stage A relevance 이진 → Stage B 나머지)로 폴백한다.

```bash
git add docs/superpowers/spikes/2026-07-10-comment-classification-spike.md
git commit -m "docs(enrichment): 댓글 분류 정확도·비용 스파이크 결과 (모델 선택 게이트)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 1: Anthropic SDK 의존성 + EnrichmentProperties + AnthropicClient 빈 + env 주입

**Files:**
- Modify: `crawler/build.gradle`
- Modify: `crawler/src/main/resources/application.yml`
- Modify: `crawler/src/test/resources/application.yml`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/config/EnrichmentProperties.java`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/config/EnrichmentConfig.java`
- Test: `crawler/src/test/java/com/celfit/crawler/enrichment/config/EnrichmentPropertiesTest.java`

- [ ] **Step 1: 실패하는 프로퍼티 바인딩 테스트 작성**

`crawler/src/test/java/com/celfit/crawler/enrichment/config/EnrichmentPropertiesTest.java`:

```java
package com.celfit.crawler.enrichment.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** test/resources/application.yml의 crawler.enrichment.* 바인딩 검증. */
class EnrichmentPropertiesTest extends IntegrationTest {

    @Autowired
    EnrichmentProperties props;

    @Test
    void enrichment_프로퍼티가_바인딩된다() {
        assertThat(props.apiKey()).isEqualTo("test-key");
        assertThat(props.model()).isEqualTo("claude-opus-4-8");
        assertThat(props.promptVersion()).isEqualTo("2026-07-10a");
        assertThat(props.batchLimit()).isEqualTo(100);
        assertThat(props.maxCommentsPerCall()).isEqualTo(50);
        assertThat(props.dryRun()).isFalse();
        assertThat(props.schedule().enabled()).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :crawler:test --tests '*EnrichmentPropertiesTest*'`
Expected: FAIL — `EnrichmentProperties` 심볼 없음(컴파일 에러)

- [ ] **Step 3: SDK 의존성 추가**

`crawler/build.gradle`의 `dependencies` 블록에 한 줄 추가(Boot BOM이 버전 관리 안 하므로 명시 버전):

```groovy
	implementation 'com.anthropic:anthropic-java:2.48.0'
```

- [ ] **Step 4: EnrichmentProperties record 작성**

`crawler/src/main/java/com/celfit/crawler/enrichment/config/EnrichmentProperties.java`:

```java
package com.celfit.crawler.enrichment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * enrichment 파이프라인 설정. AggregateProperties와 동일한 불변 record 스타일.
 * 모델은 하드코딩하지 않고 여기로 주입한다 — haiku 다운그레이드는 yml/env 한 줄 변경.
 */
@ConfigurationProperties("crawler.enrichment")
public record EnrichmentProperties(
        String apiKey,
        String model,
        String promptVersion,    // system 프롬프트 + 통제 키워드 사전을 커버하는 버전 라벨.
                                 // 프롬프트/사전을 바꾸면 반드시 올려야 재분석이 트리거된다(멱등키 일부).
        int batchLimit,          // 1회 실행에서 처리할 게시물 상한(비용 안전장치)
        int maxCommentsPerCall,  // 호출당 댓글 상한(기본 50 = aggregate.comments-per-post)
        int timeoutSeconds,      // SDK 호출 타임아웃
        int maxRetries,          // SDK 자동 재시도 횟수
        boolean dryRun,          // true면 프리필터만, LLM 호출·저장 생략
        Schedule schedule) {

    // 설정 검증(오설정 백스톱): batch-limit 상한을 강제한다. 바인딩 시점(컨텍스트 로딩)에 거부.
    public EnrichmentProperties {
        if (batchLimit > 500) {
            throw new IllegalStateException(
                    "crawler.enrichment.batch-limit는 500을 넘을 수 없습니다(비용 백스톱): " + batchLimit);
        }
    }

    public record Schedule(boolean enabled, String analyzeCron) {}
}
```

- [ ] **Step 5: EnrichmentConfig + AnthropicClient 빈 작성**

`crawler/src/main/java/com/celfit/crawler/enrichment/config/EnrichmentConfig.java`:

```java
package com.celfit.crawler.enrichment.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EnrichmentProperties.class)
public class EnrichmentConfig {

    /**
     * JdkApifyHttp와 동일하게 자격증명 fail-fast. 빌드 시점에 네트워크 호출은 없다.
     * 테스트는 test/resources/application.yml의 api-key=test-key로 통과한다.
     */
    @Bean
    AnthropicClient anthropicClient(EnrichmentProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY가 설정되지 않았습니다 (환경변수 필요)");
        }
        // ⚠️ .apiKey / .timeout(Duration) / .maxRetries(int) 는 표준 Stainless 빌더 메서드(조사 §2).
        //    최초 컴파일에서 cannot find symbol이 나면 anthropic-java 2.48.0의
        //    AnthropicOkHttpClient.Builder 시그니처를 확인해 메서드명을 맞춘다.
        return AnthropicOkHttpClient.builder()
                .apiKey(props.apiKey())
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .maxRetries(props.maxRetries())
                .build();
    }
}
```

- [ ] **Step 6: application.yml에 프로퍼티 추가**

`crawler/src/main/resources/application.yml`의 `crawler:` 블록 끝(schedule 뒤)에 추가:

```yaml
  enrichment:
    api-key: ${ANTHROPIC_API_KEY:}
    model: ${ENRICHMENT_MODEL:claude-opus-4-8}
    prompt-version: ${ENRICHMENT_PROMPT_VERSION:2026-07-10a}   # system 프롬프트/사전 변경 시 반드시 bump
    batch-limit: 100
    max-comments-per-call: 50
    timeout-seconds: 60
    max-retries: 2
    dry-run: false
    schedule:
      enabled: false
      analyze-cron: "0 0 8 * * *"
```

`crawler/src/test/resources/application.yml`의 `crawler:` 블록 끝에 추가(fail-fast 통과용 더미 키 필수 — 없으면 @SpringBootTest 컨텍스트 로딩이 전부 깨진다):

```yaml
  enrichment:
    api-key: test-key
    model: claude-opus-4-8
    prompt-version: 2026-07-10a
    batch-limit: 100
    max-comments-per-call: 50
    timeout-seconds: 5
    max-retries: 0
    dry-run: false
    schedule:
      enabled: false
      analyze-cron: "0 0 8 * * *"
```

- [ ] **Step 7: 테스트 실행 — 통과 확인**

Run: `./gradlew :crawler:test --tests '*EnrichmentPropertiesTest*'`
Expected: PASS. (SDK가 core+okhttp를 전이의존으로 끌어오고 컨텍스트가 test-key로 AnthropicClient 빈을 만든다.)

> **⚠️ 실행 순서 안전장치(중요):** `AnthropicClient` 빈은 EnrichmentConfig에서 **무조건** 생성되어 모든 `@SpringBootTest` 컨텍스트(전 IntegrationTest 서브클래스)에서 인스턴스화된다. 따라서 `AnthropicOkHttpClient.builder().apiKey().timeout().maxRetries().build()` 체인의 메서드명이 하나라도 틀리면 **crawler 테스트 스위트 전체가 '무관한' 컨텍스트 로딩 실패로 위장**해 깨진다. **Task 2~7로 넘어가기 전에 이 Step에서 빌더 체인이 컴파일·컨텍스트 로딩까지 통과하는지 반드시 고립 확인**한다(빌더 시그니처는 고신뢰이나 blast radius가 커 선(先)검증이 필수). cannot find symbol이면 anthropic-java 2.48.0의 `AnthropicOkHttpClient.Builder` 시그니처로 메서드명을 맞춘 뒤에만 다음 Task를 진행한다.

- [ ] **Step 8: Jackson 좌표 충돌 sanity 확인 (양방향)**

Run: `./gradlew :crawler:dependencies --configuration runtimeClasspath | grep -i jackson`
Expected: SDK의 Jackson 2(`com.fasterxml.jackson.core:jackson-databind:2.19.x`)와 Boot의 Jackson 3(`tools.jackson:*`)가 **공존**(패키지·좌표가 달라 하드 충돌 없음). 다른 라이브러리가 상충하는 Jackson 2 버전을 강제하지 않는지만 확인.

**방향 1 (SDK→Spring):** **SDK 타입을 Spring의 Jackson 3 ObjectMapper로 직렬화하지 말 것** — @RestController에서 SDK 객체 직접 반환 금지(자체 DTO로만 매핑).

**방향 2 (공유 인프라 영향 — 새로 명시):** SDK가 jackson-databind 2.19.x를 crawler 클래스패스에 추가하면 Hibernate의 JSON `FormatMapper` 선택에 영향을 줄 수 있다(Jackson 3 단독 → Jackson 2 공존). crawler의 **기존 jsonb 엔티티**(`RawComment.payload` Map)와 **신규 `CommentAnalysis.keywords` List<String>**가 SDK 추가 후에도 그대로 직렬화·라운드트립되는지가 게이트다. 기능 파손 확률은 낮으나(Map/List는 Jackson 2/3가 동일 직렬화) 이 공유 인프라 효과를 무검증으로 두지 않는다. **게이트: Task 2 Step 7**(실 Postgres에서 `List<String>`↔jsonb 라운드트립) + **Task 6 Step 3**(`:crawler:test` 전체 회귀로 `RawComment` 매핑까지 그린). 만약 FormatMapper가 어긋나면 명시적으로 Jackson3 FormatMapper를 Hibernate에 지정하는 설정을 추가한다.

> **cannot find symbol 시 대안(빌더/구조화출력 메서드가 2.48.0과 다르면):** `.timeout`/`.maxRetries`/`.system`/`.outputConfig`/두 번째 `.text()`는 조사 기준 고신뢰이나 미확정. 최초 컴파일에서 틀리면 anthropic-java 2.48.0 소스(`AnthropicOkHttpClient.Builder`, `StructuredOutputsExample.java`)의 정확한 시그니처로 교체한다(플레이스홀더 아님, 검증 지점).

- [ ] **Step 9: Commit**

```bash
git add crawler/build.gradle crawler/src/main/resources/application.yml \
  crawler/src/test/resources/application.yml \
  crawler/src/main/java/com/celfit/crawler/enrichment/config/EnrichmentProperties.java \
  crawler/src/main/java/com/celfit/crawler/enrichment/config/EnrichmentConfig.java \
  crawler/src/test/java/com/celfit/crawler/enrichment/config/EnrichmentPropertiesTest.java
git commit -m "feat(enrichment): Anthropic SDK 도입 + EnrichmentProperties + AnthropicClient 빈

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Flyway V7 comment_analysis + 엔티티 + 리포지토리 (jsonb 라운드트립 검증)

**Files:**
- Create: `crawler/src/main/resources/db/migration/V7__comment_analysis.sql`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/domain/SentimentCategory.java`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/domain/CommentAnalysis.java`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/CommentAnalysisRepository.java`
- Test: `crawler/src/test/java/com/celfit/crawler/enrichment/CommentAnalysisRepositoryTest.java`

- [ ] **Step 1: 실패하는 리포지토리 테스트 작성** (jsonb 배열 라운드트립 + 멱등 검사 메서드)

`crawler/src/test/java/com/celfit/crawler/enrichment/CommentAnalysisRepositoryTest.java`:

```java
package com.celfit.crawler.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.domain.Category;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.content.application.port.out.CategoryRepository;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.enrichment.application.port.out.CommentAnalysisRepository;
import com.celfit.crawler.enrichment.domain.CommentAnalysis;
import com.celfit.crawler.enrichment.domain.SentimentCategory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CommentAnalysisRepositoryTest extends IntegrationTest {

    @Autowired CategoryRepository categories;
    @Autowired ContentRepository contents;
    @Autowired CrawlRunRepository runs;
    @Autowired RawCommentRepository rawComments;
    @Autowired CommentAnalysisRepository analyses;

    private Long seedComment(Long contentId, Long runId, String text) {
        return rawComments.save(new RawComment(contentId, runId,
                Map.of("ownerUsername", "u", "text", text, "timestamp", "2026-07-05T00:00:00Z"),
                Instant.now())).getId();
    }

    @Test
    void keywords_jsonb_배열이_라운드트립되고_멱등검사가_동작한다() {
        Long catId = categories.save(new Category("뷰티")).getId();
        Long runId = runs.save(new CrawlRun(JobName.AGGREGATE, TriggerType.MANUAL, null, null,
                "actor", Instant.now())).getId();
        Content c = contents.save(new Content("sc1", ContentType.REELS, "kim",
                Instant.parse("2026-06-01T00:00:00Z"), catId, "kw", Instant.now()));
        Long commentId = seedComment(c.getId(), runId, "발림성 최고");

        analyses.save(new CommentAnalysis(commentId, c.getId(), SentimentCategory.POSITIVE,
                true, List.of("발림성", "가격"), "claude-opus-4-8", "2026-07-10a", Instant.now()));

        CommentAnalysis found = analyses.findById(commentId).orElseThrow();
        assertThat(found.getCategory()).isEqualTo(SentimentCategory.POSITIVE);
        assertThat(found.isPurchaseIntent()).isTrue();
        assertThat(found.getKeywords()).containsExactly("발림성", "가격");
        assertThat(found.getModel()).isEqualTo("claude-opus-4-8");
        assertThat(found.getPromptVersion()).isEqualTo("2026-07-10a");

        // 멱등 검사: (comment_id, model, prompt_version) 일치해야 존재.
        assertThat(analyses.existsByCommentIdAndModelAndPromptVersion(commentId, "claude-opus-4-8", "2026-07-10a")).isTrue();
        // 모델이 다르면 미존재
        assertThat(analyses.existsByCommentIdAndModelAndPromptVersion(commentId, "claude-haiku-4-5", "2026-07-10a")).isFalse();
        // ★ 프롬프트/사전 버전이 바뀌면 미존재 → 재분석이 트리거된다(조사 §7의 stale-mixing 방지)
        assertThat(analyses.existsByCommentIdAndModelAndPromptVersion(commentId, "claude-opus-4-8", "2026-07-10b")).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :crawler:test --tests '*CommentAnalysisRepositoryTest*'`
Expected: FAIL — `SentimentCategory`/`CommentAnalysis`/`CommentAnalysisRepository` 심볼 없음

- [ ] **Step 3: V7 마이그레이션 작성**

`crawler/src/main/resources/db/migration/V7__comment_analysis.sql`:

```sql
-- ===== enrichment: 댓글 LLM 분석 결과 =====
-- comment_id = raw_comment.id 재사용(PK/FK).
--   ✅ psql 확인: raw_comment.id는 bigint, nextval('raw_comment_id_seq') 기반 안정 PK
--   (\d raw_comment → "raw_comment_pkey" PRIMARY KEY btree (id)). 따라서 아래 FK는 유효하다.
-- 한 댓글 = 한 행. 멱등 키는 애플리케이션의 (comment_id, model, prompt_version) 존재 검사.
--   prompt_version = system 프롬프트 + 통제 키워드 사전을 커버하는 버전 라벨(설계 판단 2).
--   프롬프트/사전 변경 시 이 값을 올려야 재분석이 트리거된다(조사 §7의 stale-mixing 방지).
--   ⚠️ MVP 트레이드오프: PK가 comment_id 단독이라 model/prompt_version 교체 시 merge가 기존 행을
--      '덮어쓴다'(A/B·이력 보존 불가, 조사 §7 non-overwrite 미준수 — Self-Review에 명시).
--      row별 model/prompt_version은 이 테이블에 남아 감사·수동 무효화(delete 후 재분석)는 가능.
-- category는 CHECK로 4분류만 허용. keywords는 통제 어휘 문자열 배열을 jsonb로 저장 —
--   analytics 뷰가 jsonb_array_elements_text로 집계하고, MaterializationService 미러는
--   게시물 단위 집계 뷰(칩=jsonb)만 보므로 이 저장 배열 형식은 미러 DDL에 노출되지 않는다(판단 1).
CREATE TABLE comment_analysis (
    comment_id      bigint      PRIMARY KEY REFERENCES raw_comment(id),
    content_id      bigint      NOT NULL REFERENCES content(id),
    category        text        NOT NULL CHECK (category IN ('POSITIVE','NEUTRAL','NEGATIVE','IRRELEVANT')),
    purchase_intent boolean     NOT NULL DEFAULT false,
    keywords        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    model           text        NOT NULL,
    prompt_version  text        NOT NULL,
    analyzed_at     timestamptz NOT NULL
);
CREATE INDEX idx_comment_analysis_content ON comment_analysis(content_id);
CREATE INDEX idx_comment_analysis_model ON comment_analysis(model, prompt_version);
```

- [ ] **Step 4: SentimentCategory enum 작성**

`crawler/src/main/java/com/celfit/crawler/enrichment/domain/SentimentCategory.java`:

```java
package com.celfit.crawler.enrichment.domain;

/** 감성 4분류. IRRELEVANT = 스팸·광고·무관·봇 통합(확정안). */
public enum SentimentCategory { POSITIVE, NEUTRAL, NEGATIVE, IRRELEVANT }
```

- [ ] **Step 5: CommentAnalysis 엔티티 작성**

`crawler/src/main/java/com/celfit/crawler/enrichment/domain/CommentAnalysis.java`:

```java
package com.celfit.crawler.enrichment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 댓글 분석 결과 1행. comment_id = raw_comment.id를 직접 할당(시퀀스 아님).
 * keywords는 RawComment.payload와 동일한 jsonb 매핑 패턴(@JdbcTypeCode JSON)을 재사용.
 */
@Entity
@Table(name = "comment_analysis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommentAnalysis {

    @Id
    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SentimentCategory category;

    @Column(name = "purchase_intent", nullable = false)
    private boolean purchaseIntent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> keywords;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    public CommentAnalysis(Long commentId, Long contentId, SentimentCategory category,
                           boolean purchaseIntent, List<String> keywords, String model,
                           String promptVersion, Instant analyzedAt) {
        this.commentId = commentId;
        this.contentId = contentId;
        this.category = category;
        this.purchaseIntent = purchaseIntent;
        this.keywords = keywords;
        this.model = model;
        this.promptVersion = promptVersion;
        this.analyzedAt = analyzedAt;
    }
}
```

> 멱등 upsert: `commentId`가 non-null이라 Spring Data가 `merge`(SELECT→INSERT/UPDATE)로 저장한다. 같은 `(model, prompt_version)` 재실행은 Task 5에서 사전에 `existsByCommentIdAndModelAndPromptVersion`으로 걸러 아예 save하지 않고, model 또는 prompt_version이 바뀌면 멱등 검사가 miss → 재분석되며 merge가 기존 행을 덮어쓴다(MVP 덮어쓰기, 판단 2 트레이드오프).

- [ ] **Step 6: 리포지토리 작성**

`crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/CommentAnalysisRepository.java`:

```java
package com.celfit.crawler.enrichment.application.port.out;

import com.celfit.crawler.enrichment.domain.CommentAnalysis;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentAnalysisRepository extends JpaRepository<CommentAnalysis, Long> {

    /** 멱등 검사: 같은 (comment_id, model, prompt_version)로 이미 분석됐는지. */
    boolean existsByCommentIdAndModelAndPromptVersion(Long commentId, String model, String promptVersion);

    List<CommentAnalysis> findByContentId(Long contentId);

    /** 현재 (model, prompt_version)로 아직 분석되지 않은 댓글이 하나라도 있는 게시물 id (오래된 순, 상한은 Pageable). */
    @Query(value = """
            SELECT DISTINCT rc.content_id
            FROM raw_comment rc
            WHERE NOT EXISTS (
                SELECT 1 FROM comment_analysis ca
                WHERE ca.comment_id = rc.id
                  AND ca.model = :model
                  AND ca.prompt_version = :promptVersion)
            ORDER BY rc.content_id
            """, nativeQuery = true)
    List<Long> findContentIdsWithUnanalyzedComments(@Param("model") String model,
                                                    @Param("promptVersion") String promptVersion,
                                                    Pageable pageable);
}
```

- [ ] **Step 7: 테스트 실행 — 통과 확인**

Run: `./gradlew :crawler:test --tests '*CommentAnalysisRepositoryTest*'`
Expected: PASS. (실 Postgres에서 jsonb 배열 저장/조회 라운드트립 확인.)

> ⚠️ 만약 `List<String>` ↔ jsonb 매핑이 Hibernate에서 실패하면(예: `could not determine type`), 대안: `keywords` 필드를 `@JdbcTypeCode(SqlTypes.JSON) private java.util.List<String> keywords;` 그대로 두되 컬럼을 명시(`columnDefinition = "jsonb"`)하거나, 최후에는 `AttributeConverter<List<String>,String>`를 붙인다. 이 Step이 그 결정을 컴파일·런타임으로 확정하는 지점이다.

- [ ] **Step 8: Commit**

```bash
git add crawler/src/main/resources/db/migration/V7__comment_analysis.sql \
  crawler/src/main/java/com/celfit/crawler/enrichment/domain/SentimentCategory.java \
  crawler/src/main/java/com/celfit/crawler/enrichment/domain/CommentAnalysis.java \
  crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/CommentAnalysisRepository.java \
  crawler/src/test/java/com/celfit/crawler/enrichment/CommentAnalysisRepositoryTest.java
git commit -m "feat(enrichment): V7 comment_analysis 테이블 + 엔티티 + 리포지토리 (keywords jsonb)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 정규식 프리필터 (순수 함수) + 파라미터라이즈드 테스트

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/application/service/SpamPreFilter.java`
- Test: `crawler/src/test/java/com/celfit/crawler/enrichment/application/service/SpamPreFilterTest.java`

- [ ] **Step 1: 실패하는 파라미터라이즈드 테스트 작성**

`crawler/src/test/java/com/celfit/crawler/enrichment/application/service/SpamPreFilterTest.java` (순수 함수 → Spring 컨텍스트 불필요):

```java
package com.celfit.crawler.enrichment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpamPreFilterTest {

    private final SpamPreFilter filter = new SpamPreFilter();

    @ParameterizedTest
    @ValueSource(strings = {
            "프로필 링크 확인 https://bit.ly/abc",   // LINK
            "공구 진행중 디엠 주세요",                 // CONTACT(디엠 주세요) + PROMO(공구 진행)
            "카톡: shopping123",                      // CONTACT
            "토토 먹튀검증 문의",                       // GAMBLING
            "카지노 꽁머니 지급",                       // GAMBLING
            "010-1234-5678 연락주세요",               // CONTACT(전화번호)
            "😍😍😍",                                  // 잔여 한글/영숫자 0
            "❤️❤️",                                    // 잔여 한글/영숫자 0
            "   ",                                     // 공백
    })
    void 명백한_스팸은_true(String text) {
        assertThat(filter.isObviousSpam(text)).isTrue();
    }

    // ⚠️ 오탐 방지 회귀(조사 §4: '친근한 맞팔성 댓글 vs 진성 댓글 구분'은 LLM 몫).
    //    맨 부분문자열 '맞팔/선팔/부업/재테크'와 정상 결제어 '후불'은 하드필터에서 제외했으므로
    //    링크·연락처가 없으면 프리필터를 통과(false)해 LLM으로 넘어가야 한다.
    @ParameterizedTest
    @ValueSource(strings = {
            "발림성 진짜 최고예요",
            "이거 어디서 사요? 가격 궁금해요",
            "건성인데 괜찮을까요?",
            "향이 좀 강한 편이네요",
            "촉촉하고 좋아요 재구매 의사 있어요",
            "별로였어요 유분감 심함",
            "발림성 최고 맞팔해요",          // 진성 제품감성 + 맞팔 → 하드필터 금지(LLM 판단)
            "후불 되나요?",                  // 정상 결제 문의('후불')는 도박 아님
            "선팔하고가요",                  // 링크·연락처 없는 맨 맞팔성 → LLM으로 위임(하드 스팸 아님)
    })
    void 진성_또는_모호_댓글은_false(String text) {
        assertThat(filter.isObviousSpam(text)).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :crawler:test --tests '*SpamPreFilterTest*'`
Expected: FAIL — `SpamPreFilter` 심볼 없음

- [ ] **Step 3: SpamPreFilter 작성**

`crawler/src/main/java/com/celfit/crawler/enrichment/application/service/SpamPreFilter.java`:

```java
package com.celfit.crawler.enrichment.application.service;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * LLM 호출 전 '명백한' 스팸만 하드필터하는 순수 규칙(고정밀 우선 — 불확실하면 통과시켜 LLM에 맡긴다).
 * 과공격적이면 진성 댓글을 소리 없이 삭제해 감성%·칩 집계를 편향시키므로, 확실한 신호만 잡는다.
 *
 * ⚠️ 조사 §4 원칙 준수: '친근한 맞팔성 댓글 vs 진성 댓글 구분'은 LLM 몫이므로, 링크·연락처 없는 맨
 *    '맞팔/선팔/부업/재테크'는 하드필터하지 않는다(진성 제품 댓글을 영구 IRRELEVANT로 오탐 방지).
 *    '후불'은 정상 결제어라 GAMBLING에서 제외. 프리필터로 IRRELEVANT 확정한 원문은 매칭 규칙명과 함께
 *    감사 로그로 남긴다(PostAnalyzer가 reason()을 로깅 — 오탐 추적 가능).
 */
@Component
public class SpamPreFilter {

    private static final Pattern LINK =
            Pattern.compile("(?i)(https?://|bit\\.ly|t\\.me|\\.shop|\\.store|telegram|텔레\\s*@)");
    private static final Pattern CONTACT =
            Pattern.compile("(?i)(카톡\\s*[:：]|디엠\\s*주세요|dm\\s*주세요|010[-.\\s]?\\d{3,4}[-.\\s]?\\d{4})");
    // '후불' 제거(정상 결제어). 확정적 도박·성인 신호만.
    private static final Pattern GAMBLING =
            Pattern.compile("토토|카지노|먹튀|배팅|베팅|꽁머니|야동");
    // 맨 '맞팔|선팔|부업|재테크' 제거(→LLM 위임). 명시적 모집·공구 솔리시테이션만 고정밀로 잡는다.
    private static final Pattern PROMO =
            Pattern.compile("공구\\s*진행|체험단\\s*모집|협찬\\s*문의");
    /** 한글/영문/숫자 잔여가 0이면(이모지·문장부호만) 무의미 → IRRELEVANT. */
    private static final Pattern ALNUM_HANGUL = Pattern.compile("[0-9A-Za-z가-힣]");

    /** 매칭된 하드 스팸 규칙명(감사용). 스팸이 아니면 empty. */
    public Optional<String> reason(String text) {
        if (text == null) {
            return Optional.of("NULL");
        }
        String t = text.strip();
        if (t.isEmpty() || !ALNUM_HANGUL.matcher(t).find()) {
            return Optional.of("EMPTY_OR_SYMBOLS");
        }
        if (LINK.matcher(t).find()) return Optional.of("LINK");
        if (CONTACT.matcher(t).find()) return Optional.of("CONTACT");
        if (GAMBLING.matcher(t).find()) return Optional.of("GAMBLING");
        if (PROMO.matcher(t).find()) return Optional.of("PROMO");
        return Optional.empty();
    }

    public boolean isObviousSpam(String text) {
        return reason(text).isPresent();
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :crawler:test --tests '*SpamPreFilterTest*'`
Expected: 18개 케이스 전부 PASS (스팸 9 + 진성/모호 9). 특히 '발림성 최고 맞팔해요'·'후불 되나요?'가 false로 통과(오탐 회귀).

- [ ] **Step 5: Commit**

```bash
git add crawler/src/main/java/com/celfit/crawler/enrichment/application/service/SpamPreFilter.java \
  crawler/src/test/java/com/celfit/crawler/enrichment/application/service/SpamPreFilterTest.java
git commit -m "feat(enrichment): 명백 스팸 정규식 프리필터 (고정밀, LLM 선행 1단계)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 분류 포트 + 소스 읽기 포트 + Fake

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/CommentClassifierPort.java`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/SourceCommentRepository.java`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/PostContextRepository.java`
- Create: `crawler/src/test/java/com/celfit/crawler/enrichment/FakeCommentClassifier.java`

이 Task는 순수 인터페이스/포트 + 테스트용 Fake만 만든다(구현/소비는 Task 5·6). 별도 테스트 없이 Task 5 통합테스트가 이들을 검증한다. 각 파일은 컴파일 가능한 완성본이다.

- [ ] **Step 1: 분류 포트 작성**

`crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/CommentClassifierPort.java`:

```java
package com.celfit.crawler.enrichment.application.port.out;

import com.celfit.crawler.enrichment.domain.SentimentCategory;
import java.util.List;

/** 댓글 배치 분류 아웃바운드 포트. 실제 구현은 Anthropic 어댑터, 테스트는 Fake. */
public interface CommentClassifierPort {

    List<Classification> classify(PostContext post, List<CommentToClassify> comments);

    /** 게시물 문맥(참고용). caption은 절단됨, category는 main_group. */
    record PostContext(String caption, String category) {}

    /** id = raw_comment.id (위치가 아닌 id로 결과를 조인). */
    record CommentToClassify(long id, String text) {}

    record Classification(long id, SentimentCategory category, boolean purchaseIntent, List<String> keywords) {}
}
```

- [ ] **Step 2: 소스 댓글 읽기 포트 작성** (enrichment 전용 — crawling 포트 미변경)

`crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/SourceCommentRepository.java`:

```java
package com.celfit.crawler.enrichment.application.port.out;

import com.celfit.crawler.crawling.domain.RawComment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** enrichment가 crawling의 raw_comment를 읽는 전용 포트(파이프라인 방향 의존, 읽기만). */
public interface SourceCommentRepository extends JpaRepository<RawComment, Long> {
    List<RawComment> findByContentId(Long contentId);
}
```

- [ ] **Step 3: 게시물 문맥 읽기 포트 작성** (네이티브 프로젝션)

`crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/PostContextRepository.java`:

```java
package com.celfit.crawler.enrichment.application.port.out;

import com.celfit.crawler.content.domain.Content;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** content.main_group + 최신 raw_post_detail.caption을 한 번에 읽는 읽기 전용 포트. */
public interface PostContextRepository extends Repository<Content, Long> {

    // 별칭을 쌍따옴표로 감싸 대소문자를 보존한다(Postgres는 미인용 별칭을 소문자화 → 프로젝션 매칭 실패).
    @Query(value = """
            SELECT c.main_group AS "mainGroup",
                   (SELECT rpd.caption FROM raw_post_detail rpd
                    WHERE rpd.content_id = c.id
                    ORDER BY rpd.captured_at DESC LIMIT 1) AS "caption"
            FROM content c
            WHERE c.id = :contentId
            """, nativeQuery = true)
    PostContextRow findContext(@Param("contentId") Long contentId);

    interface PostContextRow {
        String getMainGroup();
        String getCaption();
    }
}
```

- [ ] **Step 4: Fake 분류기 작성** (테스트 전용, 실 API 미호출)

`crawler/src/test/java/com/celfit/crawler/enrichment/FakeCommentClassifier.java`:

```java
package com.celfit.crawler.enrichment;

import com.celfit.crawler.enrichment.application.port.out.CommentClassifierPort;
import com.celfit.crawler.enrichment.domain.SentimentCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 스크립트된 분류 결과를 반환하고 호출을 기록한다. 실 Anthropic API는 절대 호출하지 않는다. */
public class FakeCommentClassifier implements CommentClassifierPort {

    public final List<List<CommentToClassify>> calls = new ArrayList<>();
    private Function<CommentToClassify, Classification> rule =
            c -> new Classification(c.id(), SentimentCategory.POSITIVE, false, List.of());
    private RuntimeException toThrow;

    /** 텍스트 기반 규칙 주입. */
    public void classifyWith(Function<CommentToClassify, Classification> rule) {
        this.rule = rule;
    }

    /** 다음 classify 호출에서 예외를 던지게 한다(부분 실패 테스트용). */
    public void failNext(RuntimeException e) {
        this.toThrow = e;
    }

    public void reset() {
        calls.clear();
        toThrow = null;
        rule = c -> new Classification(c.id(), SentimentCategory.POSITIVE, false, List.of());
    }

    @Override
    public List<Classification> classify(PostContext post, List<CommentToClassify> comments) {
        calls.add(comments);
        if (toThrow != null) {
            RuntimeException e = toThrow;
            toThrow = null;
            throw e;
        }
        return comments.stream().map(rule).toList();
    }
}
```

- [ ] **Step 5: 컴파일 확인**

Run: `./gradlew :crawler:compileJava :crawler:compileTestJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/CommentClassifierPort.java \
  crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/SourceCommentRepository.java \
  crawler/src/main/java/com/celfit/crawler/enrichment/application/port/out/PostContextRepository.java \
  crawler/src/test/java/com/celfit/crawler/enrichment/FakeCommentClassifier.java
git commit -m "feat(enrichment): 분류/소스 읽기 포트 + 테스트용 Fake 분류기

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: PostAnalyzer + EnrichmentJob (프리필터→LLM→저장, 멱등·부분실패·dry-run)

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/application/service/PostAnalyzer.java`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/application/service/EnrichmentJob.java`
- Test: `crawler/src/test/java/com/celfit/crawler/enrichment/application/service/EnrichmentJobTest.java`
- Test: `crawler/src/test/java/com/celfit/crawler/enrichment/application/service/EnrichmentDryRunTest.java`

- [ ] **Step 1: 실패하는 통합 테스트 작성** (fake 분류기, per-post 트랜잭션·멱등·부분실패)

`crawler/src/test/java/com/celfit/crawler/enrichment/application/service/EnrichmentJobTest.java`:

```java
package com.celfit.crawler.enrichment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.domain.Category;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.content.application.port.out.CategoryRepository;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.enrichment.FakeCommentClassifier;
import com.celfit.crawler.enrichment.application.port.out.CommentAnalysisRepository;
import com.celfit.crawler.enrichment.application.port.out.CommentClassifierPort;
import com.celfit.crawler.enrichment.domain.CommentAnalysis;
import com.celfit.crawler.enrichment.domain.SentimentCategory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(EnrichmentJobTest.Config.class)
class EnrichmentJobTest extends IntegrationTest {

    // ⚠️ @Transactional 롤백을 쓰지 않는다 — PostAnalyzer의 per-post 트랜잭션(독립 커밋)이
    //    부분 성공의 핵심이라, 클래스 롤백으로 감싸면 그 의미가 사라진다. @BeforeEach로 직접 청소.
    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeCommentClassifier fakeCommentClassifier() {
            return new FakeCommentClassifier();
        }
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {  // 컨텍스트 로딩용(실 Apify 어댑터 대체)
            return new FakeApifyRunner();
        }
    }

    @Autowired FakeCommentClassifier fake;
    @Autowired EnrichmentJob job;
    @Autowired CategoryRepository categories;
    @Autowired ContentRepository contents;
    @Autowired CrawlRunRepository runs;
    @Autowired RawCommentRepository rawComments;
    @Autowired CommentAnalysisRepository analyses;
    @Autowired JdbcTemplate jdbc;

    Long catId, runId;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE comment_analysis, raw_comment, content, crawl_run, category RESTART IDENTITY CASCADE");
        fake.reset();
        catId = categories.save(new Category("뷰티")).getId();
        runId = runs.save(new CrawlRun(JobName.AGGREGATE, TriggerType.MANUAL, null, null,
                "actor", Instant.now())).getId();
    }

    Long seedContent(String shortCode) {
        Content c = new Content(shortCode, ContentType.REELS, "kim",
                Instant.parse("2026-06-01T00:00:00Z"), catId, "kw", Instant.now());
        return contents.save(c).getId();
    }

    Long seedComment(Long contentId, String text) {
        return rawComments.save(new RawComment(contentId, runId,
                Map.of("ownerUsername", "u", "text", text, "timestamp", "2026-07-05T00:00:00Z"),
                Instant.now())).getId();
    }

    @Test
    void 프리필터는_IRRELEVANT로_선확정하고_나머지만_LLM에_보낸다() {
        Long cid = seedContent("sc1");
        Long spamId = seedComment(cid, "맞팔해요 놀러오세요");   // 프리필터가 잡음
        Long realId = seedComment(cid, "발림성 최고");           // LLM 대상
        fake.classifyWith(c -> new CommentClassifierPort.Classification(
                c.id(), SentimentCategory.POSITIVE, true, List.of("발림성")));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.posts()).isEqualTo(1);
        assertThat(summary.prefiltered()).isEqualTo(1);
        assertThat(summary.llmClassified()).isEqualTo(1);
        // fake는 프리필터를 통과한 1건만 받았다
        assertThat(fake.calls).hasSize(1);
        assertThat(fake.calls.get(0)).extracting(CommentClassifierPort.CommentToClassify::id)
                .containsExactly(realId);
        // 저장 결과
        assertThat(analyses.findById(spamId).orElseThrow().getCategory())
                .isEqualTo(SentimentCategory.IRRELEVANT);
        CommentAnalysis real = analyses.findById(realId).orElseThrow();
        assertThat(real.getCategory()).isEqualTo(SentimentCategory.POSITIVE);
        assertThat(real.isPurchaseIntent()).isTrue();
        assertThat(real.getKeywords()).containsExactly("발림성");
    }

    @Test
    void 같은_model로_이미_분석된_댓글은_건너뛴다_멱등() {
        Long cid = seedContent("sc1");
        seedComment(cid, "발림성 최고");
        job.run(TriggerType.MANUAL);
        fake.reset();

        var second = job.run(TriggerType.MANUAL);   // 재실행

        assertThat(second.posts()).isZero();          // 미분석 댓글 없음 → 대상 게시물 0
        assertThat(fake.calls).isEmpty();             // LLM 재호출 없음
        assertThat(analyses.count()).isEqualTo(1);    // 행 증가 없음
    }

    @Test
    void 한_게시물_분류실패는_그_게시물만_롤백하고_나머지는_커밋된다() {
        Long okCid = seedContent("ok");
        Long okComment = seedComment(okCid, "발림성 최고");
        Long badCid = seedContent("bad");
        Long badComment = seedComment(badCid, "가격 궁금");
        // bad 게시물(content_id가 더 큼 → 두 번째 처리)에서 LLM이 터진다
        fake.classifyWith(c -> {
            if (c.id() == badComment) throw new RuntimeException("rate limit");
            return new CommentClassifierPort.Classification(
                    c.id(), SentimentCategory.POSITIVE, false, List.of("발림성"));
        });

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.failedPosts()).isEqualTo(1);
        assertThat(analyses.findById(okComment)).isPresent();      // ok는 커밋됨
        assertThat(analyses.findById(badComment)).isEmpty();       // bad는 롤백됨 → 다음 실행 재시도
    }
}
```

> ⚠️ 위 `failNext` 대신 규칙(`classifyWith`)으로 특정 id에서 던지게 한다(게시물별 호출이 분리돼 있어 id 기준 제어가 정확). `RawComment(contentId, crawlRunId, payload, capturedAt)` 생성자와 `RawComment.getText()`(generated column)를 사용한다.

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :crawler:test --tests '*EnrichmentJobTest*'`
Expected: FAIL — `PostAnalyzer`/`EnrichmentJob` 심볼 없음

- [ ] **Step 3: PostAnalyzer 작성** (게시물 1건 = 트랜잭션 1개)

`crawler/src/main/java/com/celfit/crawler/enrichment/application/service/PostAnalyzer.java`:

```java
package com.celfit.crawler.enrichment.application.service;

import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.enrichment.application.port.out.CommentAnalysisRepository;
import com.celfit.crawler.enrichment.application.port.out.CommentClassifierPort;
import com.celfit.crawler.enrichment.application.port.out.CommentClassifierPort.Classification;
import com.celfit.crawler.enrichment.application.port.out.CommentClassifierPort.CommentToClassify;
import com.celfit.crawler.enrichment.application.port.out.CommentClassifierPort.PostContext;
import com.celfit.crawler.enrichment.application.port.out.PostContextRepository;
import com.celfit.crawler.enrichment.application.port.out.SourceCommentRepository;
import com.celfit.crawler.enrichment.config.EnrichmentProperties;
import com.celfit.crawler.enrichment.domain.CommentAnalysis;
import com.celfit.crawler.enrichment.domain.SentimentCategory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시물 1건 = 트랜잭션 1개. 프리필터 → LLM 분류 → 저장. 예외 시 이 게시물만 롤백되고
 * 상위(EnrichmentJob)가 다음 게시물을 계속한다. 같은 model로 이미 분석된 댓글은 건너뛴다(멱등).
 *
 * ⚠️ 동기 messages().create() 호출을 @Transactional 안에서 수행한다(AggregateJob이 Apify HTTP를
 *    트랜잭션 안에서 호출하는 것과 동일 선례). 이후 Message Batches(24h 비동기)로 전환하면
 *    이 트랜잭션 경계를 반드시 쪼개야 한다(실행 기록을 트랜잭션 밖으로).
 */
@Component
public class PostAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PostAnalyzer.class);

    public record PostResult(int prefiltered, int llmClassified) {}

    private final SourceCommentRepository sourceComments;
    private final CommentAnalysisRepository analyses;
    private final PostContextRepository postContexts;
    private final CommentClassifierPort classifier;
    private final SpamPreFilter preFilter;
    private final EnrichmentProperties props;
    private final Clock clock;

    public PostAnalyzer(SourceCommentRepository sourceComments, CommentAnalysisRepository analyses,
                        PostContextRepository postContexts, CommentClassifierPort classifier,
                        SpamPreFilter preFilter, EnrichmentProperties props, Clock clock) {
        this.sourceComments = sourceComments;
        this.analyses = analyses;
        this.postContexts = postContexts;
        this.classifier = classifier;
        this.preFilter = preFilter;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public PostResult analyze(Long contentId) {
        String model = props.model();
        String promptVersion = props.promptVersion();
        List<RawComment> comments = sourceComments.findByContentId(contentId).stream()
                // 멱등: 같은 (comment_id, model, prompt_version)면 skip. 프롬프트/사전 bump 시 재분석.
                .filter(rc -> !analyses.existsByCommentIdAndModelAndPromptVersion(rc.getId(), model, promptVersion))
                .limit(props.maxCommentsPerCall())                                     // 호출당 상한(≤50)
                .toList();
        if (comments.isEmpty()) {
            return new PostResult(0, 0);
        }

        // 1단계: 명백한 스팸 하드필터 → IRRELEVANT 즉시 확정(LLM 토큰 절감).
        //   프리필터가 IRRELEVANT로 확정한 원문은 규칙명과 함께 감사 로그로 남긴다(오탐 추적).
        List<CommentToClassify> toLlm = new ArrayList<>();
        int prefiltered = 0;
        for (RawComment rc : comments) {
            var spamReason = preFilter.reason(rc.getText());
            if (spamReason.isPresent()) {
                log.info("[prefilter] IRRELEVANT 확정 content_id={} comment_id={} rule={} text=\"{}\"",
                        contentId, rc.getId(), spamReason.get(), rc.getText());
                if (!props.dryRun()) {
                    analyses.save(new CommentAnalysis(rc.getId(), contentId,
                            SentimentCategory.IRRELEVANT, false, List.of(), model, promptVersion, clock.instant()));
                }
                prefiltered++;
            } else {
                toLlm.add(new CommentToClassify(rc.getId(), rc.getText()));
            }
        }

        if (toLlm.isEmpty()) {
            return new PostResult(prefiltered, 0);
        }
        if (props.dryRun()) {
            log.info("[dry-run] content_id={} 프리필터 {}건, LLM 예정 {}건 (호출/저장 생략)",
                    contentId, prefiltered, toLlm.size());
            return new PostResult(prefiltered, 0);
        }

        // 2단계: 나머지를 한 번의 structured-output 호출로 4분류+구매의도+키워드.
        var ctx = postContexts.findContext(contentId);
        PostContext post = new PostContext(
                ctx == null ? null : ctx.getCaption(),
                ctx == null ? null : ctx.getMainGroup());
        List<Classification> results = classifier.classify(post, toLlm);
        Map<Long, Classification> byId = results.stream()
                .collect(Collectors.toMap(Classification::id, Function.identity(), (a, b) -> a));

        int classified = 0;
        for (CommentToClassify in : toLlm) {
            Classification r = byId.get(in.id());
            if (r == null) {
                // 모델이 일부 id를 누락 — 저장 안 함 → 다음 실행에서 재시도(멱등).
                log.warn("분류 결과 누락 comment_id={} (다음 실행에서 재시도)", in.id());
                continue;
            }
            boolean irrelevant = r.category() == SentimentCategory.IRRELEVANT;
            analyses.save(new CommentAnalysis(in.id(), contentId, r.category(),
                    !irrelevant && r.purchaseIntent(),
                    irrelevant ? List.of() : capKeywords(r.keywords()),
                    model, promptVersion, clock.instant()));
            classified++;
        }
        return new PostResult(prefiltered, classified);
    }

    /** strict 스키마는 배열 길이를 강제 못 하므로 서버측에서 키워드 상한(3)을 적용. */
    private List<String> capKeywords(List<String> keywords) {
        if (keywords == null) {
            return List.of();
        }
        return keywords.stream().distinct().limit(3).toList();
    }
}
```

- [ ] **Step 4: EnrichmentJob 작성** (오케스트레이션, batch-limit, 부분 실패 집계)

`crawler/src/main/java/com/celfit/crawler/enrichment/application/service/EnrichmentJob.java`:

```java
package com.celfit.crawler.enrichment.application.service;

import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.enrichment.application.port.out.CommentAnalysisRepository;
import com.celfit.crawler.enrichment.config.EnrichmentProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** 클래스 레벨 @Transactional 없음 — 게시물별 트랜잭션(PostAnalyzer)으로 부분 성공을 보존. */
@Service
public class EnrichmentJob {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentJob.class);

    public record Summary(int posts, int prefiltered, int llmClassified, int failedPosts) {}

    private final CommentAnalysisRepository analyses;
    private final PostAnalyzer postAnalyzer;
    private final EnrichmentProperties props;

    public EnrichmentJob(CommentAnalysisRepository analyses, PostAnalyzer postAnalyzer,
                         EnrichmentProperties props) {
        this.analyses = analyses;
        this.postAnalyzer = postAnalyzer;
        this.props = props;
    }

    public Summary run(TriggerType trigger) {
        List<Long> due = analyses.findContentIdsWithUnanalyzedComments(
                props.model(), props.promptVersion(), PageRequest.of(0, props.batchLimit()));
        log.info("enrichment 시작: 대상 게시물 {}건 (model={}, promptVersion={}, trigger={}, dryRun={})",
                due.size(), props.model(), props.promptVersion(), trigger, props.dryRun());

        int posts = 0, prefiltered = 0, classified = 0, failed = 0;
        for (Long contentId : due) {
            try {
                var r = postAnalyzer.analyze(contentId);
                prefiltered += r.prefiltered();
                classified += r.llmClassified();
                posts++;
            } catch (Exception e) {
                failed++;
                log.error("게시물 분석 실패 content_id={} (건너뜀)", contentId, e);
            }
        }
        var summary = new Summary(posts, prefiltered, classified, failed);
        log.info("enrichment 종료: {}", summary);
        return summary;
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :crawler:test --tests '*EnrichmentJobTest*'`
Expected: 3 tests PASS

- [ ] **Step 6: dry-run 테스트 작성**

`crawler/src/test/java/com/celfit/crawler/enrichment/application/service/EnrichmentDryRunTest.java`:

```java
package com.celfit.crawler.enrichment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.domain.Category;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.content.application.port.out.CategoryRepository;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.enrichment.FakeCommentClassifier;
import com.celfit.crawler.enrichment.application.port.out.CommentAnalysisRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@Import(EnrichmentDryRunTest.Config.class)
@TestPropertySource(properties = "crawler.enrichment.dry-run=true")   // 컨텍스트 분리 로딩
class EnrichmentDryRunTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary FakeCommentClassifier fakeCommentClassifier() { return new FakeCommentClassifier(); }
        @Bean @Primary FakeApifyRunner fakeApifyRunner() { return new FakeApifyRunner(); }
    }

    @Autowired FakeCommentClassifier fake;
    @Autowired EnrichmentJob job;
    @Autowired CategoryRepository categories;
    @Autowired ContentRepository contents;
    @Autowired CrawlRunRepository runs;
    @Autowired RawCommentRepository rawComments;
    @Autowired CommentAnalysisRepository analyses;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE comment_analysis, raw_comment, content, crawl_run, category RESTART IDENTITY CASCADE");
        fake.reset();
    }

    @Test
    void dry_run은_LLM_호출도_저장도_하지_않는다() {
        Long catId = categories.save(new Category("뷰티")).getId();
        Long runId = runs.save(new CrawlRun(JobName.AGGREGATE, TriggerType.MANUAL, null, null,
                "actor", Instant.now())).getId();
        Long cid = contents.save(new Content("sc1", ContentType.REELS, "kim",
                Instant.parse("2026-06-01T00:00:00Z"), catId, "kw", Instant.now())).getId();
        rawComments.save(new RawComment(cid, runId,
                Map.of("ownerUsername", "u", "text", "발림성 최고", "timestamp", "2026-07-05T00:00:00Z"),
                Instant.now()));

        job.run(TriggerType.MANUAL);

        assertThat(fake.calls).isEmpty();       // LLM 호출 없음
        assertThat(analyses.count()).isZero();  // 저장 없음
    }
}
```

- [ ] **Step 7: dry-run 테스트 실행 — 통과 확인**

Run: `./gradlew :crawler:test --tests '*EnrichmentDryRunTest*'`
Expected: 1 test PASS

- [ ] **Step 8: Commit**

```bash
git add crawler/src/main/java/com/celfit/crawler/enrichment/application/service/PostAnalyzer.java \
  crawler/src/main/java/com/celfit/crawler/enrichment/application/service/EnrichmentJob.java \
  crawler/src/test/java/com/celfit/crawler/enrichment/application/service/EnrichmentJobTest.java \
  crawler/src/test/java/com/celfit/crawler/enrichment/application/service/EnrichmentDryRunTest.java
git commit -m "feat(enrichment): PostAnalyzer(per-post tx)+EnrichmentJob (프리필터·멱등·부분실패·dry-run)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Anthropic structured-output 어댑터 (실 API 미호출 — 컴파일로 확정)

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/adapter/out/anthropic/AnthropicCommentClassifier.java`

이 어댑터의 동작은 **fake(Task 5)로 이미 커버**된다. 실 API를 때리는 단위 테스트는 만들지 않는다(과제 규칙). 대신 **structured outputs 배선을 컴파일로 확정**한다. 코드는 조사에서 확인된 API(StructuredOutputsExample.java)만 쓰고, 미확인 지점은 주석으로 표시한다.

- [ ] **Step 1: 어댑터 작성**

`crawler/src/main/java/com/celfit/crawler/enrichment/adapter/out/anthropic/AnthropicCommentClassifier.java`:

```java
package com.celfit.crawler.enrichment.adapter.out.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.celfit.crawler.enrichment.application.port.out.CommentClassifierPort;
import com.celfit.crawler.enrichment.config.EnrichmentProperties;
import com.celfit.crawler.enrichment.domain.SentimentCategory;
import com.fasterxml.jackson.annotation.JsonPropertyDescription; // ⚠️ Jackson 2(SDK 소유) — Spring의 tools.jackson(3) 아님
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AnthropicCommentClassifier implements CommentClassifierPort {

    private final AnthropicClient client;
    private final EnrichmentProperties props;

    public AnthropicCommentClassifier(AnthropicClient client, EnrichmentProperties props) {
        this.client = client;
        this.props = props;
    }

    // ── structured outputs 스키마 POJO (victools가 이 클래스에서 JSON 스키마를 생성) ──
    public enum Sentiment { POSITIVE, NEUTRAL, NEGATIVE, IRRELEVANT }

    public static class CommentResult {
        @JsonPropertyDescription("입력의 [id] 값을 그대로 반향한다")
        public long id;
        @JsonPropertyDescription("먼저 category를 판정한다. 제품/사용경험과 무관하거나 스팸·광고·봇이면 IRRELEVANT")
        public Sentiment category;
        @JsonPropertyDescription("구매/재구매/구매처 문의 의도가 있으면 true. IRRELEVANT면 false")
        public boolean purchaseIntent;
        @JsonPropertyDescription("반응 키워드(통제 어휘, 최대 3개). IRRELEVANT면 빈 배열")
        public List<String> keywords;
    }

    public static class BatchClassification {
        @JsonPropertyDescription("입력 댓글 각각에 대한 분류 결과")
        public List<CommentResult> results;
    }

    @Override
    public List<Classification> classify(PostContext post, List<CommentToClassify> comments) {
        // ⚠️ 아래 API는 조사(§3a, StructuredOutputsExample.java)에서 확인:
        //   .outputConfig(Class) → 반환타입이 StructuredMessageCreateParams<T>로 바뀜,
        //   create(params).content().stream() ... 의 두 번째 .text()는 파싱된 POJO(T)를 반환(String 아님).
        //   .system(...) / .maxTokens(...) / .addUserMessage(...) 는 표준 빌더. 문자열 .model(...)도 허용(§8).
        //   최초 컴파일에서 cannot find symbol이면 anthropic-java 2.48.0의
        //   com.anthropic.models.messages.MessageCreateParams / StructuredMessageCreateParams 를 확인.
        StructuredMessageCreateParams<BatchClassification> params = MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(4096)
                .system(systemPrompt())
                .addUserMessage(userMessage(post, comments))
                .outputConfig(BatchClassification.class)
                .build();

        List<Classification> out = new ArrayList<>();
        client.messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())   // Optional<StructuredTextBlock<T>>
                .map(tb -> tb.text())                       // ★ 두 번째 .text() = BatchClassification (POJO)
                .filter(bc -> bc.results != null)
                .forEach(bc -> {
                    for (CommentResult r : bc.results) {
                        out.add(new Classification(
                                r.id,
                                SentimentCategory.valueOf(r.category.name()),
                                r.purchaseIntent,
                                r.keywords == null ? List.of() : r.keywords));
                    }
                });
        return out;
    }

    private String systemPrompt() {
        return """
                너는 한국어 인스타그램 뷰티 게시물 댓글 분류기다. 각 댓글을 아래 절차로 분류한다.
                1) category를 먼저 판정한다: POSITIVE(호감/칭찬), NEUTRAL(중립/질문), NEGATIVE(불만/비판),
                   IRRELEVANT(스팸·광고·봇·제품 무관 통합). 제품/사용경험과 무관하면 무조건 IRRELEVANT.
                2) category=IRRELEVANT이면 purchase_intent=false, keywords=[]로 고정한다.
                3) purchase_intent: 구매/재구매/구매처 문의 의도가 드러나면 true.
                4) keywords: 아래 통제 어휘에서만, 최대 3개. 해당 없으면 빈 배열.
                   [발림성,제형,흡수력,밀착력,지속력,커버력,발색,보습,유분감,건조함,자극,트러블,향,
                    가격,가성비,색상,톤,퍼스널컬러,용기,용량,미백,주름개선,진정,각질,건성,지성,복합성,민감성]
                JSON 스키마에 맞는 결과만 출력하고 설명·서문은 쓰지 않는다.
                """;
    }

    private String userMessage(PostContext post, List<CommentToClassify> comments) {
        StringBuilder sb = new StringBuilder();
        sb.append("게시물 캡션(참고 문맥, 분류 대상 아님): ")
          .append(post.caption() == null ? "(없음)" : truncate(post.caption(), 200)).append('\n');
        sb.append("제품 카테고리: ").append(post.category() == null ? "(미상)" : post.category()).append('\n');
        sb.append("아래 각 댓글을 분류하라. [] 안 숫자가 id다. id를 결과에 그대로 반향하라.\n");
        for (CommentToClassify c : comments) {
            sb.append('[').append(c.id()).append("] ")
              .append(c.text() == null ? "" : c.text().replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }

    private String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
```

- [ ] **Step 2: 컴파일 확정** (핵심 — 실 API 미호출)

Run: `./gradlew :crawler:compileJava`
Expected: `BUILD SUCCESSFUL`.
- `cannot find symbol: StructuredMessageCreateParams` → 조사 §3a의 import 경로(`com.anthropic.models.messages.*`)와 2.48.0 소스의 `StructuredOutputsExample.java`를 대조.
- `.outputConfig`/`.system`/`.model`/두 번째 `.text()` 관련 에러 → 같은 예제 파일의 정확한 체인을 그대로 따라 수정.
- `AnthropicClient`/`AnthropicOkHttpClient` import 에러 → `com.anthropic.client.AnthropicClient`, `com.anthropic.client.okhttp.AnthropicOkHttpClient` 확인.

- [ ] **Step 3: 전체 crawler 테스트 회귀** (fake가 @Primary라 실 어댑터는 주입되지 않음)

Run: `./gradlew :crawler:test`
Expected: `BUILD SUCCESSFUL` — Task 1·2·3·5 테스트 전부 그린. 실 어댑터는 컨텍스트에 빈으로 존재하지만(AnthropicClient는 test-key로 빌드) fake가 포트 주입을 대체하므로 호출되지 않는다.

- [ ] **Step 4: Commit**

```bash
git add crawler/src/main/java/com/celfit/crawler/enrichment/adapter/out/anthropic/AnthropicCommentClassifier.java
git commit -m "feat(enrichment): Anthropic structured-output 댓글 분류 어댑터 (GA 경로)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: 수동 트리거(포트/서비스/컨트롤러) + 스케줄러 + ApiTest

**Files:**
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/application/port/in/AnalyzeCommentsUseCase.java`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/application/service/EnrichmentService.java`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/adapter/in/web/EnrichmentController.java`
- Create: `crawler/src/main/java/com/celfit/crawler/enrichment/adapter/in/scheduler/EnrichmentScheduleRunner.java`
- Test: `crawler/src/test/java/com/celfit/crawler/enrichment/adapter/in/web/EnrichmentApiTest.java`

- [ ] **Step 1: 실패하는 API 테스트 작성** (JobApiTest 패턴 — SyncTaskExecutor로 결정화)

`crawler/src/test/java/com/celfit/crawler/enrichment/adapter/in/web/EnrichmentApiTest.java`:

```java
package com.celfit.crawler.enrichment.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.enrichment.FakeCommentClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Import(EnrichmentApiTest.Config.class)
@Transactional  // SyncTaskExecutor라 잡이 같은 스레드에서 돌아 테스트 tx에 합류 → 롤백으로 DB 오염 방지
class EnrichmentApiTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary FakeApifyRunner fakeApifyRunner() { return new FakeApifyRunner(); }
        @Bean @Primary FakeCommentClassifier fakeCommentClassifier() { return new FakeCommentClassifier(); }
        @Bean("jobTaskExecutor") @Primary
        TaskExecutor syncJobExecutor() { return new SyncTaskExecutor(); }  // 트리거 동기화
    }

    @Autowired MockMvc mvc;

    @Test
    void analyze_트리거는_202이고_대상이_없으면_바로_끝난다() throws Exception {
        // 미분석 댓글이 없어 잡은 즉시 종료. 동기 실행이라 202 반환 시점에 이미 끝나 있다.
        mvc.perform(post("/admin/enrichment/analyze"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("accepted"));
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일/404 실패 확인**

Run: `./gradlew :crawler:test --tests '*EnrichmentApiTest*'`
Expected: FAIL — `EnrichmentController` 심볼 없음(컴파일 에러)

- [ ] **Step 3: UseCase 포트 작성**

`crawler/src/main/java/com/celfit/crawler/enrichment/application/port/in/AnalyzeCommentsUseCase.java`:

```java
package com.celfit.crawler.enrichment.application.port.in;

public interface AnalyzeCommentsUseCase {

    TriggerResult trigger();

    enum TriggerResult { ACCEPTED, BUSY }
}
```

- [ ] **Step 4: EnrichmentService 작성** (비동기 + 인프로세스 락)

`crawler/src/main/java/com/celfit/crawler/enrichment/application/service/EnrichmentService.java`:

```java
package com.celfit.crawler.enrichment.application.service;

import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.enrichment.application.port.in.AnalyzeCommentsUseCase;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/** enrichment 잡의 비동기 트리거 + 동시 실행 방지(단일 인스턴스 전제, JobService 패턴 축소판). */
@Service
public class EnrichmentService implements AnalyzeCommentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);

    private final EnrichmentJob job;
    private final TaskExecutor taskExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EnrichmentService(EnrichmentJob job,
                             @Qualifier("jobTaskExecutor") TaskExecutor taskExecutor) {
        this.job = job;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public TriggerResult trigger() {
        if (!running.compareAndSet(false, true)) {
            return TriggerResult.BUSY;
        }
        taskExecutor.execute(() -> {
            try {
                log.info("enrichment 완료: {}", job.run(TriggerType.MANUAL));
            } catch (Exception e) {
                log.error("enrichment 잡 실패", e);
            } finally {
                running.set(false);
            }
        });
        return TriggerResult.ACCEPTED;
    }
}
```

- [ ] **Step 5: EnrichmentController 작성**

`crawler/src/main/java/com/celfit/crawler/enrichment/adapter/in/web/EnrichmentController.java`:

```java
package com.celfit.crawler.enrichment.adapter.in.web;

import com.celfit.crawler.enrichment.application.port.in.AnalyzeCommentsUseCase;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/enrichment")
public class EnrichmentController {

    private final AnalyzeCommentsUseCase useCase;

    public EnrichmentController(AnalyzeCommentsUseCase useCase) {
        this.useCase = useCase;
    }

    /** 댓글 분석 파이프라인 수동 트리거. 비동기 실행 후 즉시 반환. */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, String>> analyze() {
        return switch (useCase.trigger()) {
            case ACCEPTED -> ResponseEntity.accepted().body(Map.of("result", "accepted"));
            case BUSY -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("result", "busy"));
        };
    }
}
```

- [ ] **Step 6: 스케줄러 작성** (@ConditionalOnProperty — 기본 비활성)

`crawler/src/main/java/com/celfit/crawler/enrichment/adapter/in/scheduler/EnrichmentScheduleRunner.java`:

```java
package com.celfit.crawler.enrichment.adapter.in.scheduler;

import com.celfit.crawler.enrichment.application.port.in.AnalyzeCommentsUseCase;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 스케줄 트리거 — crawler.enrichment.schedule.enabled=true일 때만 활성. 초기 운영은 수동. */
@Component
@ConditionalOnProperty(prefix = "crawler.enrichment.schedule", name = "enabled", havingValue = "true")
public class EnrichmentScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentScheduleRunner.class);

    private final AnalyzeCommentsUseCase useCase;

    public EnrichmentScheduleRunner(AnalyzeCommentsUseCase useCase) {
        this.useCase = useCase;
    }

    /** 오설정 백스톱: 스케줄이 켜진 채 부팅되면 비용 유발 경로가 자동으로 도는 것이므로 경고를 남긴다. */
    @PostConstruct
    void warnEnabled() {
        log.warn("⚠️ enrichment 스케줄이 활성화되어 있습니다 — 유료 LLM 호출이 자동 실행됩니다. "
                + "batch-limit(≤500)와 model 설정을 확인하고, 켜기 전 dry-run 선점검을 권장합니다.");
    }

    @Scheduled(cron = "${crawler.enrichment.schedule.analyze-cron}")
    void analyze() {
        log.info("스케줄 enrichment: {}", useCase.trigger());
    }
}
```

- [ ] **Step 7: 테스트 실행 — 통과 확인**

Run: `./gradlew :crawler:test --tests '*EnrichmentApiTest*'`
Expected: PASS (202 + `result=accepted`). SyncTaskExecutor라 잡이 동기 실행되고, 미분석 댓글이 없어 즉시 종료.

- [ ] **Step 8: Commit**

```bash
git add crawler/src/main/java/com/celfit/crawler/enrichment/application/port/in/AnalyzeCommentsUseCase.java \
  crawler/src/main/java/com/celfit/crawler/enrichment/application/service/EnrichmentService.java \
  crawler/src/main/java/com/celfit/crawler/enrichment/adapter/in/web/EnrichmentController.java \
  crawler/src/main/java/com/celfit/crawler/enrichment/adapter/in/scheduler/EnrichmentScheduleRunner.java \
  crawler/src/test/java/com/celfit/crawler/enrichment/adapter/in/web/EnrichmentApiTest.java
git commit -m "feat(enrichment): 수동 트리거(REST+비동기 서비스+락) + 스케줄러

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: analytics 뷰 11_comment_analysis.sql + 테스트 + 미러 등록

**Files:**
- Create: `analytics/views/11_comment_analysis.sql`
- Test: `analytics/test/11_comment_analysis.test.sql`
- Modify: `analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java`

- [ ] **Step 0: 공유 compose DB에 crawler V7 선(先)적용** (하니스 선행 조건 — 없으면 하니스 전체가 죽는다)

⚠️ **근거(직접 확인):** `analytics/test/run.sh`는 어떤 테스트를 돌리기 전에 `views/*.sql`을 **전부** `ON_ERROR_STOP=1`로 `crawler-postgres-1`의 `crawler` DB에 적용한다. 새 뷰 10은 `CREATE OR REPLACE VIEW ... FROM comment_analysis`인데, 이 테이블은 crawler Flyway **V7**이 만든다. 그런데 확인 결과 **`crawler-postgres-1`은 V7이 아직 미적용(Flyway V6에 머묾)**이다 — Tasks 1~7은 휘발성 Testcontainers에서만 V7을 적용했고, compose는 Postgres만 띄우며 crawler 앱을 실행하지 않는다. V7 없이 하니스를 돌리면 뷰 적용 루프가 10에서 `relation "comment_analysis" does not exist`로 죽고 `set -e`로 **기존 00~09 테스트까지 전부 실행 불가(회귀)**가 된다.

```bash
# 확인: 현재 버전 (6이면 V7 미적용)
docker exec -i crawler-postgres-1 psql -U crawler -d crawler -tAc "SELECT max(version) FROM flyway_schema_history"
```

V7을 compose DB에 적용하는 두 경로(택1):

```bash
# (A) 권장: crawler bootRun 1회 — spring-boot-docker-compose가 compose DB에 연결되고 Flyway가 V7 적용
docker compose up -d
export ANTHROPIC_API_KEY=sk-ant-...      # EnrichmentConfig fail-fast 통과용(실호출 없음)
./gradlew :crawler:bootRun &             # Flyway 마이그레이션(V7 포함) 실행
sleep 20 && kill %1 2>/dev/null

# (B) 대안: V7 SQL을 직접 주입
docker exec -i crawler-postgres-1 psql -U crawler -d crawler \
  < crawler/src/main/resources/db/migration/V7__comment_analysis.sql
```

```bash
# 검증: 이제 comment_analysis 테이블이 존재
docker exec -i crawler-postgres-1 psql -U crawler -d crawler -c "\d comment_analysis" >/dev/null && echo "V7 OK"
```

- [ ] **Step 1: 실패하는 SQL 테스트 작성** (dummy.sql에 comment_analysis 없음 → 트랜잭션 내 명시 id로 픽스처 INSERT)

`analytics/test/11_comment_analysis.test.sql`:

```sql
-- dummy.sql은 comment_analysis 데이터가 없다. 비율 검증에 표본이 부족하므로 9101(dummy_c1)에
-- 분석용 raw_comment를 '명시 id'로 추가하고 그 id로 comment_analysis를 채운다.
-- (seed/dummy.sql은 절대 수정하지 않는다 — 기존 00~09 테스트 assert가 깨진다.)
-- 분포: POSITIVE 6, NEUTRAL 2, NEGATIVE 1, IRRELEVANT 1 → total 10, relevant 9, 구매의도 2.
-- 키워드(relevant만): 발림성 4, 가격 2, 향 2, 건성 1. IRRELEVANT 키워드는 칩에서 제외.
DELETE FROM app_setting WHERE key = 'analytics.keyword-chip-limit';  -- 기본값(8) 강제

INSERT INTO raw_comment(id, content_id, crawl_run_id, payload, captured_at) VALUES
 (90001,9101,9990,'{"ownerUsername":"u1","text":"a","timestamp":"2026-06-04T09:00:00Z"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (90002,9101,9990,'{"ownerUsername":"u2","text":"b","timestamp":"2026-06-04T09:00:00Z"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (90003,9101,9990,'{"ownerUsername":"u3","text":"c","timestamp":"2026-06-04T09:00:00Z"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (90004,9101,9990,'{"ownerUsername":"u4","text":"d","timestamp":"2026-06-04T09:00:00Z"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (90005,9101,9990,'{"ownerUsername":"u5","text":"e","timestamp":"2026-06-04T09:00:00Z"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (90006,9101,9990,'{"ownerUsername":"u6","text":"f","timestamp":"2026-06-04T09:00:00Z"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (90007,9101,9990,'{"ownerUsername":"u7","text":"g","timestamp":"2026-06-04T09:00:00Z"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (90008,9101,9990,'{"ownerUsername":"u8","text":"h","timestamp":"2026-06-04T09:00:00Z"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (90009,9101,9990,'{"ownerUsername":"u9","text":"i","timestamp":"2026-06-04T09:00:00Z"}'::jsonb, timestamptz '2026-06-04 09:00:00+09'),
 (90010,9101,9990,'{"ownerUsername":"u10","text":"j","timestamp":"2026-06-04T09:00:00Z"}'::jsonb, timestamptz '2026-06-04 09:00:00+09');

INSERT INTO comment_analysis(comment_id, content_id, category, purchase_intent, keywords, model, prompt_version, analyzed_at) VALUES
 (90001,9101,'POSITIVE',  true,  '["발림성"]'::jsonb,        'claude-opus-4-8', '2026-07-10a', now()),
 (90002,9101,'POSITIVE',  false, '["발림성","향"]'::jsonb,   'claude-opus-4-8', '2026-07-10a', now()),
 (90003,9101,'POSITIVE',  false, '["발림성","가격"]'::jsonb, 'claude-opus-4-8', '2026-07-10a', now()),
 (90004,9101,'POSITIVE',  true,  '["발림성"]'::jsonb,        'claude-opus-4-8', '2026-07-10a', now()),
 (90005,9101,'POSITIVE',  false, '["가격"]'::jsonb,          'claude-opus-4-8', '2026-07-10a', now()),
 (90006,9101,'POSITIVE',  false, '["향"]'::jsonb,            'claude-opus-4-8', '2026-07-10a', now()),
 (90007,9101,'NEUTRAL',   false, '["건성"]'::jsonb,          'claude-opus-4-8', '2026-07-10a', now()),
 (90008,9101,'NEUTRAL',   false, '[]'::jsonb,               'claude-opus-4-8', '2026-07-10a', now()),
 (90009,9101,'NEGATIVE',  false, '[]'::jsonb,               'claude-opus-4-8', '2026-07-10a', now()),
 (90010,9101,'IRRELEVANT',false, '[]'::jsonb,               'claude-opus-4-8', '2026-07-10a', now());

DO $$
BEGIN
  -- 감성 카운트(분자·분모 원값)
  ASSERT (SELECT analyzed_count      FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 10, 'analyzed != 10';
  ASSERT (SELECT positive_count      FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 6, 'positive != 6';
  ASSERT (SELECT neutral_count       FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 2, 'neutral != 2';
  ASSERT (SELECT negative_count      FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 1, 'negative != 1';
  ASSERT (SELECT irrelevant_count    FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 1, 'irrelevant != 1';
  ASSERT (SELECT relevant_count      FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 9, 'relevant != 9';
  -- 구매의도 카운트·비율(전체 기준 = 스팸 포함, 목업 기본)
  ASSERT (SELECT purchase_intent_count FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 2, 'intent != 2';
  ASSERT (SELECT purchase_intent_ratio FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 0.2000, 'intent ratio != 0.2';
  -- 반응 키워드 칩(빈도 상위, IRRELEVANT 제외): 발림성 4, 가격 2, 향 2, 건성 1
  ASSERT (SELECT keyword_chips->0->>'keyword' FROM analytics.v_post_comment_analysis WHERE content_id=9101) = '발림성', 'top chip != 발림성';
  ASSERT (SELECT (keyword_chips->0->>'count')::int FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 4, 'top chip count != 4';
  ASSERT (SELECT jsonb_array_length(keyword_chips) FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 4, 'chip len != 4';
  -- short_code 노출(was가 이 키로 조회)
  ASSERT (SELECT short_code FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 'dummy_c1', 'short_code wrong';
END $$;

-- 칩 상한 app_setting 검증: limit=2면 상위 2개만
INSERT INTO app_setting(key, value) VALUES ('analytics.keyword-chip-limit', '2');
DO $$
BEGIN
  ASSERT (SELECT jsonb_array_length(keyword_chips) FROM analytics.v_post_comment_analysis WHERE content_id=9101) = 2, 'chip limit 2 failed';
  ASSERT (SELECT keyword_chips->1->>'keyword' FROM analytics.v_post_comment_analysis WHERE content_id=9101) = '가격', '2nd chip != 가격';
END $$;
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd analytics && ./test/run.sh test/11_comment_analysis.test.sql`
Expected: FAIL — `relation "analytics.v_post_comment_analysis" does not exist`.
> 전제: **Step 0으로 V7이 적용돼 `comment_analysis` 테이블이 존재**해야 이 지점까지 온다. Step 0을 건너뛰면 실패 사유가 뷰가 아니라 픽스처 `INSERT INTO comment_analysis` 단계의 `relation "comment_analysis" does not exist`로 **더 앞에서** 죽는다(그리고 run.sh의 뷰 적용 루프 자체가 view 10 파일 생성 후엔 comment_analysis 부재로 하니스 전체를 중단시킨다). 여기서 기대하는 실패는 '아직 안 만든 뷰'다.

- [ ] **Step 3: 뷰 작성**

`analytics/views/11_comment_analysis.sql`:

```sql
-- 그룹 10: 댓글 반응 분석 (LLM 결과 집계). 게시물 1건 = 1행.
-- 감성 4분류 카운트(분자: positive/neutral/negative/irrelevant, 분모 원값: analyzed_count=total, relevant),
-- 구매의도 카운트·비율, 반응 키워드 상위 N 칩.
-- ▸ 분모 정의: purchase_intent_ratio = purchase_intent_count / analyzed_count.
--   분모는 '전체 분석 수(스팸 IRRELEVANT 포함)' = analyzed_count(목업 기본, 예 27/214식 total 기준).
--   프론트가 스팸 제외 분모를 원하면 relevant_count로 재계산할 수 있게 count 원값을 모두 노출한다.
-- ▸ 칩 집계 단위: '언급 댓글 수'(mention_count = 그 키워드를 단 댓글의 개수). PostAnalyzer.capKeywords가
--   댓글별로 distinct+최대 3개로 캡하므로 한 댓글은 한 키워드에 최대 1표 → count(*)가 곧 언급 '댓글 수'다
--   (한 댓글 내 중복 언급이 표를 부풀리지 않음). '발림성 4' = 발림성을 언급한 댓글 4개.
-- ▸ 동의어 병합: 키워드는 통제 어휘(28개 enum)로만 산출되므로 LLM이 자유표현을 사전값으로 정규화한다.
--   따라서 뷰 단계의 사후 동의어 병합은 불필요(어휘 자체가 표준형).
-- 키워드 칩은 IRRELEVANT(스팸/무관) 댓글을 제외하고 빈도순 상위 N을 jsonb 배열로 낸다.
-- N = app_setting 'analytics.keyword-chip-limit'(기본 8). 미러는 이 뷰(칩=jsonb)만 보므로
-- comment_analysis.keywords의 저장 배열 형식(jsonb)은 미러 DDL에 노출되지 않는다.
CREATE OR REPLACE VIEW analytics.v_post_comment_analysis AS
WITH counts AS (
  SELECT content_id,
         count(*)                                       AS analyzed_count,
         count(*) FILTER (WHERE category='POSITIVE')    AS positive_count,
         count(*) FILTER (WHERE category='NEUTRAL')     AS neutral_count,
         count(*) FILTER (WHERE category='NEGATIVE')    AS negative_count,
         count(*) FILTER (WHERE category='IRRELEVANT')  AS irrelevant_count,
         count(*) FILTER (WHERE purchase_intent)        AS purchase_intent_count
  FROM comment_analysis
  GROUP BY content_id
),
kw AS (
  SELECT ca.content_id, k.keyword, count(*) AS mention_count
  FROM comment_analysis ca
  CROSS JOIN LATERAL jsonb_array_elements_text(
    CASE WHEN jsonb_typeof(ca.keywords)='array' THEN ca.keywords ELSE '[]'::jsonb END) AS k(keyword)
  WHERE ca.category <> 'IRRELEVANT'
  GROUP BY ca.content_id, k.keyword
),
kw_ranked AS (
  SELECT content_id, keyword, mention_count,
         row_number() OVER (PARTITION BY content_id ORDER BY mention_count DESC, keyword ASC) AS rk
  FROM kw
),
chips AS (
  SELECT content_id,
         jsonb_agg(jsonb_build_object('keyword', keyword, 'count', mention_count)
                   ORDER BY mention_count DESC, keyword ASC) AS keyword_chips
  FROM kw_ranked
  WHERE rk <= COALESCE((SELECT value::int FROM app_setting WHERE key='analytics.keyword-chip-limit'), 8)
  GROUP BY content_id
)
SELECT
  c.content_id,
  ct.short_code,
  c.analyzed_count,
  c.positive_count,
  c.neutral_count,
  c.negative_count,
  c.irrelevant_count,
  (c.positive_count + c.neutral_count + c.negative_count) AS relevant_count,
  c.purchase_intent_count,
  round(c.purchase_intent_count::numeric / NULLIF(c.analyzed_count, 0), 4) AS purchase_intent_ratio,
  COALESCE(ch.keyword_chips, '[]'::jsonb) AS keyword_chips
FROM counts c
JOIN content ct ON ct.id = c.content_id
LEFT JOIN chips ch ON ch.content_id = c.content_id;
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd analytics && ./test/run.sh test/11_comment_analysis.test.sql`
Expected: `PASS: test/11_comment_analysis.test.sql` → `ALL GREEN`

- [ ] **Step 5: 전체 회귀** (신규 뷰가 기존 뷰 미변경)

Run: `cd analytics && ./test/run.sh`
Expected: 모든 테스트 `PASS` → `ALL GREEN`

- [ ] **Step 6: MaterializationService에 미러 등록**

`MaterializationService.java`의 `VIEW_MAPPINGS` 리스트 **마지막 항목 뒤**에 추가한다. Plan 1 미병합이면 현재 마지막은 `v_hashtag_performance`, 병합됐으면 `v_post_detail`이다 — 어느 쪽이든 그 뒤에 붙이고 닫는 괄호를 옮긴다:

```java
			new ViewMapping("v_hashtag_performance", "hashtag_performance"),
			new ViewMapping("v_post_comment_analysis", "post_comment_analysis"));
```

(Plan 1이 병합돼 있으면:)

```java
			new ViewMapping("v_post_detail", "post_detail"),
			new ViewMapping("v_post_comment_analysis", "post_comment_analysis"));
```

- [ ] **Step 7: 컴파일 + 로컬 미러 검증**

```bash
./gradlew :analytics:compileJava
docker compose up -d
cd analytics && ./test/run.sh && cd ..     # 뷰 00→10 적용 (Step 0의 V7 선적용 전제)
./gradlew :analytics:bootRun               # analysis DB로 미러(완료 후 종료)
docker exec -i crawler-postgres-1 psql -U crawler -d analysis \
  -c "SELECT column_name, data_type FROM information_schema.columns WHERE table_name='post_comment_analysis' ORDER BY ordinal_position;"
docker exec -i crawler-postgres-1 psql -U crawler -d analysis \
  -c "SELECT count(*) FROM post_comment_analysis WHERE keyword_chips IS NOT NULL;"
```

Expected(하드 게이트): `BUILD SUCCESSFUL`. `post_comment_analysis` 테이블 생성, `keyword_chips` 컬럼이 **`jsonb`**, 그리고 **행 수 > 0**. analytics 로그에 `materialized post_comment_analysis: N rows`.
> ⚠️ **최초 jsonb 미러 선례:** `keyword_chips`(jsonb)는 MaterializationService를 통과하는 **첫 jsonb 컬럼**이다(현행 미러 9개 뷰 중 jsonb 방출 뷰 없음). 미러 경로(`getColumnTypeName='jsonb'`→CREATE TABLE, `ColumnMapRowMapper`가 PGobject로 읽어 batchUpdate로 재삽입)는 기계적으로 건전하나 회귀 선례가 없으므로 **이 Step을 하드 게이트로 둔다**. `setObject(PGobject)`→jsonb 재삽입이 실패하면 INSERT에서 값을 감싸거나 `::jsonb` 캐스트를 넣는다(그 외엔 코드 변경 불필요). 조사 §9: jsonb 라운드트립은 원리상 안전.

- [ ] **Step 8: Commit**

```bash
git add analytics/views/11_comment_analysis.sql analytics/test/11_comment_analysis.test.sql \
  analytics/src/main/java/com/celfit/analytics/materialize/MaterializationService.java
git commit -m "feat(analytics): 댓글 반응 집계 뷰(감성·구매의도·키워드 칩) + analysis 미러

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: was — 드로어 응답에 commentAnalysis 블록 additive 추가

> **의존성:** Plan 1(`was/.../postdetail/*`, `was/.../IntegrationTest.java`, was `build.gradle`의 testcontainers)이 **먼저 병합돼 있어야** 이 Task를 실행할 수 있다.
>
> **설계 판단(별도 조회 vs 뷰 확장):** Plan 1의 `post_detail` 뷰/미러/`PostDetailRow`를 확장하지 않고 **별도 조회로 조립**한다. 근거: (a) `PostDetailRow`는 `post_detail` 미러와 1:1 매핑(테스트가 정확한 컬럼 DDL을 만든다) — 확장하면 Plan 1의 리포지토리 테스트가 깨진다. (b) 댓글 분석이 아직 없는 게시물은 `commentAnalysis=null`이 자연스러운데, LEFT JOIN 확장은 nullable 컬럼을 본문에 흩뿌린다. 별도 조회는 `Optional.empty` → `null`로 깔끔. (c) 미러 타이밍이 어긋나도 Plan 1의 우아한 저하(warn+빈 값) 컨벤션으로 독립 저하. **비용:** Plan 1의 `PostDetailController` 생성자에 리포지토리 1개가 늘어 그 `@WebMvcTest` 테스트에 목 빈 1줄을 추가한다(기존 assert 전부 유지 → green). 이는 "기존 테스트를 깨지 않는다"의 허용 범위다.
>
> **additive 안전성 검증(Plan 1 문서 실독 확인):** `PostDetailResponse`에 6번째 record 컴포넌트(`commentAnalysis`)를 추가하는 것은 **record 정식 생성자 시그니처를 바꾼다** — 따라서 `new PostDetailResponse(...)`를 호출하는 모든 지점이 컴파일 에러가 나야 한다. Plan 1 문서를 grep한 결과 **정식 생성자 호출 지점은 `PostDetailAssembler.toResponse(PostDetailRow)`의 `return new PostDetailResponse(...)` 단 한 곳**이며(다른 `new PostDetailResponse` 없음), 이 파일을 **같은 Task(Step 8)에서 함께 수정**하므로 깨지지 않는다. Plan 1의 어셈블러/컨트롤러 테스트는 `assembler.toResponse(...)`를 호출할 뿐 **record를 직접 생성하지 않아** 컴포넌트 추가에 안전하다(Plan 1 어셈블러 테스트 `행을_드로어_블록_구조로_조립한다`/`해시태그가_null이면...`는 1-arg 경로를 계속 호출 → green). 또 Plan 1 컨트롤러의 `.map(assembler::toResponse)` 메서드 참조는 **Task 9 Step 10에서 컨트롤러를 통째로 교체**하며 사라지므로, 2-arg 오버로드 추가로 인한 메서드-참조 모호성 문제도 없다(설령 남아도 `Function<PostDetailRow,_>` 문맥에선 1-arg만 적용 가능해 모호하지 않음).

**Files:**
- Create: `was/src/main/java/com/celfit/was/postdetail/CommentAnalysisRow.java`
- Create: `was/src/main/java/com/celfit/was/postdetail/CommentAnalysisRepository.java`
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java` (nested record + 6번째 컴포넌트 추가)
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java` (2-arg 오버로드 + 칩 파싱)
- Modify: `was/src/main/java/com/celfit/was/postdetail/PostDetailController.java` (두 소스 병합)
- Modify: `was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java` (목 빈 1줄 + 신규 테스트)
- Test: `was/src/test/java/com/celfit/was/postdetail/CommentAnalysisRepositoryTest.java`

- [ ] **Step 1: 실패하는 리포지토리 테스트 작성** (미러 테이블 형상 재현)

`was/src/test/java/com/celfit/was/postdetail/CommentAnalysisRepositoryTest.java`:

```java
package com.celfit.was.postdetail;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CommentAnalysisRepositoryTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CommentAnalysisRepository repository;

    @BeforeEach
    void setUpTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS post_comment_analysis");
        jdbcTemplate.execute("""
                CREATE TABLE post_comment_analysis (
                  content_id bigint, short_code text,
                  analyzed_count bigint, positive_count bigint, neutral_count bigint,
                  negative_count bigint, irrelevant_count bigint, relevant_count bigint,
                  purchase_intent_count bigint, purchase_intent_ratio numeric, keyword_chips jsonb
                )
                """);
        // ⚠️ 값은 게시물당 상한 50과 정합(analyzed=50: 32+6+3+9). 목업의 214/발림성41은 pre-cap 예시라
        //    50 상한 확정과 양립 불가 → ≤50 정합 값으로 교체(설계 판단 '목업 볼륨 정합' 참조).
        jdbcTemplate.update("""
                INSERT INTO post_comment_analysis VALUES (
                  1, 'abc123', 50, 32, 6, 3, 9, 41, 7, 0.1400,
                  '[{"keyword":"발림성","count":15},{"keyword":"가격","count":9}]'::jsonb
                )
                """);
    }

    @Test
    void shortCode로_댓글분석_1건을_읽는다() {
        Optional<CommentAnalysisRow> found = repository.findByShortCode("abc123");
        assertThat(found).isPresent();
        CommentAnalysisRow row = found.get();
        assertThat(row.analyzedCount()).isEqualTo(50L);
        assertThat(row.positiveCount()).isEqualTo(32L);
        assertThat(row.relevantCount()).isEqualTo(41L);
        assertThat(row.purchaseIntentCount()).isEqualTo(7L);
        assertThat(row.purchaseIntentRatio()).isEqualByComparingTo(new BigDecimal("0.1400"));
        assertThat(row.keywordChipsJson()).contains("발림성");
    }

    @Test
    void 없는_shortCode면_empty를_반환한다() {
        assertThat(repository.findByShortCode("nope")).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :was:test --tests '*CommentAnalysisRepositoryTest*'`
Expected: FAIL — `CommentAnalysisRow`/`CommentAnalysisRepository` 심볼 없음

- [ ] **Step 3: Row + Repository 작성**

`was/src/main/java/com/celfit/was/postdetail/CommentAnalysisRow.java`:

```java
package com.celfit.was.postdetail;

import java.math.BigDecimal;

/** analysis DB의 post_comment_analysis 미러 1행. keyword_chips는 ::text로 읽어 어셈블러가 파싱. */
public record CommentAnalysisRow(
        String shortCode,
        Long analyzedCount,
        Long positiveCount,
        Long neutralCount,
        Long negativeCount,
        Long irrelevantCount,
        Long relevantCount,
        Long purchaseIntentCount,
        BigDecimal purchaseIntentRatio,
        String keywordChipsJson) {
}
```

`was/src/main/java/com/celfit/was/postdetail/CommentAnalysisRepository.java`:

```java
package com.celfit.was.postdetail;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CommentAnalysisRepository {

    private static final Logger log = LoggerFactory.getLogger(CommentAnalysisRepository.class);

    private final JdbcClient jdbcClient;

    public CommentAnalysisRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<CommentAnalysisRow> findByShortCode(String shortCode) {
        try {
            return jdbcClient.sql("""
                    SELECT short_code, analyzed_count, positive_count, neutral_count, negative_count,
                           irrelevant_count, relevant_count, purchase_intent_count, purchase_intent_ratio,
                           keyword_chips::text AS keyword_chips_json
                    FROM post_comment_analysis
                    WHERE short_code = :shortCode
                    """)
                    .param("shortCode", shortCode)
                    .query(CommentAnalysisRow.class)
                    .optional();
        } catch (DataAccessException e) {
            // 미러 테이블이 아직 없어도(파이프라인 미실행) 빈 값으로 저하 — 드로어는 commentAnalysis=null
            log.warn("post_comment_analysis 조회 실패, 빈 값으로 대체합니다: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*CommentAnalysisRepositoryTest*'`
Expected: 2 tests PASS

- [ ] **Step 5: PostDetailResponse에 commentAnalysis 컴포넌트 추가** (마지막 위치 — additive)

`was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java`를 아래로 수정한다. **기존 5개 컴포넌트·nested record는 그대로 두고**, 컴포넌트 목록 끝에 `CommentAnalysis commentAnalysis`를 추가하고 nested record `CommentAnalysis`를 추가한다:

```java
package com.celfit.was.postdetail;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 게시물 드로어 v3 응답. 블록 구조가 확정안의 화면 블록과 1:1 대응.
 * commentAnalysis는 Plan 3에서 additive하게 추가된 댓글 반응 블록(파이프라인 미실행 시 null).
 */
public record PostDetailResponse(
        String shortCode,
        Header header,
        Preview preview,
        Performance performance,
        CommentStats commentStats,
        CommentAnalysis commentAnalysis) {

    public record Header(
            String username,
            Long followers,
            Long hitCount,
            Long sampleSize,
            BigDecimal hitRate,
            BigDecimal avgEngagementRate) {
    }

    public record Preview(
            OffsetDateTime uploadedAt,
            Long daysSincePosted,
            String contentFormat,
            BigDecimal videoDurationSeconds,
            Boolean adMarked,
            String caption,
            List<String> hashtags,
            List<String> mentions,
            String originalUrl) {
    }

    public record Performance(
            Long views,
            BigDecimal engagementRate,
            Long likes,
            Long comments,
            BigDecimal followerReachMultiple,
            Benchmark benchmark) {
    }

    public record Benchmark(
            BigDecimal authorAvgViews,
            BigDecimal tierAvgViews,
            BigDecimal categoryAvgViews,
            String tier,
            String mainGroup) {
    }

    public record CommentStats(Long collectedCount) {
    }

    /**
     * 댓글 반응 블록. sentiment는 분자(각 카운트)·분모(total/relevant)를 모두 담아
     * 프론트가 스팸 포함/제외 분모를 자유롭게 전환한다(확정안 미결사항 기본값).
     */
    public record CommentAnalysis(
            Long analyzedCount,
            Sentiment sentiment,
            Long purchaseIntentCount,
            BigDecimal purchaseIntentRatio,
            List<KeywordChip> keywordChips) {

        public record Sentiment(
                Long positive, Long neutral, Long negative, Long irrelevant,
                Long relevant, Long total) {
        }

        public record KeywordChip(String keyword, Long count) {
        }
    }
}
```

- [ ] **Step 6: 실패하는 어셈블러 테스트 추가** (2-arg 매핑 + 칩 파싱)

`was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java`에 **테스트 메서드 추가**(기존 메서드는 1-arg `toResponse(row())`를 계속 호출 → 그대로 green). 아래 import·필드를 추가하고 메서드를 붙인다:

```java
    private CommentAnalysisRow caRow() {
        // ≤50 정합(analyzed=50). 목업 214/41은 pre-cap 예시라 사용 금지.
        return new CommentAnalysisRow(
                "abc123", 50L, 32L, 6L, 3L, 9L, 41L, 7L,
                new java.math.BigDecimal("0.1400"),
                "[{\"keyword\":\"발림성\",\"count\":15},{\"keyword\":\"가격\",\"count\":9}]");
    }

    @Test
    void 댓글분석_행이_있으면_commentAnalysis_블록을_조립한다() {
        PostDetailResponse response = assembler.toResponse(row(), caRow());

        assertThat(response.commentAnalysis().analyzedCount()).isEqualTo(50L);
        assertThat(response.commentAnalysis().sentiment().positive()).isEqualTo(32L);
        assertThat(response.commentAnalysis().sentiment().total()).isEqualTo(50L);
        assertThat(response.commentAnalysis().sentiment().relevant()).isEqualTo(41L);
        assertThat(response.commentAnalysis().purchaseIntentCount()).isEqualTo(7L);
        assertThat(response.commentAnalysis().keywordChips())
                .extracting(PostDetailResponse.CommentAnalysis.KeywordChip::keyword)
                .containsExactly("발림성", "가격");
    }

    @Test
    void 댓글분석_행이_null이면_commentAnalysis도_null이다() {
        PostDetailResponse response = assembler.toResponse(row());  // 1-arg 유지
        assertThat(response.commentAnalysis()).isNull();
    }
```

- [ ] **Step 7: 테스트 실행 — 컴파일/실패 확인**

Run: `./gradlew :was:test --tests '*PostDetailAssemblerTest*'`
Expected: FAIL — `toResponse(row(), caRow())` 2-arg 시그니처 없음(컴파일 에러)

- [ ] **Step 8: 어셈블러에 2-arg 오버로드 + 칩 파싱 추가**

`was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java`를 수정한다. **기존 `toResponse(PostDetailRow)`는 유지**(1-arg 호출자 보존)하되 2-arg로 위임하고, 칩 파싱·CA 매핑을 추가한다. 아래는 수정된 전체 파일:

```java
package com.celfit.was.postdetail;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class PostDetailAssembler {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<PostDetailResponse.CommentAnalysis.KeywordChip>> CHIP_LIST =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PostDetailAssembler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Plan 1 호환 1-arg 경로 — 댓글 분석 없이 조립(commentAnalysis=null). */
    public PostDetailResponse toResponse(PostDetailRow row) {
        return toResponse(row, null);
    }

    /** Plan 3 경로 — 댓글 분석 행이 있으면 commentAnalysis 블록을 붙인다(null이면 생략). */
    public PostDetailResponse toResponse(PostDetailRow row, CommentAnalysisRow caRow) {
        return new PostDetailResponse(
                row.shortCode(),
                new PostDetailResponse.Header(
                        row.ownerUsername(), row.followers(),
                        row.authorHitCount(), row.authorSampleSize(),
                        row.authorHitRate(), row.authorAvgEngagementRate()),
                new PostDetailResponse.Preview(
                        row.uploadedAt(), daysSincePosted(row.uploadedAt()),
                        row.contentFormat(), row.videoDuration(), row.adMarked(),
                        row.caption(), parseStrings(row.hashtagsJson()), parseStrings(row.mentionsJson()),
                        originalUrl(row)),
                new PostDetailResponse.Performance(
                        row.views(), row.engagementRate(), row.likes(), row.commentsCount(),
                        row.followerReachMultiple(),
                        new PostDetailResponse.Benchmark(
                                row.authorAvgViews(), row.tierAvgViews(), row.categoryAvgViews(),
                                row.tier(), row.mainGroup())),
                new PostDetailResponse.CommentStats(row.collectedCommentCount()),
                caRow == null ? null : toCommentAnalysis(caRow));
    }

    private PostDetailResponse.CommentAnalysis toCommentAnalysis(CommentAnalysisRow r) {
        return new PostDetailResponse.CommentAnalysis(
                r.analyzedCount(),
                new PostDetailResponse.CommentAnalysis.Sentiment(
                        r.positiveCount(), r.neutralCount(), r.negativeCount(),
                        r.irrelevantCount(), r.relevantCount(), r.analyzedCount()),
                r.purchaseIntentCount(),
                r.purchaseIntentRatio(),
                parseChips(r.keywordChipsJson()));
    }

    private Long daysSincePosted(OffsetDateTime uploadedAt) {
        if (uploadedAt == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(uploadedAt, OffsetDateTime.now(clock));
    }

    private List<String> parseStrings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, STRING_LIST);
    }

    private List<PostDetailResponse.CommentAnalysis.KeywordChip> parseChips(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, CHIP_LIST);
    }

    private String originalUrl(PostDetailRow row) {
        String path = "reel".equals(row.contentFormat()) ? "reel" : "p";
        return "https://www.instagram.com/%s/%s/".formatted(path, row.shortCode());
    }
}
```

> ⚠️ Plan 1의 어셈블러가 해시태그/멘션 파싱 메서드를 `parseList`로 불렀다면 이름을 `parseStrings`로 통일하거나(위처럼), 기존 이름을 유지하고 `parseChips`만 추가하라. 어느 쪽이든 **1-arg `toResponse`는 반드시 유지**한다.

- [ ] **Step 9: 어셈블러 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*PostDetailAssemblerTest*'`
Expected: 기존 2개 + 신규 2개 = 4 tests PASS

- [ ] **Step 10: 컨트롤러 병합 + Plan 1 컨트롤러 테스트 목 추가**

`was/src/main/java/com/celfit/was/postdetail/PostDetailController.java`를 수정(리포지토리 2개 주입, 병합 조립):

```java
package com.celfit.was.postdetail;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PostDetailController {

    private final PostDetailRepository repository;
    private final CommentAnalysisRepository commentAnalysisRepository;
    private final PostDetailAssembler assembler;

    public PostDetailController(PostDetailRepository repository,
                                CommentAnalysisRepository commentAnalysisRepository,
                                PostDetailAssembler assembler) {
        this.repository = repository;
        this.commentAnalysisRepository = commentAnalysisRepository;
        this.assembler = assembler;
    }

    @GetMapping("/api/posts/{shortCode}")
    public PostDetailResponse postDetail(@PathVariable String shortCode) {
        PostDetailRow row = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다: " + shortCode));
        CommentAnalysisRow ca = commentAnalysisRepository.findByShortCode(shortCode).orElse(null);
        return assembler.toResponse(row, ca);
    }
}
```

`was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java`를 수정: **기존 assert는 전부 유지**하고, 아래 목 빈 1개 + `given(...)` 스텁 + 신규 테스트 메서드를 추가한다.

목 빈 추가(클래스 필드):

```java
    @MockitoBean
    CommentAnalysisRepository commentAnalysisRepository;
```

기존 `게시물_상세를_JSON으로_반환한다` 테스트에 CA 스텁 1줄 추가(commentAnalysis=null로 두어 기존 assert 불변):

```java
        given(commentAnalysisRepository.findByShortCode("abc123")).willReturn(java.util.Optional.empty());
```

신규 테스트 메서드 추가:

```java
    @Test
    void 댓글분석이_있으면_commentAnalysis_블록이_응답에_포함된다() throws Exception {
        given(repository.findByShortCode("abc123")).willReturn(java.util.Optional.of(row()));
        given(commentAnalysisRepository.findByShortCode("abc123")).willReturn(java.util.Optional.of(
                new CommentAnalysisRow("abc123", 50L, 32L, 6L, 3L, 9L, 41L, 7L,
                        new java.math.BigDecimal("0.1400"),
                        "[{\"keyword\":\"발림성\",\"count\":15}]")));

        mockMvc.perform(get("/api/posts/abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentAnalysis.analyzedCount").value(50))
                .andExpect(jsonPath("$.commentAnalysis.sentiment.positive").value(32))
                .andExpect(jsonPath("$.commentAnalysis.sentiment.total").value(50))
                .andExpect(jsonPath("$.commentAnalysis.purchaseIntentCount").value(7))
                .andExpect(jsonPath("$.commentAnalysis.keywordChips[0].keyword").value("발림성"));
    }
```

> `없는_게시물이면_404` 테스트는 repository가 empty를 반환하므로 CA 스텁 없이도 그대로 통과(404가 CA 조회 전에 던져짐).

- [ ] **Step 11: 컨트롤러 테스트 실행 — 통과 확인**

Run: `./gradlew :was:test --tests '*PostDetailControllerTest*'`
Expected: 기존 2개 + 신규 1개 = 3 tests PASS (기존 assert 전부 유지 → Plan 1 회귀 없음)

- [ ] **Step 12: was 전체 테스트**

Run: `./gradlew :was:test`
Expected: `BUILD SUCCESSFUL` — Plan 1 테스트(Repository·Assembler·Controller) + Plan 3 신규 테스트 전부 PASS

- [ ] **Step 13: Commit**

```bash
git add was/src/main/java/com/celfit/was/postdetail/CommentAnalysisRow.java \
  was/src/main/java/com/celfit/was/postdetail/CommentAnalysisRepository.java \
  was/src/main/java/com/celfit/was/postdetail/PostDetailResponse.java \
  was/src/main/java/com/celfit/was/postdetail/PostDetailAssembler.java \
  was/src/main/java/com/celfit/was/postdetail/PostDetailController.java \
  was/src/test/java/com/celfit/was/postdetail/CommentAnalysisRepositoryTest.java \
  was/src/test/java/com/celfit/was/postdetail/PostDetailAssemblerTest.java \
  was/src/test/java/com/celfit/was/postdetail/PostDetailControllerTest.java
git commit -m "feat(was): 드로어 응답에 commentAnalysis 블록 additive 추가 (별도 조회 조립)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: E2E 검증 (실 API 미호출 — 파이프라인 배선 관통)

**Files:** 없음 (검증만)

- [ ] **Step 1: 전체 빌드 + 테스트**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL` — crawler·analytics·was 전 모듈 그린. (LLM은 전부 fake로 검증됐고 실 API 호출은 없다.)

- [ ] **Step 2: crawler 파이프라인 dry-run 관통** (실 API 미호출, 대상 선정·프리필터만)

```bash
docker compose up -d
# 실 API를 아끼려 dry-run으로 대상 선정·프리필터 경로만 관통 확인
export ANTHROPIC_API_KEY=sk-ant-...    # fail-fast 통과용(dry-run이라 실제 과금 없음)
ENRICHMENT_MODEL=claude-opus-4-8 \
  ./gradlew :crawler:bootRun --args='--crawler.enrichment.dry-run=true' &
sleep 20
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/admin/enrichment/analyze
# 로그에 "enrichment 시작: 대상 게시물 N건 ... dryRun=true" 와 "[dry-run] ... 호출/저장 생략" 확인
kill %1 2>/dev/null
docker exec -i crawler-postgres-1 psql -U crawler -d crawler -tAc "SELECT count(*) FROM comment_analysis"
```

Expected: `202`. dry-run이라 `comment_analysis` 행 수 = 0(저장 생략). 로그에 대상 게시물 수·프리필터 건수.

- [ ] **Step 3: (선택, 유료) 소량 실호출 검증** — 팀 승인 후에만

```bash
# batch-limit=1로 게시물 1건만 실제 분류(과금 최소화). Task 0에서 정한 모델 사용.
ENRICHMENT_MODEL=claude-haiku-4-5 \
  ./gradlew :crawler:bootRun --args='--crawler.enrichment.batch-limit=1' &
sleep 20
curl -s -X POST http://localhost:8080/admin/enrichment/analyze
sleep 30   # 동기 per-post 호출 완료 대기
kill %1 2>/dev/null
docker exec -i crawler-postgres-1 psql -U crawler -d crawler -c \
  "SELECT category, purchase_intent, keywords, model FROM comment_analysis ORDER BY comment_id LIMIT 20"
```

Expected: 1개 게시물의 댓글들이 4분류·구매의도·키워드(jsonb 배열)로 저장됨. `model` = 지정 모델. 스키마 위반 없음.

- [ ] **Step 4: analytics 미러 + was 응답 관통**

```bash
cd analytics && ./test/run.sh && cd ..        # 뷰 00→10 적용
./gradlew :analytics:bootRun                   # analysis DB로 미러
./gradlew :was:bootRun &                       # port 8081
sleep 15
SC=$(docker exec -i crawler-postgres-1 psql -U crawler -d analysis -tAc \
     "SELECT short_code FROM post_comment_analysis LIMIT 1")
curl -s "http://localhost:8081/api/posts/${SC}" | python3 -m json.tool | sed -n '1,80p'
kill %1 2>/dev/null
```

Expected: 응답 JSON에 `commentAnalysis`(analyzedCount·sentiment{positive,neutral,negative,irrelevant,relevant,total}·purchaseIntentCount·purchaseIntentRatio·keywordChips[]) 블록이 채워짐. 분석이 없는 게시물은 `commentAnalysis: null`.

- [ ] **Step 5: 마무리**

```bash
git status   # working tree clean 확인
```

> **배포 메모:** 실서버 순서 = ① crawler 배포(V7 마이그레이션 자동 적용 + enrichment 파이프라인) → ② `POST /admin/enrichment/analyze`로 분류 실행(또는 스케줄 활성화) → ③ `analytics/views/*.sql` 적용 + analytics 모듈 1회 실행(미러) → ④ was 배포. was는 `post_comment_analysis` 미러가 없어도 `commentAnalysis: null`로 우아하게 저하되므로 순서가 어긋나도 500은 나지 않는다.
>
> **비용 통제(중요):** 출하 기본 경로는 **동기·무캐시·무배치·기본 Opus**라 실비가 **≈$61/1k 게시물**(Haiku로 내리면 ≈$12.2/1k)이다. 조사의 **$6.1/1k는 Batches 도입 후에야 도달 가능한 후속 목표치**이며 현재 코드 경로로는 도달 불가(헤드라인 실비로 인용 금지). 통제 절차: (1) 최초엔 `crawler.enrichment.batch-limit`을 작게(예: 20) 두고 스파이크 실측 비용을 확인하며 상향(상한 500 초과는 설정 검증에서 거부됨), (2) **스케줄(`schedule.enabled=true`)을 켜기 전에 반드시 `dry-run=true`로 대상 게시물·토큰을 선점검**한다(스케줄이 켜진 채 부팅되면 부팅 로그에 경고가 남는다), (3) 프롬프트/키워드 사전을 바꾸면 `crawler.enrichment.prompt-version`을 bump해야 재분석이 트리거된다(안 올리면 기존 결과가 그대로 유지됨).

---

## Self-Review 체크 결과

**1. 스펙 커버리지**
- Task 0 = **정확도·비용 스파이크**(opus vs haiku, 합격 게이트, 결정 규칙, 스크래치 스크립트 비커밋 + 결과 md 커밋) ✅
- Anthropic SDK 도입 + `ANTHROPIC_API_KEY` 주입 + `@ConfigurationProperties record`(모델·배치·재시도·타임아웃·dry-run) → Task 1 ✅
- Flyway V7 `comment_analysis`(comment_id PK/FK, content_id, category CHECK, purchase_intent, **keywords jsonb**, model, **prompt_version**, analyzed_at, 인덱스) → Task 2 ✅. **keywords 타입 결정 + 근거**를 문서 상단 판단 1 + Task 2 주석에 명시(jsonb 선택: JPA 일관성 + 미러 비노출) ✅. **raw_comment.id가 bigserial PK임을 psql `\d raw_comment`로 확인** → FK 유효 ✅
- enrichment bounded context(domain / application{service,port.in,port.out} / adapter{out.anthropic, in.scheduler, in.web}) → Task 2·4·5·6·7 ✅ (DDD 패키지 규칙 준수)
- 프리필터(명백 스팸, **고정밀·오탐 방지**) → structured-output 배치 호출 → 저장, 게시물 단위 청킹, 부분 실패, 멱등(`existsByCommentIdAndModelAndPromptVersion`) → Task 3·5·6 ✅
- analytics 뷰 `11_comment_analysis.sql`(감성 4분류 카운트+비율 **원값**, 키워드 상위 N 칩, 구매의도 카운트·비율) + 미러 등록 → Task 8 ✅. **Task 8 Step 0으로 공유 compose DB에 V7 선적용**(하니스 붕괴 방지) ✅
- was 응답 `commentAnalysis` **additive**(별도 조회 vs 뷰 확장 → 별도 조회 선택 + 근거, Plan 1 테스트 green 유지, record 정식 생성자 유일 호출처=어셈블러 동일 Task 수정) → Task 9 ✅
- 감성 분모: 분자(positive/neutral/negative/irrelevant 카운트)·분모(total=analyzedCount, relevant) **원값 모두 응답 포함**; 구매의도 비율 분모=analyzed_count 명시 → Task 8 뷰 + Task 9 Sentiment record ✅
- 키워드 칩 집계 단위='언급 댓글 수'(capKeywords distinct → 댓글당 1표), 동의어는 통제 어휘(enum)로 사전 정규화 → Task 8 뷰 주석 ✅
- 모델 하드코딩 금지(`crawler.enrichment.model` 프로퍼티, haiku 다운그레이드=yml/env 한 줄) → Task 1·6 ✅
- 비용 안전장치(batch-limit + **500 초과 거부** + max-comments-per-call + dry-run + **스케줄 활성 경고 로그** + **스케줄 전 dry-run 필수**; 일일 상한 유보) → 문서 판단 5 + Task 1·5·7·10 ✅
- **비용 현실성:** 출하 경로(동기·무캐시·무배치·Opus) 실비 $61/1k(Haiku $12.2/1k) 명시, $6.1은 Batches 후속 목표치로 강등 → 판단 3·4 + Task 0 Step 4 + Task 10 메모 ✅
- **버전 멱등(조사 §7 부분 반영):** prompt_version을 멱등키에 포함해 프롬프트/사전 변경 시 재분석 트리거; comment_id 단독 PK 덮어쓰기 한계는 트레이드오프로 명시 → 판단 2 + Task 2·5 ✅
- 테스트 전략: LLM은 fake로만(실 API 미호출), 프리필터 파라미터라이즈드, 멱등·부분실패 Testcontainers, 뷰는 `10_*.test.sql`(트랜잭션 내 INSERT 픽스처, dummy.sql 미수정), was는 MockMvc → Task 3·5·8·9 ✅
- Anthropic SDK는 조사 확인 API만 사용, 미확인은 주석 + 컴파일 확정 Step(`./gradlew :crawler:compileJava`, cannot find symbol 시 참조처 명시) → Task 1·6 ✅

**2. 플레이스홀더 스캔**
- "TBD/적절히 처리/Task N과 유사/테스트를 작성하라(코드 없이)" 없음. 모든 코드 블록은 복붙 가능한 완성본.
- 유일한 명시적 미확정 = Anthropic SDK 빌더 메서드(`.timeout`/`.maxRetries`/`.system`/두 번째 `.text()`)와 `List<String>`↔jsonb Hibernate 매핑 — 둘 다 **컴파일/런타임으로 확정하는 Step + 실패 시 대안**을 코드 주석과 Step에 명시(플레이스홀더 아님, 검증 지점).
- Task 0 스파이크 스크립트는 스크래치(비커밋)라 실행 가능한 Python 완성본으로 제공.

**3. 타입 일관성**
- `SentimentCategory{POSITIVE,NEUTRAL,NEGATIVE,IRRELEVANT}` — 엔티티(@Enumerated STRING)·CHECK 제약·포트·어댑터·뷰 FILTER·was Sentiment record 전부 동일 문자열.
- `CommentAnalysis(commentId, contentId, category, purchaseIntent, keywords, model, promptVersion, analyzedAt)` 생성자 = Task 2 정의 = Task 5 `analyses.save(new CommentAnalysis(...))` 2곳(프리필터/LLM) 인자 순서 일치.
- 포트 `CommentClassifierPort.{PostContext(caption,category), CommentToClassify(id,text), Classification(id,category,purchaseIntent,keywords)}` = Fake·PostAnalyzer·AnthropicCommentClassifier 전부 동일.
- `CommentAnalysisRepository.{existsByCommentIdAndModelAndPromptVersion, findByContentId, findContentIdsWithUnanalyzedComments(model, promptVersion, Pageable)}` = Task 2 정의 = Task 5 호출(`PostAnalyzer` 멱등 필터 + `EnrichmentJob` due 조회) 일치.
- `EnrichmentProperties`에 `promptVersion` 추가 = 테스트 assert = main/test yml `prompt-version` = Task 5 `props.promptVersion()` 사용 일치. batch-limit>500 거부 검증은 정식 생성자에.
- `SpamPreFilter.{isObviousSpam(text), reason(text)→Optional<String>}` = Task 3 정의 = Task 5 `preFilter.reason(...)` 감사 로깅 일치.
- 뷰 컬럼(`content_id, short_code, analyzed_count, positive_count, neutral_count, negative_count, irrelevant_count, relevant_count, purchase_intent_count, purchase_intent_ratio, keyword_chips`) = 미러 테이블 = was `CommentAnalysisRow`(shortCode 제외 매핑, keyword_chips→keywordChipsJson) = SQL 테스트 assert = 미러테이블 DDL(Task 9 Step 1) 일치. `analyzed_count`(SQL bigint)→Java `Long`.
- was `PostDetailResponse`는 5→6 컴포넌트로 additive 확장, 1-arg `toResponse` 유지로 Plan 1 어셈블러 테스트 불변. 목업 214는 50 상한과 양립 불가라 was 픽스처는 ≤50 정합 값(analyzed=50)으로 통일.

---

## 검증 리뷰 반영 로그 (blocker/major/minor)

**MAJOR (전부 반영):**
1. **Task 8 하니스 붕괴(V7 미적용):** 공유 `crawler-postgres-1`이 Flyway V6에 머물러 뷰 10(`FROM comment_analysis`)이 하니스 전체를 죽였다 → **Task 8 Step 0**(bootRun/직접 주입으로 V7 선적용) 추가, Step 2 실패 사유 정정. (`psql`로 V6 및 `\d raw_comment` 실측)
2. **버전 멱등 부재(조사 §7 충돌):** model-only 멱등이라 프롬프트/사전 변경 시 재분석 미발동 → `prompt_version` 컬럼 + `(comment_id, model, prompt_version)` 멱등키로 확장(판단 2, Task 1·2·5·8). comment_id 단독 PK 덮어쓰기 한계는 트레이드오프 명시.
3. **목업 214 vs 50 상한 모순:** analyzedCount ≤ 50 구조인데 214를 픽스처에 박음 → 목업 수치 정정 방침 명시 + was 픽스처 전부 ≤50 정합 값으로 교체(판단 '목업 볼륨 정합', Task 9).
4. **스파이크 단일 호출 잘림:** 골드셋 전체를 max_tokens=4096 1회 호출 → 잘림 → **50 청킹 + stop_reason 가드 + 합산**으로 프로덕션 정합(Task 0 Step 3).
5. **비용 오인용:** 출하 경로 실비 $61/1k(Opus)·$12.2/1k(Haiku) 명시, $6.1은 Batches 후속 목표치로 강등, 캐시 미적용 명시(판단 3·4, Task 0 Step 4, Task 10 메모).
6. **게이트 통계력 부족:** n=100으론 하드 게이트 분해 불가 → IRRELEVANT·intent 양성 각 ≥50 확보 + **Wilson CI 하한≥기준선** 판정 + 기준선 역산 근거(Task 0 Step 1·5).
7. **프리필터 과공격:** 맨 `맞팔/선팔/부업/재테크`·정상어 `후불` 오탐 → 하드 정규식에서 제거(LLM 위임) + `reason()` 감사 로깅 + 오탐 회귀 테스트('발림성 최고 맞팔해요','후불 되나요?')(Task 3·5).

**MINOR (반영):**
- 최초 jsonb 미러 하드 게이트화(Task 8 Step 7). / Task 1 빌더 고립 선검증 순서 안전장치(Step 7). / Jackson 2·3 양방향(공유 인프라 영향까지) + crawler 자체 jsonb 엔티티 그린 게이트(Step 8). / 필드순서 트릭 correctness 근거에서 강등(Task 0 Step 5). / 비용 백스톱: batch-limit>500 거부 + 스케줄 활성 경고 + 스케줄 전 dry-run 필수(판단 5, Task 1·7·10).

## 리뷰에서 반영하지 않은 지적

없음 — blocker/major는 전부 반영했고, 제기된 minor(최초 jsonb 미러, 빌더 blast radius, Jackson 단방향, 필드순서 과대포장, 비용 백스톱)도 모두 문서에 명시화했다. 다만 다음은 **의도적으로 축소 반영**했으니 트레이드오프로 남긴다:
- **조사 §7의 완전한 non-overwrite(복합 PK로 model/prompt별 이력 보존):** MVP 범위에서 comment_id 단독 PK를 유지하고 `prompt_version`만 멱등키에 추가했다. 프롬프트/사전 변경 시 재분석 트리거(문제 1)는 완전히 해소되나, model/prompt 교체 시 과거 행 덮어쓰기(문제 2)는 남는다. 근거: 복합 PK로 승격하면 집계 뷰가 '현재 버전'만 골라야 해 뷰·미러·was가 연쇄 변경되어 MVP를 넘어선다. 후속 승격 경로를 판단 2에 명시.
- **keyword_dict_version 별도 컬럼:** 조사 §7은 별도 컬럼을 들지만, 사전은 프롬프트의 일부이므로 `prompt_version` 단일 라벨이 사전 변경까지 커버하도록 folding했다(운영 절차: 사전 변경 시에도 prompt_version bump). 컬럼 수·멱등 메서드명 복잡도를 낮추면서 문제 1을 동일하게 해소.
- **프롬프트 캐시 실제 배선(cache_control):** 비용 절감 후속 과제로 남기고 현재 어댑터엔 넣지 않았다(판단 3에 명시). 지금 반영하면 Batches 재설계와 함께 별도 작업이 필요.
