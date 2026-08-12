# Z — 하입 스코어 v3 (감쇠를 매핑 뒤로) + 계정 점수 척도 재교정

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: —
- **상태**: 🔨 (07-30분 구현·PR·운영 뷰 적용·미러 전부 완료 — 잔여는 프론트 통지뿐. 07-31 후속(§11,
  계정 점수 고정 분모)은 구현 완료·PR 대기, 운영 미반영)

## 내용

발굴 목록 피드 편향(피드 ≥70점 7.61% vs 릴스 3.98%, 상위 50 계정 피드비율 중앙값 0.70 vs 전체 0.18)의 원인이 `analytics.hype_score()` 앵커를 감쇠 후 `qf`에 적합시켜 캘리브레이션이 코퍼스 연령 구성에 오염된 것으로 규명. 감쇠를 앵커 매핑 **뒤로** 옮겨 `점수 = clamp(map_Q(Q), 0, 100) × 0.5^(경과일/halflife)`로 재정의(클램프는 감쇠 전), 앵커 8개는 **감쇠 전 Q 기준·전체 서빙 코퍼스**로 재적합(운영 실측이 스펙값과 n까지 정확히 일치 확인 — 07-30). app_setting 앵커 키를 `hype-anchor-q-*`로 개명(구 키는 무시 — 조용한 오염 방지). Java에는 계산 로직이 없어(계약 record는 미러 값 통과) 수정은 SQL 함수 + 계약 Javadoc뿐. **후속(같은 날, §9)**: test 반영 실측으로 계정 점수(`avg_hype_score`, 최근창 평균) 척도가 무너진 것을 발견(최대 59점·0점 계정 20.72% — 창 스팬에 걸친 개별 콘텐츠 감쇠를 평균 내다 보니 오래된 창 뒤쪽이 평균을 누름). 게시 빈도가 계정 점수를 좌우하는 것은 의도된 동작(사용자 결정) — 순위는 유지하고 척도만 콘텐츠와 동일한 4점 구간선형 매핑 함수 `analytics.hype_account_score()` 신설로 재교정(앵커는 계정 raw 평균 0점 제외 분위수로 별도 적합, `hype-anchor-acct-*`). 콘텐츠 산식·반감기는 불변. **재후속(같은 날, §9-6)**: 재교정 배포 후 발굴 목록 상위권 순서가 뒤섞임 발견 — 매핑 최상단 구간이 raw 44.86~58.92(상위 1% 전체)를 정수 97~100 4개 값으로 압축해(≥97점 54개 계정에 서로 다른 점수값 4개뿐) `ORDER BY avg_hype_score DESC, handle`의 동점 처리가 상위권을 사실상 handle 알파벳순으로 지배. 표시값과 정렬 키를 분리해 해결: `v_account_summaries`에 정렬 전용 `avg_hype_raw`(반올림 전 평균) 컬럼 신설(Flyway V49·contract `AccountSummary.avgHypeRaw`, `CREATE OR REPLACE VIEW` 중간 삽입 불가 제약으로 셋 다 맨 끝에 추가), was `V1InfluencerDiscoveryRepository`의 `sort=hype`를 `avg_hype_score`→`avg_hype_raw`로 교체. 표시는 계속 avg_hype_score, API 응답엔 avg_hype_raw 비노출. 콘텐츠 점수는 서빙 코퍼스가 110,488건이라 상위 1%가 1,100건 이상으로 퍼져 같은 문제가 없음 — [specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md §9](../superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md) [plans/2026-07-30-hype-score-v3-decay-after-mapping.md](../superpowers/plans/archive/2026-07-30-hype-score-v3-decay-after-mapping.md). **재재후속(같은 날, §10)**: 랭킹 경로(`is_beauty AND (metric_timeliness='timely' OR NULL)`) 실측(n=5,683)에서 콘텐츠 점수도 p05=5·p50=23·p90=44·p99=60.8·**max=76**로 척도 상단(77~100)이 미사용임을 확인 — 콘텐츠 Q 앵커가 전체 서빙 코퍼스(n=117,600)에 맞춰져 있는데 실제 노출은 그보다 훨씬 좁은 랭킹 경로 모수뿐이라 좁혀 보면 하단에 몰린 것. 타입 무관 단일 출력 매핑 `analytics.hype_score_output(raw)` 신설(같은 4점 구간선형, 앵커 `5/23/44/60.8` — 타입 정규화는 Q 앵커가 이미 끝냈으므로 재분기 안 함)로 척도를 다시 편다. 동시에 정수 반올림이 랭킹 경로(n=5,683, 계정과 비슷한 규모)에서도 동점을 만드는 걸 막기 위해 표시·정렬을 소수 4자리로 전환 — `contents.hype_score_precise`·`account_summaries.avg_hype_score_precise`(둘 다 numeric, Flyway `V20260730122340`) 신설. 계정 앵커도 콘텐츠 출력 매핑 반영 새 raw 기준으로 별도 재적합(`analytics.hype_account_score_precise`, 앵커 `1.4856/23.6566/56.3961/77.0479`, `hype-anchor-acct-precise-*`) — `hype_account_score`(구)의 기본값을 직접 바꾸지 않은 이유는 `avg_hype_score`(bigint)가 그 함수로 정의돼 값이 같이 바뀌기 때문(값·의미 불변 계약 위반). was 표시·정렬은 `hype_score`·`avg_hype_score`(구, 정수)에서 `hype_score_precise`·`avg_hype_score_precise`(신, 소수)로 전환, 구 컬럼 3종(`contents.hype_score`·`account_summaries.avg_hype_score`·`avg_hype_raw`)은 값·의미 불변 유지 후 다음 릴리스 DROP 대상 — [specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md §10](../superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md)

