# 브랜드 AI 챗 eval (설계 §7, 계획 2026-09-01 Task 4)

`goldset.json` 케이스를 로컬 was에 실제로 쏘고 `app.ai_chat_logs`에 남은 툴 호출·답변을 채점해
"실측 사고가 다시 나면 CI 없이도 사람이 로컬에서 잡을 수 있는" 회귀 게이트를 만든다. 정본 설계는
[`docs/superpowers/specs/archive/2026-09-01-brand-ai-structural-quality-design.md`](../../docs/superpowers/specs/archive/2026-09-01-brand-ai-structural-quality-design.md) §7.

## 전제

- 로컬 `was`가 8081에서 떠 있어야 한다(`./gradlew :was:bootRun`). Vertex 자격 증명(`GOOGLE_APPLICATION_CREDENTIALS`
  등)이 셸에 export돼 있어야 실 LLM 호출이 된다 - 이 러너는 **실 Vertex 비용이 든다**(CI에 없음, 사람이
  로컬에서 의도적으로 돌리는 도구).
- 로컬 DB 컨테이너(`crawler-postgres-1`, 머신마다 이름이 다를 수 있음 - `PG_CONTAINER`로 오버라이드)가
  떠 있어야 한다. `docker exec`가 도커 소켓을 못 찾으면(콜리마 사용 시 흔함):
  ```
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  ```
- `EVAL_EMAIL`/`EVAL_PASSWORD`(기본 `poc@test.local`/`poc-test-1234`) 계정이 로컬 `app.users`에 있고,
  `BRAND_ID`(기본 128)를 `app.brand_monitorings`로 보유하고 있어야 한다 - 아니면 모든 케이스가
  404(`success:false`)로 SKIP된다. 계정·브랜드 링크가 없으면 로컬 시딩부터 맞춘다(브랜드 모니터링
  등록 플로우로 직접 만들거나, 기존 로컬 시드 데이터를 확인).
- `curl`·`jq`·`docker`가 PATH에 있어야 한다.

## 실행법

```bash
cd was/eval
./run.sh                 # 전체 14케이스 실행(순차, 케이스 간 1초 대기) - 실 Vertex 호출
./run.sh --self-test      # 채점 로직만 mock 데이터로 검증 - 네트워크·DB 접근 없음, 비용 없음
```

env 오버라이드(전부 선택):

| 변수 | 기본값 | 용도 |
|---|---|---|
| `WAS_BASE` | `http://localhost:8081` | was 베이스 URL |
| `BRAND_ID` | `128` | 질의할 브랜드 id (`accountIds`) |
| `EVAL_EMAIL` / `EVAL_PASSWORD` | `poc@test.local` / `poc-test-1234` | 로그인 계정 |
| `PG_CONTAINER` | `crawler-postgres-1` | DB 컨테이너 이름 |
| `PG_USER` | `crawler` | psql 접속 유저 |
| `APP_DB` | `analysis` | `app.ai_chat_logs`·`app.app_setting`이 있는 DB (was의 application.yml 기준) |
| `MONITORING_DB` | `monitoring` | `groundTruthSql`이 조회하는 DB(brand_tagged_post·brand_post_meta) |

`run.sh`(self-test 아닌 본 실행)는 시작 시 `app.app_setting`의 `ai.chat.daily-limit`·
`ai.chat.per-minute-limit`을 999로 올렸다가, 어떤 경로로 끝나든(성공·실패·Ctrl-C) `trap`으로
30·10에 복원한다 - 로컬 전용이라 운영 값에는 손대지 않는다.

## 케이스 스키마

```json
{
  "id": "ad-posts-top10",
  "note": "사람이 읽는 설명 - 그레이더는 무시한다",
  "question": "광고 게시물로 10명 정리해줘",
  "presetId": null,
  "expectTools": [{"name": "aggregate_posts", "argsInclude": {"groupBy": "author", "sponsorship": "sponsored"}}],
  "forbidTools": [{"name": "aggregate_posts", "argsInclude": {"keyword": "광고"}}],
  "expectAnswerContains": ["표본"],
  "expectAnswerNotContains": ["shortCode를 알려", "알 수 없습니다"],
  "groundTruthSql": "SELECT count(...) ... WHERE t.brand_id = :BRAND_ID ..."
}
```

