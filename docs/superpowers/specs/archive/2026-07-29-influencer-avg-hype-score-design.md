# 인플루언서 발굴 목록 계정 하입 스코어 설계

> 상태: ✅ 구현·운영 반영됨(2026-07-30, 트랙 V 완결)

## 1. 배경·목표

콘텐츠 단건에는 hype_score v2.1(0~100, `analytics.hype_score()` — 도달·참여 로그 축 + 신선도
14일 반감 + 타입별 앵커, 정본 `analytics/views/02_serving.sql`)이 있지만 계정 레벨 점수는 없다.
인플루언서 발굴 목록(`GET /v1/influencers`, 6.21) 카드에 계정 하입 스코어를 노출하고
정렬 옵션을 추가한다.

**적용 표면은 발굴 목록 카드(+정렬)만.** 리포트 상세(/v2)·별도 표면 확장은 이번 범위 아님.
단, 6.23 유사 인플루언서 카드는 발굴 카드와 SELECT·DTO를 공유하므로 자동 포함된다(§5).

## 2. 결정 사항 (2026-07-29 브레인스토밍)

| 질문 | 결정 |
|---|---|
| 적용 표면 | 발굴 목록 카드 + 정렬만 |
| 산식 방향 | 콘텐츠 hype_score 집계(계정 별도 산식 없음) |
| 신선도 | 감쇠 포함 그대로 — "지금 뜨고 있는 계정" 의미. 활동성은 activityDays 필터가 별도 담당 |
| 집계 함수 | 단순 평균 |
| 계산 위치 | **B안: analytics `v_account_summaries` 확장 + 미러** (비채택 대안은 §8) |

## 3. 산식 정의

```
avg_hype_score = round(avg(analytics.hype_score(
                   lower(content_type), views, likes, comments_count, followers,
                   extract(epoch FROM (now() - uploaded_at)) / 86400.0
                 )))::bigint        -- 0~100 정수, 모수는 최근 12창(v_recent_content)
```

- 신선도 인자 `now() − uploaded_at`은 `v_contents`(콘텐츠 랭킹)와 동일 관용구.
  같은 미러 런에서 함께 갱신되므로 카드 점수와 콘텐츠 랭킹 점수의 기준 시각이 일치한다.
- **NULL 규칙**: hype_score NULL인 콘텐츠(피드 likes·comments NULL, 릴스 views NULL)는
  `avg()`가 자연 제외. 스코어 가능 콘텐츠 0개면 계정 점수 NULL — 최소 개수 컷 없음(단순 유지).
- 팔로워는 `v_account_recent`가 프로필 INNER JOIN이라 항상 존재
  (함수 내부 `COALESCE(followers,0)+1000`은 방어로만 남음).
- 모수 정합성 근거: `v_recent_content`(최근창)도 ENUMERATION + QUALIFIED∧beauty∧¬beauty_company
  + `v_pinned_metrics` 기반이라 랭킹 모수(`v_serving_content`→`v_contents`)와 같은 밑판·같은 핀
  스냅샷을 쓴다.

## 4. 변경 지점 — 3곳 동시 변경(ARCHITECTURE §4-5) + was

| 층 | 변경 |
|---|---|
| `analytics/views/10_account_detail.sql` | `v_account_summaries` base CTE에 `avg_hype_score` 집계 추가. win CTE에 `content_type` 통과 필요. SELECT **맨 끝**에 컬럼 추가(미러 1:1 컬럼 순서 규칙) |
| analysis Flyway V-next | `ALTER TABLE account_summaries ADD COLUMN avg_hype_score bigint` — 번호는 머지 직전 재확인(V18 경합 전례) |
| `contract-analysis` `AccountSummary` record | 필드 맨 끝에 `Long avgHypeScore` 추가 — 미러가 타입 기반이라 이것으로 자동 반영 |
| was `V1InfluencerDiscoveryRepository` | SELECT 두 곳(findCards·findCardsByHandles 공유 SELECT)에 `su.avg_hype_score`, `CardRow`에 필드 추가 |
| was 카드 DTO·어셈블러 | `InfluencerCard`에 `hypeScore`(Integer, null 가능) 추가 |
| was 정렬 | `orderBy`에 `case "hype" -> "ORDER BY su.avg_hype_score DESC NULLS LAST, a.handle"`, `V1InfluencerDiscoveryQuery` sort 허용값에 `hype` 추가. 기본 정렬(views_per_follower) 불변 |

