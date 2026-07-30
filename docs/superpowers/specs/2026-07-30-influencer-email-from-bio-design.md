# 인플루언서 이메일 — biography 정규식 파싱 (트랙 BB)

> 상태: 🟢 활성 · ✅ 구현됨(PR 대기)
> 날짜: 2026-07-30

## 1. 배경

발굴 목록(`GET /v1/influencers`, 스펙 6.21) 카드의 `email` 필드는 P4(07-28) 도입 이래
"크롤러 미수집(V31)이라 항상 null" 스텁이었다. `contactOpen` 필터도 매칭 대상이 없어
`WHERE ... AND false`로 죽어 있었다.

## 2. 결정 — 정규식, LLM 아님

운영 DB 실측(analysis DB `account_summaries`, 2026-07-30):

- 전체 7,033행 중 biography 보유 6,808행(96.8%)
- 정규식 `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}` 매치 2,553행(37.5%)
- 매치 30건 전수 육안 확인 — 오탐 0건
- 도메인 분포는 gmail 1,182·naver 1,143 위주로 깨끗
- 난독화(`at`·`골뱅이`·공백 삽입 등) 후보 최대 0.75% 추정 — LLM 파싱으로 얻는 회수율 이득이
  비용·지연을 정당화하지 못한다고 판단해 **정규식 단독 채택**

## 3. 규칙

- POSIX 정규식, `substring(biography from '...')` — leftmost match만 반환하므로
  "이메일 2개 이상 매치 시 첫 번째만"이 정규식 엔진 특성상 자연히 성립(별도 로직 불필요,
  8건 실측 확인)
- 결과는 `lower()`로 소문자 정규화
- 후행 문장부호(`abc@gmail.com.`)는 도메인부 `[A-Za-z]{2,}` 그리디 매칭의 백트래킹으로
  자연 제외됨(정규식 엔진 특성 — 별도 trim 불필요)
- biography NULL 또는 매치 없음 → email NULL

## 4. 구현 범위

- `analytics/views/10_account_detail.sql` `v_account_summaries`에 `email` 파생 컬럼 추가
  (뷰 SELECT 맨 끝 — 기존 뷰티 필터는 `v_recent_content`가 이미 적용 중이라 무변경)
- analysis Flyway `V46__account_summaries_email.sql` — `account_summaries.email` ADD COLUMN
  (expand만, record 끝 위치와 순서 일치)
- `contract-analysis` `AccountSummary.email`(끝 필드) — MirrorJob 위치 대조·FlywaySchemaTest
  ordinal 대조 통과 조건
- was `V1InfluencerDiscoveryRepository`: `findCards`/`findCardsByHandles` SELECT에 `su.email`
  추가, `CardRow.email`, `contactOpen` 필터를 `AND false` → `AND su.email IS NOT NULL`로 교체
- `InfluencerCard.email` 배선(`V1InfluencerDiscoveryAssembler`) — 구 null 스텁 제거

## 5. 범위 밖

- `InfluencerProfileResponse.Influencer.email`(스펙 6.4, `/v1/influencers/{id}` 상세)은
  이번 트랙 범위 밖 — 발굴 목록(6.21) 카드만 배선한다. 상세 표면도 이메일을 노출하려면
  별도 후속 작업 필요(동일 `account_summaries.email` 소스 재사용 가능).
- crawler 원본 이메일 수집(email 필드 자체 크롤링)은 팀원 담당 영역, 무관.
