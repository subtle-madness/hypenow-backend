# 그라파나 "브랜드 모니터링" 폴더 분리 설계 — 폴더 + 대시보드 3장

> 상태: ✅ 구현/반영됨(2026-08-19, PR #501 — 대시보드 3장·시드·문서 전부 반영. 단
> **§14-2-2 GRANT 런북은 서버 미적용**이라 운영 개통은 그 실행 이후) ·
> 구현 편차: §3-2 "enrich 잔여"는 24h+ 미처리만 카운트한다
> (`AND first_seen_at < now() - interval '24 hours'` — 자연 처리 대기분 제외, 리뷰 확정) ·
> 2026-08-18 · 선행: [2026-08-18-grafana-dashboard-redesign-design.md](2026-08-18-grafana-dashboard-redesign-design.md)(6탭 개편, ✅ 반영됨) · PR #501(GRANT 런북·표시 다듬기) 후속

## 1. 동기

6탭 개편에서 브랜드 모니터링은 대시보드 1장(패널 7개)이 됐다. 그러나 브랜드 도메인은 운영
건강 외에도 수집 파이프라인(태그 게시물 2.8만·해시태그 감지·enrich·백필)과 광고 표기 판정
(08-17 신설)이라는 관측 수요가 있고, 한 장에 다 넣으면 홈-상세 2계층 규율이 깨진다.
사용자 결정(08-18): **Grafana 폴더 "브랜드 모니터링"을 만들고 그 아래 대시보드 여러 장**을
둔다(참고 이미지: Q-Asker의 폴더 + `[API]` 접두 대시보드 패턴). 이번 범위는 브랜드만 —
경쟁사 등 다른 탭의 폴더화는 이 패턴이 검증된 뒤 별도 작업.

## 2. 구조 — 제2 프로바이더 + 형제 디렉토리 (A안, 사용자 승인)

```yaml
# deploy/grafana/provisioning/dashboards/dashboards.yaml 에 추가
  - name: hypenow-brand-dashboards
    orgId: 1
    folder: 브랜드 모니터링          # 폴더명은 yaml에서 한글 지정
    type: file
    disableDeletion: false
    updateIntervalSeconds: 60
    allowUiUpdates: false
    options:
      path: /etc/grafana/provisioning/dashboards/json-brand
      foldersFromFilesStructure: false
```

- 디렉토리는 `deploy/grafana/provisioning/dashboards/json-brand/`(영문 — macOS↔linux 한글
  NFC/NFD 정규화 차이 회피). 기존 `json/` 프로바이더는 **하위 디렉토리를 재귀 탐색하므로**
  `json/` 안이 아니라 **형제 디렉토리**여야 이중 로드가 없다.
- compose 마운트는 변경 불필요(확인 완료): 운영은 `./grafana/provisioning` 전체,
  dev 하니스는 `../provisioning/dashboards` 전체를 마운트하므로 `json-brand/`가 자동 포함된다.
- `foldersFromFilesStructure: true` 전환(B안)은 6장 전부 재배치가 필요해 기각.
- 폴더명 "브랜드 모니터링"은 기존 폴더 "HypeNow"와 나란히 최상위에 놓인다(Grafana 프로비저닝은
  중첩 폴더를 지원하지 않음 — Q-Asker 참고 이미지와 같은 평면 폴더 구조).

## 3. 대시보드 3장 (사용자 선택: 운영 건강 이관 + 수집 현황·광고 표기 신설)

공통 관용구(전 대시보드 규약 유지): KST 날짜 비교 `(now() AT TIME ZONE 'Asia/Seoul')::date` ·
건강 stat fail-loud(`noValue`+null 매핑 빨강+임계) / 사용량 stat 중립 · 패널·타깃 양쪽
datasource uid · 행 순서 건강→사용량 · 저밀도 일별 timeseries 금지 · 수정은 레포 JSON로만.
데이터소스는 전부 `hypenow-monitoring-pg`(서비스 연결 2패널만 `hypenow-analysis-pg`).

### 3-1. [브랜드] 운영 건강 — `hypenow-brand` (uid 유지, 이동+개명만)

현 `json/hypenow-brand.json`을 `json-brand/`로 옮기고 제목만 "[브랜드] 운영 건강"으로 변경.
패널·쿼리 무변경. **uid를 유지**해 `/d/hypenow-brand` 링크·북마크가 안 깨진다.
(대시보드의 폴더 소속은 프로바이더가 결정하므로 JSON 수정 없이 이동만으로 폴더가 바뀐다.)

### 3-2. [브랜드] 수집 현황 — `hypenow-brand-collection` (신설)

수집 파이프라인 관점: "게시물이 계속 들어오고, enrich가 밀리지 않는가".