**07-30 운영 뷰 적용·미러·스팟체크 완료**: `staging → main` 승격(PR #247)으로 위 뷰·컬럼 전부가
운영에 반영된 뒤, 수동 미러를 실행해 데이터를 채웠다 — 6개 타깃 235,272행, 소요 약 6분 8초
(15:37:52Z→15:44:00Z): accounts 7,095 / contents 138,755 / content_comments 0 /
account_summaries 7,033 / account_content_series 82,388 / landing_stats 1.
`account_summaries.avg_hype_raw`는 6,869계정에 값이 채워짐(전체 7,033행 중 — 나머지는 최근 12창
콘텐츠가 없는 계정으로 추정, NULL 자체는 정상). 정렬키 스팟체크: 표시값 99 구간에서 raw가
55.14 → 53.17로 갈리는 사례를 확인 — §9-6이 의도한 대로 반올림 동점이 `avg_hype_raw` 정렬로
정확히 분리됨을 운영 데이터로 검증. (07-30분 잔여였던 프론트 통지는 07-31 후속과 함께 처리 예정.)

**07-31 후속(§11) — 계정 점수 표본 하한 없음 해소(고정 분모)**: test 스택 실측으로 최근창
점수산출 콘텐츠가 1~2건뿐인 계정이 상위권에 섞이는 걸 확인(`ynp.ny` 2건 7위·`sunyvvin` 1건
8위·`zero_lyrical` 1건 12위) — `docs/hype-score.md` §7에 이미 기록돼 있던 "계정 점수 표본
하한 없음" 결함의 원인을 실측: 창이 12로 꽉 찬 계정 6,350개 중 2,633개(41%)가 점수산출 콘텐츠
<12건이고 결손의 99.9%가 `likes`/`comments_count` NULL(크롤러 수집 누락). **사용자 결정**:
원인이 수집 누락이어도 감점한다 — 점수산출 불가 게시물은 인플루언서 상세 화면(최근 12개
카드)에 애초에 뜨지 않아 유저 입장에서 "1개만 올린 계정"과 구분되지 않으므로 화면·점수
정합성이 우선. `v_account_summaries.avg_hype_precise_raw`를 `avg(콘텐츠 출력매핑 점수)`
(분모=창에 실제로 든 콘텐츠 수)에서 `sum(...) / analytics.recent-window`(분모=창 크기 고정,
새 상수 없이 `01_recent_window.sql`과 동일 설정 키 재사용)로 교체 — `sum()`이 NULL을 무시해
점수산출 불가 콘텐츠는 분자에 0 기여로 자연 감점되고, 창 전체 NULL이면 `sum()`도 NULL이라
"창 전체 점수 불가 → NULL" 계약은 유지된다. 분모 변경으로 raw 분포가 하향 이동해 계정 소수
앵커 재적합(`1.4856/23.6566/56.3961/77.0479` → `1.2417/19.4383/52.2401/74.0179`,
`hype-anchor-acct-precise-*` 키는 그대로, COALESCE 기본값만 교체). `avg_hype_score`(bigint)·
`avg_hype_raw`(구, 값·의미 불변 동결·다음 릴리스 DROP 대상)는 무변경. 회귀 테스트 3종을
`10_account_score_rescale.test.sql`에 추가 — 핵심 회귀는 개별 콘텐츠 점수가 완전히 동일한
1건 vs 12건 계정을 만들어, 구 코드(avg)라면 두 계정의 raw가 완전히 같아(66.3988... 동일 →
91.0525 동일) 이 단언이 실패했을 것을 테스트 추가 전 수동 SQL로 직접 확인한 뒤, 신 코드에서
뚜렷한 격차(18.2545 vs 91.0525, 약 4.99배, 여유를 두고 3배로 단언)를 검증. 그 외 창 전체
NULL 보존·0점 보존 회귀도 추가. 마이그레이션 없음(새 컬럼 없음, 함수 COALESCE 기본값·뷰
SQL만 교체) — `analytics/test/run.sh` ALL GREEN. **잔여**: PR·운영 뷰 적용·미러·프론트
통지(07-30분과 함께 처리) 전부 대기 — [specs/2026-07-31-account-score-fixed-denominator-design.md](../superpowers/specs/2026-07-31-account-score-fixed-denominator-design.md)