- `expectTools`/`forbidTools`의 각 rule은 `name` + 둘 중 하나(또는 둘 다) 선택:
  - `argsInclude`: 부분 매치 - 여기 적은 키만 값이 정확히 일치하는 호출을 찾는다(나머지 키는 무시).
  - `argsHasKeys`: 값과 무관하게 그 키가 args에 있는지만 확인 - 예: `get_comments`가
    `shortCodes`(배열) 인자를 실제로 썼는지(게시물마다 `shortCode` 단건 호출을 반복한 게
    아니라) 확인할 때 값 매칭이 불가능해서 이 확장을 뒀다(스펙 §7-1 예시에는 없는 확장).
  - `expectTools`는 목록의 **모든** rule이 각각 하나 이상의 호출과 매치해야 통과.
  - `forbidTools`는 목록의 rule 중 **하나라도** 매치하는 호출이 있으면 실패.
- `expectAnswerContains`: 답변 텍스트에 전부 부분 문자열로 있어야 하는 고정 문구.
- `expectAnswerNotContains`(2026-09-01 실측 id75 후속, `expectAnswerContains`의 대칭): 목록 중 어느
  하나라도 답변 텍스트에 부분 문자열로 등장하면 실패. shortCode 같은 내부 식별자를 사용자에게
  요구하거나 "알 수 없습니다"로 물러나는 조용한 회피를 잡을 때 쓴다.
- `groundTruthSql`: 선택. `monitoring` DB에서 실행하고(러너가 `:BRAND_ID`를 실제 값으로 치환),
  실행값이 콤마 포맷(`1,234`)·무콤마 포맷(`1234`) 둘 중 하나로 답변에 등장하면 통과. **SQL 실행
  자체가 실패하면(스키마 변경·데이터 없음 등) 그 케이스의 수치 검증만 SKIP 표시하고 러너는
  죽지 않는다** - 나머지 tools/forbid/answerContains 채점은 그대로 진행된다.
- `turns`(2026-09-02 스윕 후속, 멀티턴 체인 케이스): `question` 대신 턴 배열을 두면 러너가 순서대로
  보내되 첫 응답의 `conversationId`를 이후 턴에 실어 체인을 유지한다. 각 원소는
  `{"text", "expectTools", "forbidTools", "expectAnswerContains", "expectAnswerNotContains"}`(검증
  필드 전부 선택)이고, 턴마다 그 턴의 tool_calls·답변에 그 턴의 룰을 적용한다. 전역 denylist는
  모든 턴에 적용되며, 모든 턴이 통과해야 케이스 PASS(FAIL 상세에 `turn N: ...`로 어느 턴인지
  표기). `groundTruthSql`은 단일 턴 케이스 전용이다. 예: `chain-referent-resolution`("이번 달 릴스
  top5" → "거기서 조회수 젤 높은 사람 프로필")은 2턴째가 `get_author`를 올바른 대상으로 부르는지,
  즉 지칭 해소 회귀를 잡는 앵커다. 골드셋 17건이 전부 단일 턴이라 멀티턴 열화(조기 확정 과의존,
  지칭 오해소)를 못 보던 사각지대를 메운다(09-02 리서치 - arXiv 2505.06120).

## 전역 답변 denylist(내부 구현 용어 유출 차단, 2026-09-01 실측 id75·id70 후속)

`GLOBAL_ANSWER_DENYLIST`(run.sh 상단)는 케이스별 `expectAnswerNotContains`와 별개로 **모든 케이스의
답변에 항상** 적용되는 전역 검사다. "`list_posts` 툴은 최대 30건...", "도달 배수(reachMultiple)"처럼
툴 이름·인자·필드명이 사용자 답변에 그대로 새는 것을 잡는다(프롬프트 쪽 대응은
`BrandAiPrompt.SYSTEM` 규칙 11-1). 현재 목록:

```
list_posts, aggregate_posts, search_posts, get_comments, get_author, list_brands,
groupBy, reachMultiple, viewsSampleCount, minSample, "sponsorship 인자", "###",
"shortCode를 알려", "shortCode`를 알려"
```

`shortCode를 알려`(백틱 병기 변형 포함)는 사용자에게 내부 식별자 입력을 요구하는 회피 패턴 감시다 - 규칙 7의
shortCode 표기는 허용되지만 되묻기는 사전이 금지한다(09-02 스윕 재실행에서 "뒷광고" 질문이 오거절 대신
shortCode 요구로 옮겨간 2차 결함 실측). `###`은 내부 용어가 아니라 포맷 위반 감시다(2026-09-02 스윕 104턴 중 4턴이 마크다운 헤더를 씀 -
프롬프트 규칙 11이 모든 헤더를 금지하므로 정당한 사용처가 없다).

