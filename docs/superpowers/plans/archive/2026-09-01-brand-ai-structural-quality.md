# 브랜드 AI 구조적 품질 개선 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행 완료(2026-09-01) · 스펙: docs/superpowers/specs/2026-09-01-brand-ai-structural-quality-design.md

**Goal:** 조용한 근사를 구조로 차단한다 - sponsorship 축 인자·minSample·서버 강제 caveat·용어 사전·프리셋 verified 플랜·eval 골드셋 러너.

**Architecture:** 전부 was `v1/brandmonitoring/ai/` 패키지 + `was/eval/`(신규 러너). 브랜치 feat/brand-ai-tool-limits-redesign 위에 계속. 각 태스크 TDD, 커밋 단위 분리, push·PR은 마지막에 사용자 지시대로(push만).

**공통 전제:** 작업 디렉토리 `/Users/woomin/Project/hypenow-backend/.worktrees/brand-ai-tool-redesign`. 테스트 전 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`. 스펙 문서를 먼저 읽을 것.

---

### Task 1: sponsorship 필터 + minSample + 서버 강제 caveats (BrandAiToolbox)

**Files:** BrandAiToolbox.java, BrandAiToolSpecs.java, BrandAiToolboxIntegrationTest.java

- [ ] 실패 테스트 먼저(통합 테스트, 기존 시드 헬퍼 사용 - sponsorship 시드는 기존 협찬 관련 시드 방법을 파일에서 확인해 따를 것. 없으면 brand_post_meta.is_paid_partnership 또는 campaign 시드 경로 확인):
  - `aggregate_posts_sponsorship_인자는_협찬_게시물만_모수로_삼는다` (sponsored 2건+organic 3건 시드 → sponsorship="sponsored"면 postCount 2)
  - `list_posts와_search_posts도_sponsorship_인자를_공유한다`
  - `aggregate_posts_minSample은_표본_미달_그룹을_제외하고_filteredOutBySample로_보고한다`
  - `aggregate_posts_keyword_사용시_caveats에_캡션_매칭_고지가_강제된다`
  - `aggregate_posts_소표본_그룹_반환시_caveats에_표본_경고가_강제된다`
- [ ] 구현:
  - resolveWindow에 모델 인자 sponsorship 필터 추가(scope 필터와 별개, equalsIgnoreCase - `BrandSponsorshipClassifier.SPONSORED/ORGANIC/UNKNOWN` 상수 참조, 리터럴 복제 금지). 유효값 밖이면 error 결과("sponsorship은 sponsored·organic·unknown 중 하나").
  - aggregatePosts: `minSample` 파싱(0 이상 클램프) → groupBy 시 정렬 전 `viewsSampleCount < minSample` 그룹 제외, 제외 수를 `filteredOutBySample`(0이면 생략)로.
  - caveats 조립 헬퍼:
    ```java
    /** 서버 강제 caveat(스펙 §4) - 고지를 모델 재량에 맡기지 않는다(Knowing-but-Not-Showing 근거). */
    private static final String CAVEAT_KEYWORD =
        "keyword는 캡션 문자 매칭입니다. 광고·협찬 여부 판정이 아닙니다 - 협찬 여부는 sponsorship 인자를 쓰세요.";
    private static String caveatSmallSample(long count) {
        return "반환된 그룹 중 " + count + "개는 릴스 표본이 1개뿐입니다. 순위 해석에 주의하고 각 행에 표본 수를 함께 표기하세요.";
    }
    ```
    scalar·grouped·search 페이로드 조립부에서 해당 조건 시 `caveats` ArrayNode 삽입(조건 미충족 시 필드 자체 생략).
- [ ] BrandAiToolSpecs: 세 툴 스키마에 sponsorship enum 추가, aggregate에 minSample 추가. description에 "광고·협찬 여부는 keyword가 아니라 sponsorship 인자" 명시.
- [ ] 테스트 통과 확인 후 커밋: `feat(was): AI 툴 sponsorship 축 인자·minSample·서버 강제 caveat(스펙 §3·§4)`

### Task 2: 용어 사전 BrandAiGlossary + 프롬프트 개정

**Files:** Create BrandAiGlossary.java, Modify BrandAiPrompt.java, BrandAiAgent.java(buildBasePrompt), BrandAiAgentTest.java

- [ ] 실패 테스트: `시스템_프롬프트에_용어_정의_섹션이_상시_주입된다` (fake client 캡처 프롬프트에 "[용어 정의]"와 "sponsorship" 포함 확인 - 세션 brandId 유무와 무관하게).
- [ ] BrandAiGlossary: 스펙 §5의 정의 목록을 `public static final String SECTION` 텍스트 블록으로("\n\n[용어 정의]\n" 시작, 엠대시 금지). 항목은 스펙 §5 전체를 담되 문장은 명령형으로 다듬는다.
- [ ] BrandAiAgent.buildBasePrompt: `BrandAiPrompt.SYSTEM + BrandAiGlossary.SECTION + context + extra`.
- [ ] BrandAiPrompt 개정: caveat 의무 규칙 신설("툴 결과에 caveats 필드가 있으면 그 내용을 반드시 답변에 반영하고 한계를 고지합니다"), 규칙 12~14 중 사전과 중복되는 산식·축 설명은 사전 참조로 축약(이중 정본 금지 - 산식 정본은 사전).
- [ ] 통과 확인 후 커밋: `feat(was): AI 용어 사전(BrandAiGlossary) 상시 주입 + caveat 의무 규칙(스펙 §5·§4)`

### Task 3: 프리셋 verified 플랜 선실행 주입

**Files:** BrandAiPresets.java, BrandAiAgent.java, V1BrandAiMessagesController.java, BrandAiAgentTest.java, V1BrandAiMessagesControllerTest.java

- [ ] 실패 테스트:
  - `프리셋_플랜이_있으면_첫_LLM_호출_전에_선실행_결과가_대화에_주입된다` (fake client가 받은 contents에 functionCall/functionResponse 쌍 선행 확인, fake toolbox 실행 기록 확인)
  - `플랜_실행이_실패하면_주입_없이_기존_경로로_폴백한다`
  - 컨트롤러: `presetId가_플랜을_가지면_agent에_플랜이_전달된다`
- [ ] BrandAiPresets 확장:
  ```java
  /** 검증된 호출 플랜 1건(스펙 §6) - argsJson의 brandId는 실행 시 세션 brandId로 치환된다. */
  record PlannedCall(String toolName, String argsJson) {}
  static List<PlannedCall> planFor(String presetId) { ... }
  ```
  플랜: efficient_influencers → `aggregate_posts {"groupBy":"author","orderBy":"reachMultiple","limit":10,"minSample":2}` / sponsored_vs_organic → `aggregate_posts {"groupBy":"sponsorship"}` / top_posts → `list_posts {"sort":"performance_desc"}`. 나머지 둘은 빈 목록. 지시문도 갱신: 플랜 보유 프리셋은 "핵심 데이터는 이미 조회되어 있습니다. 그 결과로 답하되 필요하면 추가 조회하세요. 표에는 각 행의 표본 수를 포함하세요."
- [ ] BrandAiAgent: run() 두 오버로드에 `List<BrandAiPresets.PlannedCall> plannedCalls` 파라미터 추가(기존 오버로드는 빈 목록 위임). 루프 진입 전:
  ```java
  // 프리셋 verified 플랜 선실행(스펙 §6) - 검증된 호출을 먼저 실행해 결과를 대화에 주입한다.
  // 실패하면 주입하지 않고 기존 자유 경로로 폴백한다. 실행분은 로그·회수에 포함(관측 일관성).
  for (BrandAiPresets.PlannedCall call : plannedCalls) {
      ObjectNode args = (ObjectNode) objectMapper.readTree(call.argsJson());
      if (sessionBrandId != null) { args.put("brandId", sessionBrandId); }
      AiToolResult result = toolbox.execute(toolSession, userId, call.toolName(), args);
      if (result.failed()) { log.warn(...); break; }  // 폴백 - 이미 주입된 선행 호출은 유지
      toolCalls.add(new AiChatLogEntry.ToolCallLog(call.toolName(), args, result.rowCount()));
      shortCodes.addAll(result.shortCodes());
      contents.add(client.modelToolCallContent(List.of(new LlmTurn.ToolCall(call.toolName(), args))));
      contents.add(client.toolResultContent(List.of(new GeminiChatClient.ToolResponse(call.toolName(), result.payloadJson()))));
  }
  ```
  (LlmTurn.ToolCall 생성자 시그니처는 실제 코드 확인 후 맞출 것. 스트리밍 경로는 listener.onToolCall도 호출해 FE 진행 표시 일관 유지.)
- [ ] 컨트롤러: 두 경로에서 `BrandAiPresets.planFor(request.presetId())`를 agent.run에 전달.
- [ ] 통과 확인 후 커밋: `feat(was): 프리셋 verified 플랜 선실행 주입 - 검증 경로 이원화(스펙 §6)`

### Task 4: eval 골드셋 + 러너

**Files:** Create `was/eval/goldset.json`, `was/eval/run.sh`, `was/eval/README.md`

- [ ] goldset.json: 스펙 §7-1 스키마로 케이스 12개 이상. 필수 포함:
  - ad-posts-top10 (forbidTools: keyword=광고 / expectTools: sponsorship=sponsored·groupBy=author)
  - fit-influencer ("어울리는 인플루언서" - expectTools: groupBy=author, 채점은 trajectory만)
  - top10-reach / top30-reach (expectTools limit 10/30, 30명은 답변에 "30" 행 검증 대신 totalGroups 고지 문자열은 데이터 의존이라 trajectory만)
  - month-compare (groupBy=month), caption-count (search_posts, groundTruthSql로 총 매칭 수 검증), sponsored-vs-organic (groupBy=sponsorship), comments-summary (get_comments, shortCodes 배열 사용 확인), preset-efficient (presetId 지정 - 선실행 플랜 호출이 로그에 남는지)
  - groundTruthSql은 로컬 monitoring DB 스키마(brand_tagged_post·brand_post_meta 조인) 기준으로 작성하되, 러너가 실행 실패하면 그 케이스의 수치 채점만 SKIP 표시(러너 죽지 않기).
- [ ] run.sh (bash, analytics/test/run.sh 컨벤션 - `PG_CONTAINER` 오버라이드):
  1. 전제 확인: was 8081 응답, DB 컨테이너 접근.
  2. app_setting 한도 임시 상향(daily 999·per-minute 999) - 종료 trap에서 복원(30·10).
  3. 로그인(CSRF 쿠키 확보 → /v1/auth/login, 계정 poc@test.local/poc-test-1234 - env로 오버라이드 가능하게).
  4. 케이스별: /ai/messages POST(완결 JSON, accountIds는 env BRAND_ID 기본 128) → 응답 저장 → psql로 ai_chat_logs 최신 행(tool_calls·answer) 조회 → 채점(jq로 expectTools 부분 매치·forbidTools 검사, groundTruthSql 실행값의 콤마/무콤마 두 포맷이 answer에 포함되는지).
  5. 결과 표 출력(PASS/FAIL/SKIP + 실패 상세), 실패 있으면 exit 1.
  - 케이스 간 sleep 1(서버 2스레드 보호). 각 케이스는 conversationId 없이(독립 대화).
- [ ] README.md: 전제(로컬 was·DB·Vertex env), 실행법, 케이스 추가 규칙("실패가 발견되면 케이스부터 추가"), 모델 실험법(BRAND_AI_MODEL 바꿔 재실행).
- [ ] run.sh는 실 Vertex 비용이 들므로 이 태스크에서는 **문법 검증**(bash -n, jq 스키마 파싱)까지만. 실 완주는 Task 5(메인 세션).
- [ ] 커밋: `feat(was): AI 챗 eval 골드셋·러너 - 실측 사고 3부류 회귀 게이트(스펙 §7)`

### Task 5: 실측 검증·문서·마무리 (메인 세션 직접)

- [ ] :was:test 전체(기존 플레이키 2건 제외 기준 그린).
- [ ] 로컬 was 재기동 → `was/eval/run.sh` 실 완주 → 실측 사고 3건 케이스 PASS 확인. FAIL이면 원인 수정 후 재실행(골드셋·구현 중 어느 쪽 결함인지 구분해 기록).
- [ ] DECISIONS.md 최상단 결정 추가(리서치 근거·B 기각과 선실행 주입의 구분 포함), 스펙 상태 ✅, 이 계획 archive 이동, 08-31 스펙의 "eval 계획" 항을 본 스펙 §7 포인터로 갱신.
- [ ] 커밋·push. **PR 금지.**
