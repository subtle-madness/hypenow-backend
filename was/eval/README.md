# 브랜드 AI 챗 eval (설계 §7, 계획 2026-09-01 Task 4)

`goldset.json` 케이스를 로컬 was에 실제로 쏘고 `app.ai_chat_logs`에 남은 툴 호출·답변을 채점해
"실측 사고가 다시 나면 CI 없이도 사람이 로컬에서 잡을 수 있는" 회귀 게이트를 만든다. 정본 설계는
[`docs/superpowers/specs/2026-09-01-brand-ai-structural-quality-design.md`](../../docs/superpowers/specs/2026-09-01-brand-ai-structural-quality-design.md) §7.

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
./run.sh                 # 전체 13케이스 실행(순차, 케이스 간 1초 대기) - 실 Vertex 호출
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
- `groundTruthSql`: 선택. `monitoring` DB에서 실행하고(러너가 `:BRAND_ID`를 실제 값으로 치환),
  실행값이 콤마 포맷(`1,234`)·무콤마 포맷(`1234`) 둘 중 하나로 답변에 등장하면 통과. **SQL 실행
  자체가 실패하면(스키마 변경·데이터 없음 등) 그 케이스의 수치 검증만 SKIP 표시하고 러너는
  죽지 않는다** - 나머지 tools/forbid/answerContains 채점은 그대로 진행된다.

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

## 모델 실험법

이 러너 자체는 모델을 선택하지 않는다 - was가 쓰는 Vertex 모델은
`monitoring.brand.ai.model`(기본 `gemini-2.5-flash`, `BrandAiConfig` 참고) 프로퍼티다. 다른 모델로
같은 골드셋을 돌리려면 was를 그 값으로 재기동한 뒤 `run.sh`을 다시 실행하면 된다:

```bash
MONITORING_BRAND_AI_MODEL=gemini-2.5-pro ./gradlew :was:bootRun
# 다른 터미널에서
./run.sh
```

(계획 문서의 `BRAND_AI_MODEL`은 편의상 줄임 표기이고, 실제 스프링 프로퍼티 바인딩은 위
`MONITORING_BRAND_AI_MODEL` 환경변수다.) 결과 표의 PASS/FAIL 개수를 모델 간에 비교하는 것이
"③ 모델 실험"의 실체다(스펙 §7-2) - 정성적 우열은 실패한 케이스의 실제 답변을 사람이 대조해서
판단한다.

## 범위 밖(설계 §7-3)

온라인 judge 샘플링, 👎 트리거 자동화, 모호성 분류 파이프라인은 운영 배포 후 후속이다. 이
러너는 로컬 1회성 실행 전제이고 CI에 편입돼 있지 않다(실 LLM 비용 때문).
