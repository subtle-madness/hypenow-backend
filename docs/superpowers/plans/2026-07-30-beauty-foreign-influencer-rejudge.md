> 상태: 🟢 활성

# 뷰티 FOREIGN_INFLUENCER 대량 누락 — 재판정 범위 확대 + 프롬프트 v4

## 배경

crawler 계정 뷰티 판정에서 `FOREIGN_INFLUENCER`(외국인 뷰티 인플루언서, 서빙 제외 대상)가
대량 누락됐다. 원인은 둘.

### 원인 1 — 재판정 범위 누락(지배적)

v3 프롬프트(`FOREIGN_INFLUENCER` 도입, 커밋 5fb84118, V21)는 운영에서 2026-07-28 05:00 UTC부터
적용됐다. 그런데 `deploy/scripts/reset-influencer-judgments-v3.sql`이 **운영에서 실행되지
않았다**. 결과:

- 현재 `beauty_class='INFLUENCER'` 7,128건 중 **6,605건(92.7%)이 컷오버 이전 판정** —
  `FOREIGN_INFLUENCER` 선택지가 아예 없던 4분류 프롬프트 산출물이다.
- `InfluencerRepository.findRejudgeTargets`가 `i.beauty = false` 조건이라 `INFLUENCER`는
  자동 재판정 대상에서 구조적으로 영구 제외된다(자가 치유 불가).

### 원인 2 — 프롬프트 결함(잔여)

v3로 판정된 계정 중 "한글 0 + 외국어 문자 4자 이상"인 33건을 전수 확인한 결과 **33/33 전부
일본 뷰티 인플루언서인데 `INFLUENCER`로 판정**돼 있었다(`韓国コスメ` 리뷰, `美容垢`, 일본
앰배서더 프로그램 등). 원인 분석: 현행 프롬프트의 "한국어 콘텐츠 중심"이라는 표현을 LLM이
"한국 관련 콘텐츠"로 읽어, 한국 화장품을 **일본어로** 리뷰하는 계정을 INFLUENCER로 넣는다.
즉 **서술 언어**와 **다루는 제품의 국적**이 프롬프트에서 분리돼 있지 않았다.

## 실측 데이터

- v3 프롬프트 운영 컷오버: 2026-07-28 05:00 UTC (FOREIGN_INFLUENCER 최초 등장 시각)
- `INFLUENCER` 7,128건 = pre-v3 6,605 + post-v3 523. `FOREIGN_INFLUENCER` 418건은 전부 post-v3.
- 한글 신호(NFC 정규화 후) 버킷별 pre-v3 INFLUENCER: 한글0+외국어문자≥4 = 417 /
  한글0+라틴전용 = 630 / 한글>0 = 5,558
- v3 판정 코호트의 버킷별 외국인 기준율: 한글0+외국어문자 64.1%, 한글0+라틴전용 75.3%,
  한글>0 5.3%
- 위 기준율 적용 시 pre-v3 예상 오분류 ≈ 1,000~1,200건(서빙 INFLUENCER의 14~17%)
- 직접 스팟체크: pre-v3 "한글0+외국어문자" 표본 40건 → 40/40 전부 비한국어 계정
  (일본 다수, 대만·중국 소수)
- v3 판정 잔여 오분류: post-v3 "한글0+외국어문자" INFLUENCER 33건 전수 확인 → 33/33 일본 계정
- 서빙 영향: 후보군 1,047계정이 콘텐츠 20,148건 보유(랭킹 노출 중). 대조군인 기판정
  FOREIGN_INFLUENCER 418건은 콘텐츠 0건
- 인스타 `full_name`은 NFD(자모 분해)로 저장되는 경우가 많아 `[가-힣]` 범위 스크리닝이
  한국 계정을 놓친다 — 집계 시 `normalize(txt, NFC)` 선행 필요(이번 조사에서 실제로
  오측정했다가 정정)

## 수정 내용

### 1. 판정 프롬프트 v4 (`ClaudeCliBeautyJudge.buildPrompt`)

- INFLUENCER·FOREIGN_INFLUENCER 정의를 "서술 언어" 기준으로 재작성 — INFLUENCER는 "캡션·bio를
  한국어로 쓰는" 개인, FOREIGN_INFLUENCER는 "글을 한국어로 쓰지 않는" 개인으로 명확화.
- 경계 규칙에 판정 기준 축 분리 규칙 추가: **판정 기준은 서술 언어이지 다루는 제품·주제의
  국적이 아니다** — 한국 브랜드를 리뷰해도, 한국에 거주해도 글을 외국어로 쓰면
  FOREIGN_INFLUENCER. 히라가나·가타카나·한자·태국어·키릴 등 문장형 외국어 신호와
  카오모지(장식 문자, 신호 아님)를 구분하는 규칙도 추가.
- 기존 "캡션이 최우선 신호" 규칙에 대칭 조항 추가 — bio가 한국어 섞여도 캡션이 주로
  외국어면 FOREIGN_INFLUENCER.
- COMPANY·BEAUTY_SERVICE·NOT_BEAUTY 정의, 출력 포맷, `JUDGE_CHUNK`(50) 등 배치 상수는
  무변경.