FAIL 상세에는 걸린 용어가 그대로 찍힌다(`전역 denylist 위반(내부 용어 노출: <용어>)`).

**`get_post`는 이 목록에 없다** - 실존하는 툴 이름(`BrandAiToolSpecs.GET_POST`)이지만, 일반적인
한국어 답변 문장에 `get_post`가 부분 문자열로 등장할 자연스러운 경로가 없고, `shortCode` 병기
의무(규칙 7)가 요구하는 값도 shortCode 자체(영숫자 코드)이지 `get_post`라는 문자열이 아니라서
오탐 여지가 없다고 보고 뺐다. 반대로 넣었을 때의 이득도 없다(이미 안 새는 걸 감시하는 셈) -
넣는 비용(목록이 길어짐)만 있고 얻는 게 없어 제외했다.

내부 용어가 정당하게 필요한 케이스가 생기면(예: 사용자가 먼저 툴 이름을 언급하며 물어봐서 그
용어를 답변에서 되풀이해야 하는 경우) 그때 케이스 스키마에 per-case 예외 필드(예:
`allowGlobalDenylistTerms`)를 추가한다 - 지금은 그런 케이스가 없으므로 전역 목록에 예외 없이
전부 적용한다.

## 채점은 전부 결정론(1단계, 설계 §7-2)

LLM judge 없음 - `expectTools`/`forbidTools`는 `app.ai_chat_logs.tool_calls`(jsonb, 실행된 툴 호출의
`name`·`args`·`rows`)를 jq로 부분 매치하고, `groundTruthSql`은 SQL 실행값을 답변 문자열에서
찾는다. 정성적 채점(예: "추천이 실제로 타당한가")은 이 러너의 범위 밖 - `fit-influencer`처럼
trajectory(툴 선택)만 확인하고 답변 품질은 사람이 읽는다.

## 케이스 추가 규칙: "실패가 발견되면 케이스부터 추가한다"

운영·로컬에서 새로운 조용한 근사(silent approximation) 사고를 발견하면, 그 자리에서 프롬프트만
패치하지 말고 **먼저 이 골드셋에 재현 케이스를 추가**한다(실측 사고 문구를 `question`에 그대로
쓰고, 사고의 정체를 `forbidTools`/`expectTools`로 못박는다) - 그래야 같은 사고가 나중에 조용히
재발해도 다음 러너 실행에서 잡힌다. 케이스를 추가한 뒤에 수정(툴 인자·프롬프트·사전)을 하고,
러너가 그 케이스를 PASS로 넘기는 것을 고침의 완료 기준으로 삼는다.

`caption-count`의 키워드(`이벤트`)는 로컬 시드 데이터의 실제 캡션 언급 여부를 확인 못 한 채
넣은 값이다 - 처음 실행해서 매칭이 0건이거나 부자연스러우면, 실제로 로컬 DB에 여러 건 잡히는
단어로 바꿀 것(`monitoring_psql`로 `SELECT caption FROM brand_post_meta LIMIT 20` 정도로 훑어보면
빠르다).

