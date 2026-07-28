> 상태: 🟢 활성 · 설계 승인됨(구현 전)

# 뷰티 판정 v3 — 한국어 콘텐츠 필터(`FOREIGN_INFLUENCER`) 설계

## 배경

뷰티 판정 v2(2026-07-20 스펙)는 뷰티 여부·형태(개인/회사/시술)만 분류한다. 서비스 목적이
**한국 시장 뷰티 제품 시딩·협찬**이므로, 외국인(비한국어 콘텐츠) 뷰티 인플루언서는 시딩
대상이 아닌데 현재 모수에 섞여 들어온다. 판정 로직에 한국어 콘텐츠 여부를 추가해
**한국인 뷰티 인플루언서만** 시드·수집·서빙 모수에 남긴다.

## 결정 사항

1. **"한국인" 기준은 한국어 콘텐츠 중심.** 국적·거주지가 아니라 bio·캡션이 한국어이고
   한국 오디언스를 대상으로 하는지로 판정한다 — LLM이 프로필 텍스트만 보고 판단 가능한
   기준이고, 한국 시장 마케팅 목적에 부합.
2. **필터는 INFLUENCER에만 적용.** COMPANY(뷰티 제품 회사)는 해외 브랜드도 컨택 타깃으로
   유지 — 한국 필터 미적용.
3. **새 분류 `FOREIGN_INFLUENCER` 추가(5분류).** BEAUTY_SERVICE 선례와 동일하게
   beauty=false로 파생 — 시드·수집·서빙에서 자동 제외되고 세그먼트로 보존된다.
   NOT_BEAUTY로 흡수하면 외국 뷰티 세그먼트 정보가 사라지고, 별도 boolean 컬럼은
   하류 필터 지점이 늘어나 배제.
4. **기존 판정분은 INFLUENCER만 재판정.** CLAUDE 판정 INFLUENCER분만 판정 초기화 후
   BEAUTY 잡 재실행(MANUAL 제외) — 영향 범위가 있는 분류만 재판정해 비용 최소.

## 분류 체계 (v3)

| 분류 | 의미 | beauty 파생 |
|---|---|---|
| INFLUENCER | **한국어 콘텐츠 중심** 뷰티 제품 개인 크리에이터 | true |
| **FOREIGN_INFLUENCER** (신규) | 뷰티 제품 개인 크리에이터지만 한국어 콘텐츠 아님 | false |
| COMPANY | 뷰티 제품 제작·판매 회사 (한국 필터 미적용) | true |
| BEAUTY_SERVICE | 뷰티 영역이지만 시술·서비스 중심 | false |
| NOT_BEAUTY | 뷰티 콘텐츠 중심이 아님 | false |

`beauty()`/`company()` 파생 규칙은 변경 없음 — FOREIGN_INFLUENCER는 어느 쪽에도 속하지
않아 자동으로 false.

## 프롬프트 판정 규칙 (v3 핵심)

INFLUENCER 정의에 "한국어 콘텐츠 중심(한국 오디언스 대상)" 조건을 추가하고,
FOREIGN_INFLUENCER 분류와 경계 규칙을 명시한다:

- bio·캡션이 주로 한국어면 INFLUENCER.
- **bio가 영어라도 캡션이 한국어면 한국어 콘텐츠로 판정** — 한국 계정이 영어 bio를 쓰는
  경우가 흔하고, 캡션이 실제 콘텐츠 언어라 더 강한 신호다.
- 한국어·외국어 혼용이면 주 오디언스 기준.
- 캡션 미수집(빈 배열)이고 bio만으로 모호하면 이름·bio의 한국어 여부로 판정.
- 한국 필터는 개인 크리에이터에만 — 회사 계정은 언어 무관 COMPANY.

## 변경 지점

| 파일 | 변경 |
|---|---|
| `crawling/domain/BeautyClass.java` | `FOREIGN_INFLUENCER` 값 추가(파생 규칙 변경 없음) |
| Flyway 신규 마이그레이션(`V21__beauty_foreign_influencer.sql`) | `influencer_beauty_class_check` 제약을 5분류로 재생성 |
| `adapter/out/claude/ClaudeCliBeautyJudge.java` | `buildPrompt` v3 규칙 반영, `parse`에 새 분류 케이스 추가 — Claude API·Gemini 어댑터가 재사용하므로 자동 반영 |
| `application/service/BeautyJob.java` | `Summary` 카운트·`applyVerdicts` switch·로그 라벨에 새 분류 추가 |
| `templates/influencers.html` | FOREIGN_INFLUENCER 배지 추가(수동 판정 드롭다운은 `BeautyClass.values()`라 자동) |
| `deploy/scripts/reset-influencer-judgments-v3.sql` (신규) | 일회성 재판정 스크립트(아래) |

**analytics·was 변경 없음** — 하류는 파생 boolean(`beauty`/`beauty_company`)만 읽으므로
(01_recent_window/02_serving 등 `i.beauty AND NOT i.beauty_company` 필터), beauty=false로
파생되는 새 분류는 자동 제외된다.

rejudge 흐름도 변경 없음 — `findRejudgeTargets`는 비뷰티(beauty=false) 판정 중 재료가
갱신된 계정을 고르므로 FOREIGN_INFLUENCER도 자동 포함된다.

## 기존 데이터 재판정 (일회성 운영 작업)

`deploy/scripts/reset-influencer-judgments-v3.sql`:

```sql
update influencer
   set beauty = null, beauty_company = null, beauty_class = null,
       beauty_source = null, beauty_reason = null, beauty_judged_at = null
 where beauty_class = 'INFLUENCER' and beauty_source = 'CLAUDE';
```

- MANUAL 판정은 건드리지 않는다.
- 초기화 후 서버 어드민에서 BEAUTY 잡 트리거 → 새 기준 재판정.
- 재판정 전까지 기존 INFLUENCER는 모수에 남는다(NULL로 되돌린 계정은 판정 완료까지
  일시적으로 모수에서 빠짐 — 배치 한도(beauty.batch-limit)에 따라 수 회 실행 필요).

## 테스트

- `BeautyClassTest` — FOREIGN_INFLUENCER의 beauty=false·company=false 파생.
- `ClaudeCliBeautyJudgeTest` — 프롬프트에 v3 규칙 포함, `parse`가 FOREIGN_INFLUENCER를
  매핑하고 5분류 외 값은 여전히 스킵.
- `BeautyJobTest` — FOREIGN_INFLUENCER 판정 적용(beauty=false 저장)·Summary 카운트.