| 절 | 패널 | 원천 |
|---|---|---|
| 건강 | 오늘 신규 태그 게시물 (stat) | `brand_tagged_post.first_seen_at` KST 오늘 |
| 건강 | enrich 잔여 (stat, 0=초록) | `brand_tagged_post` `enriched_at IS NULL` 건수 |
| 건강 | 해시태그 감지 7일 verdict 분포 (stat 멀티필드) | `brand_hashtag_post` `first_seen_at` 7일, verdict별 |
| 건강 | 백필 미완 브랜드 (stat, 0=초록) | `brand_account` `last_swept_on IS NULL AND closed_at IS NULL` |
| 사용량 | 태그 게시물 적재 추이 30일 (timeseries) | `brand_tagged_post.first_seen_at` 일별 — 고밀도(일 ~900행)라 timeseries 허용 |
| 사용량 | 해시태그 감지 적재 추이 30일 (timeseries) | `brand_hashtag_post.first_seen_at` 일별 |
| 사용량 | 수집 기간 설정 분포 (stat 멀티필드) | `brand_account.collection_months` (1/3/6/12) |

### 3-3. [브랜드] 광고 표기 — `hypenow-brand-ad` (신설)

`brand_post_meta`의 판정 6컬럼(08-17 스펙) 기반: "판정이 돌고 있고, 미표기가 얼마나 나오나".

| 절 | 패널 | 원천 |
|---|---|---|
| 건강 | 미판정 잔여 (stat) | `judged_caption_hash IS NULL` 건수 — 백필 진행 중엔 큰 수가 정상이라 **중립색**(임계 없음, 예외 승인) |
| 건강 | 오늘 판정 건수 (stat, fail-loud) | `ad_judged_at` KST 오늘 — 0이면 판정 잡 정지 신호 |
| 판정 결과 | verdict 분포 (stat 멀티필드) | `ad_verdict` 4값(DISCLOSED/NOT_DISCLOSED/INSUFFICIENT/UNCERTAIN) |
| 판정 결과 | RULE vs LLM 비율 (stat 멀티필드) | `ad_verdict_source` |
| 판정 결과 | 판정 추이 30일 (timeseries, verdict별) | `ad_judged_at` 일별 |
| 목록 | 최근 NOT_DISCLOSED 15건 (table) | `short_code`·`username`·`ad_judged_at`·`ad_violations` |

- `ad_violations`(jsonb)는 표시 전용으로 그대로 렌더(문자열 캐스팅) — 파싱 로직 없음.
- `caption`·`ad_evidence`는 조회하지 않는다(대시보드에 원문 불필요 — 최소권한).

## 4. GRANT 런북 증보 (§14-2-2 — 아직 미실행이라 지금 고치면 비용 제로)

monitoring DB 블록에 추가(컬럼은 §3의 쿼리가 쓰는 것만, 구현 후 rawSql 기계 추출로 재검산):

- `brand_tagged_post(first_seen_at, enriched_at)`
- `brand_hashtag_post(verdict, first_seen_at)`
- `brand_post_meta(short_code, username, ad_verdict, ad_verdict_source, ad_violations, ad_judged_at, judged_caption_hash)`
- `brand_account`는 기존 GRANT에 `collection_months` 1컬럼 추가

## 5. 하니스 시드 확장

`seed.sql` monitoring 구간에 `brand_post_meta` 시드 추가(광고 판정 분포 포함 — 실측 밀도가
아직 없으므로 4 verdict·2 source가 모두 나오는 임의 분포 + 미판정 NULL 일부). `brand_tagged_post`
`enriched_at NULL` 일부·`brand_account.collection_months` 편차도 넣어 건강 stat의 0/비0 양쪽을
볼 수 있게 한다. `seed-red.sql`에 신설 건강 stat 빨강 재현(오늘 판정 0건·enrich 잔여 급증) 추가.

## 6. 검증·롤아웃

1. 로컬 하니스에서 3장 육안 검증 — 폴더가 나뉘어 보이는지, 신설 패널이 시드로 그려지는지,
   빨강 재현(마운트는 §2대로 무수정 자동 포함).
2. README 갱신: §14-1 탭 목록에 폴더 구조 반영, §14-2-2 GRANT 증보, dev README 밀도표.
3. develop PR → (사용자) §14-2-2 런북 서버 실행 → staging → main. 반영은 CD가 grafana를
   매 배포 재기동하므로 자동(신규 프로바이더도 기동 시 로드).

## 7. 비범위

- 경쟁사 등 다른 탭의 폴더화(이 패턴 검증 후 별도)
- `brand_sweep_run` 이력 테이블 신설(백엔드 트랙)
- 광고 표기 알림(디스코드) — 관측이 먼저, 알림은 임계 감이 잡힌 뒤