### 2. 재판정 초기화 스크립트 (`deploy/scripts/reset-influencer-judgments-v4.sql`)

- 대상: `beauty_class = 'INFLUENCER' AND beauty_source = 'CLAUDE'`(MANUAL 보존) — v3와 동일
  조건이지만 v3가 미실행됐으므로 사실상 pre-v3 6,605건 + post-v3 오분류분 전체가 대상.
- **배치 슬라이스 지원**: 전량을 한 번에 NULL로 만들면 재판정 완료 전까지 서빙 게이트에서
  통째로 빠지므로(`beauty=null` → 수집·랭킹 대상 이탈), `beauty.batch-limit`(운영 현재값
  2000)에 맞춰 `slice`건씩 처리. `order by beauty_judged_at nulls first, id`로
  오래된(=pre-v3 가능성이 높은) 판정부터 소진.
- **psql 변수 2개** — `slice`(기본 2000), `started`(기본 `infinity`). 둘 다 `\if :{?var}`
  가드로 기본값을 주므로 `-v`로 넘긴 값이 덮이지 않는다(스크립트 안에서 무조건 `\set`하면
  `-v`가 무력화된다 — 실제로 그렇게 작성했다가 고쳤다).
- **`started`가 반복 실행의 안전장치**: 재판정 결과로 다시 `INFLUENCER`가 된 계정도
  `beauty_source='CLAUDE'`라, 시각 조건이 없으면 슬라이스를 반복할 때 이미 v4로 판정한
  계정을 다시 판정하고 잔여 카운트가 영원히 0이 되지 않는다. 작업 개시 시각을 고정해
  전 슬라이스에 같은 값으로 넘긴다.
- `begin; ... commit;` 트랜잭션.

psql 변수 동작은 UPDATE를 SELECT로 바꾼 등가 스크립트로 운영 DB에서 검증했다(읽기 전용) —
기본값 경로 `slice=2000/started=infinity`, 오버라이드 경로 `-v slice=500 -v started=...`
둘 다 의도대로 동작(선정 건수 2000 / 500).

### 3. 테스트

`ClaudeCliBeautyJudgeTest`에 신규 케이스 추가 — 프롬프트에 "다루는 제품·주제의 국적이
아니다" 취지 문구와 5개 분류명이 모두 포함되는지 검증. 기존 `parse`·`buildPrompt` 테스트는
무변경으로 유지.

## 재판정 실행 절차 (운영, 머지 후)

1. 워커 대수 확인: `INFLUENCER AND CLAUDE` 잔여 대상 수 조회
   ```sql
   select count(*) from influencer
    where beauty_class = 'INFLUENCER' and beauty_source = 'CLAUDE';
   ```
   현재 실측 기준 약 7,127건(6,605 pre-v3 + 523 post-v3 중 CLAUDE분, MANUAL 제외) —
   `beauty.batch-limit=2000` 기준 **4회 슬라이스** 예상.
2. 슬라이스 1회 초기화:
   ```sh
   docker compose exec -T postgres-raw psql -U crawler -d crawler -v slice=2000 \
     < deploy/scripts/reset-influencer-judgments-v4.sql
   ```
3. 서버 어드민(8080 `/ui`)에서 BEAUTY 잡 트리거 → 배치 소진 대기.
4. 위 1번 카운트 쿼리로 잔여 확인 → 0보다 크면 2~4번 반복.
5. 전량 소진 후 검증(아래 §검증 참조).

**롤백 고려사항**: 슬라이스 단위 실행이므로 중간에 문제가 발견되면 그 시점에서 중단 가능 —
이미 초기화된 슬라이스는 beauty=null 상태로 남아 다음 BEAUTY 잡이 재판정하거나, 급하면
`beauty_class`가 이전에 무엇이었는지는 UPDATE로 유실되므로(원본 미보존) 되돌릴 수 없다.
운영 영향이 우려되면 슬라이스 크기를 `-v slice=500` 등으로 줄여 첫 배치만 시험 실행 후
스팟체크하고 이어가는 것을 권장.

## 검증 방법

- 재판정 후 `FOREIGN_INFLUENCER` 총량이 418건 대비 유의하게 증가했는지 확인(기준율
  적용 시 pre-v3 예상 오분류 1,000~1,200건 → 전체 FOREIGN_INFLUENCER가 1,400~1,600건대로
  증가할 것으로 예상).
- 재판정 후 남은 INFLUENCER 표본(한글0+외국어문자 버킷)을 다시 스팟체크해 v4 프롬프트가
  버킷 기준율(64.1%)을 낮췄는지 확인 — 33/33 같은 완전 오분류가 재현되지 않는지가 핵심 지표.
- 서빙 영향 재측정: 위 §배경의 "후보군 1,047계정·콘텐츠 20,148건" 수치가 재판정 후 얼마나
  줄었는지(랭킹에서 이탈한 콘텐츠 수) 어드민 대시보드로 재확인.
- `./gradlew :crawler:test`로 회귀 없음 확인(이번 세션 실측: 88건 실패는 로컬 Testcontainers/
  colima 환경 문제로 이번 변경과 무관한 기존 실패 — 변경 전후 실패 건수 동일, 신규 테스트만
  추가·통과).
