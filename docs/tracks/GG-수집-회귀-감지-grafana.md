# GG — 인스타 수집 회귀 감지 (Grafana 알람 + 대시보드 + 벤더 잔액 지표)

- **소속 트랙군**: 운영 관측(crawler + monitoring 수집 파이프라인)
- **의존**: 없음(감시층만 — 폴백 수정은 별도 브랜치 `fix/self-hiker-fallback-gaps`)
- **상태**: 🔨 PR #791 OPEN(develop 대상, 브랜치 `feat/grafana-collection-alerts`)

## 배경

2026-09-02부터 인스타가 로그아웃 프로필 API(`web_profile_info`)를 401로 막아 crawler COLLECT가
3일간 사실상 100% 실패했다(raw_profile 일건수 정상 2,600~3,850 → 09-03 KST 31). 실패가 방문
트랜잭션 롤백으로 `crawl_run`에조차 남지 않았고 수집량 알람이 없어 09-04에야 발견했다.
사용자 요구: "수집이 잘못되는 경우를 없애야 한다. Grafana까지 연계하라." 이 트랙은 **원인을
묻지 않는 회귀 감지**(어제 값이 최근 7일 중앙값의 절반 미만이거나 절대 하한 밑이면 울린다)로
같은 종류의 무음 실패를 하루 안에 잡는 감시층이다.

## 내용

### 알람 룰(`deploy/grafana/provisioning/alerting/rules.yaml`, 컨택포인트 discord-ops)

공통 판정식: A=어제(KST) 값 → B=직전 7일 중앙값(결측일 0 채움) → C/D=reduce(last) →
E=math `($C < $D * 0.5 && $C < SOFT) || $C < HARD` → F=threshold(E > 0). SOFT=변동 큰 지표의
병행 절대 하한, HARD=중앙값 자체가 무너진 장기 장애도 잡는 최저선. 상향 스파이크는 알람 아님.
일별 룰은 라벨 `kind=daily`로 policies.yaml의 별도 라우트(repeat 24h)를 탄다.

| 그룹(주기) | uid | 지표 | SOFT/HARD | 백테스트 |
|---|---|---|---|---|
| hypenow-collection(1h) | collection-raw-profile-daily | raw_profile 일건수(crawler DB) | 1500/500 | 09-03 KST 31건 **FIRE** |
| | collection-reels-runs-daily | crawl_run REELS SUCCEEDED 런수 | 1500/800 | 사고 무영향(2,499~2,994) |
| | collection-collect-runs-daily | crawl_run COLLECT SUCCEEDED 런수 | 1500/800 | 09-03 KST 41건 **FIRE** |
| | collection-hiker-calls-daily | raw.fetch_payload 일 합계(monitoring) | 1000/500 | 사고 시 오히려 상승(폴백), 오탐 0 |
| | collection-brand-tagged-discovery-daily | brand_tagged_post first_seen_at 일건수 | 150/50 | 변동 337배라 절대 하한 위주 |
| | collection-brand-tagged-enrich-daily | brand_tagged_post enriched_at 일건수 | 30/10 | 08-23(3건) 애매 케이스 1 |
| | collection-self-sweep-daily | Prometheus self fetchPost/fetchComments ok 26h 누적 vs 7d 평균 | 8000/3000, 기준선 게이트 `$D >= 3000` | self 비활성 기간 침묵(dev 실측) |
| | collection-sweep-run-missing | sweep_run 26h 미실행(1) 또는 최근 ok=false(2) | - | 사고 기간 정상 |
| | vendor-hiker-requests-low | `hypenow_vendor_balance{hiker,requests}` < 30,000 | - | 지표 신설(운영 크론 등록 후) |
| | vendor-dataimpulse-residential-low / -mobile-low | 잔여 트래픽 < 4GB / < 500MB | - | 〃 |
| | vendor-hiker-daily-burn-high | Hiker 24h 소비 > 40,000 요청 | - | 09-05 실측 일 소비 ≈28.6k |
| | quality-brand-post-likes-null-daily | brand_post_snapshot likes null(숨김 제외) 비율 > 중앙값×2 && > 2% | - | 09-03 2.54%·09-04 5.93% **FIRE**, 이전 오탐 0 |
| hypenow-collection-fast(10m) | collection-self-success-rate-30m | self ok / (self 전체 + hiker fallback:*) < 50%, 분모 > 30 | - | self 비활성 시 게이트로 무음 |
| | quality-self-partial-30m | fetchComments partial 비율 > 30%, 분모 > 30 | - | 〃 |
| | vendor-balance-stale | 잔액 지표 갱신 1h 초과 또는 무데이터(noDataState Alerting) | - | **크론 등록 전엔 상시 fire(의도)** |

백테스트 전문: [deploy/grafana/backtest/2026-09-04-collection-regression.md](../../deploy/grafana/backtest/2026-09-04-collection-regression.md)
(운영 08-16~09-05 KST SELECT만, 재현 SQL 동봉). 08-18~08-25에 있던 옛 저조 패턴(SELF_GQL 3일마다
~400, REELS ~700)이 raw_profile·REELS·COLLECT 룰에서 fire하지만 08-25 이후 재발 없음 - "당시
알람이 없어 놓친 이상치"로 판단해 하한을 낮추지 않았다.