`author-posts-detail`의 `expectTools[0].argsInclude.author`는 모델이 실제로 넘긴 인자 원문과
정확히 일치해야 매치된다(툴박스의 `@` 접두 정규화는 서버 내부에서만 일어나고 로그
`tool_calls[].args`는 모델이 넘긴 원문이다) - 모델이 `"@kbeauty.real.gems"`처럼 `@`를 붙인 채로
호출하면 이 rule이 실패할 수 있다. 처음 실행해서 그렇게 새면 `argsInclude`를 빼고
`forbidTools`(예: `expectAnswerNotContains`의 "shortCode를 알려" 계열)만으로 좁혀 조정할 것.

## 모델 실험법

이 러너 자체는 모델을 선택하지 않는다 - was가 쓰는 Vertex 모델은
`monitoring.brand.ai.model`(기본 `gemini-2.5-flash`, `BrandAiConfig` 참고) 프로퍼티다. 다른 모델로
같은 골드셋을 돌리려면 was를 그 값으로 재기동한 뒤 `run.sh`을 다시 실행하면 된다:

```bash
BRAND_AI_MODEL=gemini-2.5-pro BRAND_AI_THINKING_BUDGET=-1 ./gradlew :was:bootRun
# 다른 터미널에서
./run.sh
```

(`application.yml`의 `monitoring.brand.ai.model` 플레이스홀더가 정본이라 `BRAND_AI_MODEL`이
정식 환경변수 이름이다 - `MONITORING_BRAND_AI_MODEL`도 Spring relaxed binding으로 같은
프로퍼티에 바인딩되어 동작은 하지만, 표기는 위 이름을 쓸 것.) **pro 계열로 실험할 때는
`BRAND_AI_THINKING_BUDGET=-1`이 필수다** - gemini-2.5-pro는 thinking을 비활성화할 수 없어
기본값 0(flash 전용)을 그대로 두면 Vertex가 "The model does not support setting
thinking_budget to 0" 400을 전 호출에서 돌려준다(2026-09-01 실측, `BrandAiConfig` 참고). 결과
표의 PASS/FAIL 개수를 모델 간에 비교하는 것이 "③ 모델 실험"의 실체다(스펙 §7-2) - 정성적
우열은 실패한 케이스의 실제 답변을 사람이 대조해서 판단한다.

## 대량 스윕(기대값 없는 스크리닝, 2026-09-02~)

골드셋이 "알려진 실패의 회귀"라면 스윕은 "모르는 실패의 발굴"이다. 마케터 말투 질문(막연·구어체·
오타·업무 맥락·불가능 요청·모호 지칭·꼬리질문 체인) 93항목 104턴을 `sweep-questions.json`에 두고,

```
./sweep.sh                       # 순차 호출, 체인은 conversationId 유지 -> sweep-results.jsonl
./sweep-screen.sh                # 결정론 규칙으로 실패 후보만 표면화 + 길이·툴 호출 통계
QUESTIONS=subset.json OUT=rerun.jsonl ./sweep.sh   # 수정 후 실패분만 재실행
```

스크리닝 규칙(A 도메인 안 거절, B 툴 0회+수치/계정명, C 내부 용어, D 식별자 되물음, E 빈/오류
답변)은 **깔때기이지 채점기가 아니다** - 후보를 사람이 읽고 진짜 실패만 골드셋 케이스로 승격한다.
알려진 오탐: B는 시스템 프롬프트에 선주입되는 브랜드 컨텍스트(팔로워 수·수집 개월)를 인용한 답을
잡고, A는 사전이 지시한 경쟁사 안내 문구("조회할 수 없어요")를 잡는다. `sweep-results.jsonl`은
실측 산출물이라 커밋하지 않는다(질문 파일·스크립트만 커밋).

09-02 1차 스윕 결과: 104턴 중 진짜 실패 5부류 9턴(날조 표 1, 내부 용어 유출 2, 뒷광고 오거절 1,
팔로워 추이 1, `###` 헤더 4) - 각 부류 대표가 골드셋 18~22번 케이스다. 상세는 커밋 메시지 참고.

## 범위 밖(설계 §7-3)

온라인 judge 샘플링, 👎 트리거 자동화, 모호성 분류 파이프라인은 운영 배포 후 후속이다. 이
러너는 로컬 1회성 실행 전제이고 CI에 편입돼 있지 않다(실 LLM 비용 때문).