## 5. API 표면

- `GET /v1/influencers` 카드 응답에 `hypeScore`(정수 0~100, null 가능) 추가, `sort=hype` 추가.
- 6.23 유사 인플루언서 카드(`findCardsByHandles` 재사용)에도 `hypeScore`가 자동 포함 — 별도
  작업 없이 일관성이 생기는 방향이라 의도적으로 그대로 둔다.
- **프론트 통지 필요**: 새 필드·정렬 옵션. null일 때 표시 정책("-" 등)은 프론트 몫,
  정렬은 NULLS LAST라 null 계정이 뒤로 간다.

## 6. 엣지·배포 리스크

- **배포 순서** *(07-29 최종 리뷰에서 정정 — 초안의 "뷰 선적용" 순서는 위험)*: 배포 정본은
  CD(develop→main). cd.yml 순서(컨테이너 pull·기동 → 뷰 적용)가 안전한 순서다 — was는
  compose `depends_on: analytics healthy`(=Flyway V41 완료)를 대기하므로 `su.avg_hype_score`
  미존재 500 레이스가 없고, "신 record ↔ 구 뷰" 창은 초 단위라 무해하다.
  **뷰 수동 선적용 금지**: 구 analytics 이미지(26컬럼 record)가 도는 상태에서 신 뷰(27컬럼)를
  먼저 적용하면 미러 런타임 가드(verifyColumns)가 던져 등록부 후순위 미러까지 그날 전부
  스킵된다(데이터 파손은 없음 — TRUNCATE 전에 실패). 수동 적용이 꼭 필요하면 analytics
  재배포 **이후**에.
- **배포 직후 운영 절차**: 스케줄 미러는 KST 04:30뿐이라 첫 미러 전까지 hypeScore는 전건
  NULL(sort=hype는 handle 순). 배포 → 수동 미러 1회(`ssh` 터널 후
  `curl -X POST 127.0.0.1:8082/ui/jobs/mirror`) → 값 스팟체크 → 그다음 프론트 통지 순서로.
- 정렬은 미러 테이블 실컬럼 기준이라 상관 서브쿼리 성능 이슈 없음.
- 앵커·상수 튜닝은 기존 콘텐츠 hype와 동일 `app_setting` 키를 그대로 공유(함수 재사용이므로
  계정 쪽 별도 상수 없음). 콘텐츠 hype 재보정 시 계정 점수도 자동 추종한다.

## 7. 테스트

- SQL 하니스(`analytics/test/`, 10번 테스트에 케이스 추가):
  1. NULL 혼재 창의 평균 — NULL 콘텐츠가 분모에서 빠지는지.
  2. 창 전체 스코어 불가 → 계정 avg_hype_score NULL.
  3. 동일 입력에서 `v_contents.hype_score`와 `v_account_summaries` 집계 재료 점수 일치
     (같은 함수·같은 핀 지표 확인).
- was: 발굴 목록 테스트에 `sort=hype` 정렬·응답 필드 케이스 추가.

## 8. 비채택 대안

- **A. was 인라인 집계**(`account_content_series ⋈ contents` 상관 서브쿼리): analytics 무변경으로
  가장 가볍지만, 계정 집계 지표의 산식 정본이 was로 새는 예외를 만들고 정렬 시 필터된 전
  계정에 상관 서브쿼리가 돈다(유사도 v2의 성능 절벽 전례). 기각.
- **C. analysis DB 파생 뷰**(V35 패턴): 계약·미러 무변경이지만, 파생 뷰 패턴은 "크로스 DB라
  미러 불가"일 때의 예외였고 이 건은 본선(analytics 뷰)에서 계산 가능하므로 예외 사유가 없다. 기각.