### 대시보드 `운영 / 수집`(`dashboards/json-ops/hypenow-ops-collection.json`, uid hypenow-ops-collection)

행 7개·패널 38개: 회귀 요약(stat 6) · 회귀 표(crawler/monitoring, 지표·어제·중앙값·비율) ·
crawler 일별 시계열(source별 raw_profile, job별 런·실패 런, actor별 request_count) · monitoring
Hiker(kind별 콜·비200·비용 $0.693/1k) · self 스윕(Prometheus path별, hiker 폴백 비율, **GB/일
추정**=fetchPost 125KB·fetchComments 160KB 환산 근사, partial 비율) · 벤더 잔액·소비(Hiker
요청/USD, DataImpulse 레지·모바일 잔여 GB, 갱신 경과, 일 소비 GB·비용 환산) · 품질(likes/comments
null 비율).

### 지표(신설, 정본 이름)

서버 크론 `deploy/scripts/vendor-balance.sh`(15분)가 Hiker `/sys/balance`(무과금)와 DataImpulse
`gw.dataimpulse.com:777/api/stats`(프록시 계정 basic auth)를 읽어 node-exporter textfile
컬렉터(`~/deploy/textfile/vendor_balance.prom`)로 Prometheus에 올린다. 기존 `.env` 키만 사용,
신규 시크릿 없음.

- `hypenow_vendor_balance{vendor="hiker",unit="requests"|"usd"}`
- `hypenow_vendor_balance{vendor="dataimpulse_residential"|"dataimpulse_mobile",unit="bytes"}`
- `hypenow_vendor_traffic_total_bytes{vendor}` / `hypenow_vendor_traffic_used_bytes{vendor}`
- `hypenow_vendor_balance_scrape_ok{vendor}` / `hypenow_vendor_balance_updated_seconds`

09-05 서버 실계정 실행 실측: Hiker 368,460요청·$254.24, 레지 40.8GB/59GB, 모바일 1.8GB/2.68GB
(전날 대비 Hiker 일 소비 ≈28.6k요청, 레지 ≈1.44GB - 추정 1.75GB/일과 부합).

### 데이터소스·런북

- 신설 `datasources/crawler.yaml` uid `hypenow-crawler-pg`(postgres-raw:5432/crawler,
  grafana_reader). postgres-raw는 **별도 클러스터**라 롤을 새로 만든다.
- 서버 1회 셋업은 [deploy/README.md §14-2-6](../../deploy/README.md)(⚠️ **main 배포 전 실행
  필수**): ① postgres-raw에 grafana_reader CREATE ROLE + 3테이블 집계 컬럼 GRANT(payload 제외)
  ② monitoring `raw` 스키마 USAGE + fetch_payload·post_snapshot·brand_post_snapshot·author_profile
  컬럼 GRANT ③ `~/deploy/textfile` + 크론 등록 ④ 검증.
- CD: `cd.yml`이 provisioning 디렉토리 전체를 이미 나른다(새 파일 자동). 추가한 것은 mkdir
  `~/deploy/textfile`과 scripts scp에 `vendor-balance.sh`. compose node-exporter에
  `--collector.textfile.directory=/textfile` + `./textfile:/textfile:ro`.

### 로컬 검증(dev 하니스 `deploy/grafana/dev/`)

crawler DB·마이그레이션·시드(raw_profile·crawl_run·fetch_payload·snapshot) 추가, 알람 룰·정책은
운영 파일을 그대로 마운트하고 컨택포인트만 싱크(`dev/alerting/contact-points.yaml`)로 대체.
09-05 실측: 룰 17개(기존 1 + 신규 16) 로드 오류 0, 대시보드 "운영" 폴더 등록, seed-red 적용 후
`POST /api/v1/eval`로 raw_profile·REELS·hiker-calls·sweep-run·likes-null 룰 F=1(fire) 확인,
초록 시드에서 0. 브라우저 렌더링 확인(회귀 요약 빨강·회귀 표·crawler 시계열).

## 잔여

- [ ] PR 승인·머지 → staging → main. **main 배포 직전 README §14-2-6 실행**(안 하면 crawler
      데이터소스 실패·vendor-balance-stale 상시 fire).
- [ ] 운영 7일 축적 후 SOFT/HARD 재점검(특히 self 관련 2룰은 self 재개 후 실전 미검증 추정치).
- [ ] 폴백 수정 브랜치(`fix/self-hiker-fallback-gaps`)가 crawler 실패를 crawl_run에 남기기
      시작하면 "실패 런 by job" 패널·FAILED 기반 룰 추가 검토.
- [ ] Grafana admin 비밀번호 교체(08-02 노출 건, 이 트랙 범위 밖 - [memory grafana-dashboard-deploy]).
