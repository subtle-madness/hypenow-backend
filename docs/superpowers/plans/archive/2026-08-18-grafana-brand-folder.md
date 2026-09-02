# 그라파나 "브랜드 모니터링" 폴더 분리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행됨(2026-08-19, 태스크 5/5 완료 — PR #501) · 스펙: [2026-08-18-grafana-brand-folder-design.md](../../specs/2026-08-18-grafana-brand-folder-design.md)

**Goal:** Grafana 폴더 "브랜드 모니터링"을 신설하고 그 아래 대시보드 3장([브랜드] 운영 건강 이관 + 수집 현황·광고 표기 신설)을 둔다.

**Architecture:** 제2 파일 프로바이더(`json-brand/` 형제 디렉토리, 폴더명은 yaml 한글 지정) — 기존 HypeNow 폴더 5장 무변경. 신설 2장은 하니스 시드 확장으로 육안 검증 후 §14-2-2 GRANT 런북을 증보한다(런북 미실행 상태라 지금 고치면 비용 제로).

**Tech Stack:** Grafana 13.1.1 파일 프로비저닝 · PostgreSQL(monitoring DB) · 로컬 하니스(`deploy/grafana/dev`, localhost:3300)

## Global Constraints (전 태스크 공통 — CLAUDE.md·인수인계 관용구)

- KST 날짜 비교는 반드시 `(now() AT TIME ZONE 'Asia/Seoul')::date`. 일별 버킷은
  `date_trunc('day', <col> AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'`(KST 자정 경계 timestamptz).
- 건강 stat = fail-loud(`noValue: "데이터 없음"` + special null 매핑 빨강 + 임계) / 사용량·분포 stat = 중립(`colorMode: "none"`, 매핑 없음).
- 패널·타깃 **양쪽에** `datasource: {"type":"grafana-postgres-datasource"... uid}` 지정(패널 레벨 누락 시 에러 실측).
- 행 순서 건강 → 사용량. 수정은 레포 JSON로만(`allowUiUpdates: false`). 커밋은 한국어 `feat(deploy):`/`chore(deploy):`.
- JSON 편집 후 60초 내 하니스 자동 반영(`updateIntervalSeconds: 60`). 급하면 `docker compose -f deploy/grafana/dev/compose.dev.yaml restart grafana`.
- 신설 stat/table의 구조 관용구(fieldConfig·options 골격)는 기존 파일에서 복사한다 — fail-loud stat은 `json/hypenow-home.json`의 "멈춘 등록" 패널, 중립 멀티필드 stat은 `json/hypenow-brand.json`의 "오늘 스윕 성공" 패널, table은 `json/hypenow-competitor.json`의 "발송 실패 알림" 패널이 각각 기준 견본.
- 작업 브랜치는 현 `feature/grafana-dashboard-followup-4be368`(PR #501에 커밋 적층 — 런북 증보가 #501의 런북과 한 몸이라 같은 PR이 맞다).

---

### Task 1: 제2 프로바이더 + [브랜드] 운영 건강 이관

**Files:**
- Modify: `deploy/grafana/provisioning/dashboards/dashboards.yaml`
- Move: `deploy/grafana/provisioning/dashboards/json/hypenow-brand.json` → `deploy/grafana/provisioning/dashboards/json-brand/hypenow-brand.json`

**Interfaces:**
- Produces: 폴더 "브랜드 모니터링"(Grafana), 디렉토리 `json-brand/` — Task 3·4가 여기에 신규 JSON을 넣는다. uid `hypenow-brand` 불변.

- [ ] **Step 1: 프로바이더 추가** — `dashboards.yaml` 끝에 추가(들여쓰기는 기존 provider와 동일):

```yaml
  # 브랜드 모니터링 폴더(2026-08-18 스펙) — json/ 프로바이더가 하위 디렉토리를 재귀 탐색하므로
  # 형제 디렉토리(json-brand)여야 이중 로드가 없다. 폴더명은 여기(yaml)서 한글 지정 —
  # 디렉토리명을 영문으로 두는 이유는 macOS↔linux 한글 NFC/NFD 정규화 차이 회피.
  - name: hypenow-brand-dashboards
    orgId: 1
    folder: 브랜드 모니터링
    type: file
    disableDeletion: false
    updateIntervalSeconds: 60
    allowUiUpdates: false
    options:
      path: /etc/grafana/provisioning/dashboards/json-brand
      foldersFromFilesStructure: false
```

- [ ] **Step 2: 이동 + 개명**

```bash
mkdir -p deploy/grafana/provisioning/dashboards/json-brand
git mv deploy/grafana/provisioning/dashboards/json/hypenow-brand.json deploy/grafana/provisioning/dashboards/json-brand/
```

`json-brand/hypenow-brand.json`의 최상위 `"title"`을 `"HypeNow 브랜드 모니터링"` → `"[브랜드] 운영 건강"`으로 변경. **uid·패널·쿼리는 무변경.**

- [ ] **Step 3: 하니스 검증** — 프로바이더 추가는 기동 시에만 읽히므로 재시작 필수:

```bash
docker compose -f deploy/grafana/dev/compose.dev.yaml restart grafana
```

브라우저 `http://localhost:3300/dashboards`에서 확인: ① 폴더 "브랜드 모니터링"이 생기고 그 안에 "[브랜드] 운영 건강" 1장 ② HypeNow 폴더는 5장(브랜드 빠짐) ③ `/d/hypenow-brand` 직링크가 여전히 열리고 패널이 그려진다(중복 대시보드 경고 없음 — 이중 로드 검증).

- [ ] **Step 4: 커밋**

```bash
git add deploy/grafana/provisioning/dashboards/
git commit -m "feat(deploy): 그라파나 '브랜드 모니터링' 폴더 신설 — 제2 프로바이더 + 운영 건강 이관"
```

---

### Task 2: 하니스 시드 확장 (brand_post_meta + enrich 분포 + 빨강 재현)

**Files:**
- Modify: `deploy/grafana/dev/seed.sql` (monitoring 구간, `-- END monitoring` 직전에 삽입)
- Modify: `deploy/grafana/dev/seed-red.sql` (monitoring 구간 끝에 추가)

**Interfaces:**
- Produces: `brand_post_meta` 시드 8,000행(판정 60%·미판정 40%), `brand_tagged_post.enriched_at` 분포 조정(오래 미처리 소수), `brand_account.collection_months` 편차. Task 3·4 패널이 이 데이터로 그려진다.

- [ ] **Step 1: seed.sql monitoring 구간에 추가** — 기존 `brand_tagged_post` INSERT와 스윕 시각 UPDATE 사이에 삽입:

```sql
-- 광고 표기 판정 시드(brand_post_meta 8,000 — 실측 밀도 없음: 08-17 신설·백필 진행 중 가정).
-- 판정 60%(g%5<3): verdict는 DISCLOSED 위주 4값, source RULE 70%/LLM 30%,
-- ad_judged_at은 최근 30일 + 오늘 확정분(g<=120은 오늘 새벽 — '오늘 판정' stat이 0이 안 되게).
-- 미판정 40%: judged_caption_hash NULL(잔여 스톡 — '미판정 잔여' stat).
TRUNCATE brand_post_meta;
INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption,
                             thumbnail_url, first_seen_at,
                             ad_verdict, ad_verdict_source, ad_violations, ad_evidence,
                             ad_judged_at, judged_caption_hash)
SELECT 'TP' || g, 'author' || (g % 2000),
       CASE WHEN g % 4 = 0 THEN 'FEED' ELSE 'REELS' END,
       (now() - (random() * 180 || ' days')::interval)::date,
       '목 캡션 ' || g,
       'https://mock.test/m/' || g || '.jpg',
       now() - (random() * 40 || ' days')::interval,
       CASE WHEN g % 5 >= 3 THEN NULL
            WHEN g % 20 = 0 THEN 'UNCERTAIN'
            WHEN g % 10 = 1 THEN 'INSUFFICIENT'
            WHEN g % 7  = 0 THEN 'NOT_DISCLOSED'
            ELSE 'DISCLOSED' END,
       CASE WHEN g % 5 >= 3 THEN NULL WHEN g % 10 < 7 THEN 'RULE' ELSE 'LLM' END,
       CASE WHEN g % 5 < 3 AND g % 7 = 0 THEN '["HIDDEN_PLACEMENT"]'::jsonb END,
       NULL,
       CASE WHEN g % 5 >= 3 THEN NULL
            WHEN g <= 120 THEN ((now() AT TIME ZONE 'Asia/Seoul')::date::timestamp AT TIME ZONE 'Asia/Seoul')
                               + interval '3 hours' + (g || ' seconds')::interval
            ELSE now() - (random() * 30 || ' days')::interval END,
       CASE WHEN g % 5 < 3 THEN md5('목 캡션 ' || g) END
FROM generate_series(1, 8000) g;

-- enrich 분포 조정(수집 현황 'enrich 잔여' stat용): 기존 시드는 25%가 무기한 NULL이라
-- 잔여 스탯이 상시 수천으로 뜬다 — 하루 넘게 미처리는 전부 메워 초록 시드의 잔여를 0으로.
-- 24h 이내 유입분의 NULL(자연 처리 대기)은 그대로 둔다 — '오늘' 타일들과 마찬가지로
-- 하니스 시드는 24시간 내 재적용 전제(시간이 지나면 이 대기분이 창을 넘어 잔여로 늙는다).
UPDATE brand_tagged_post SET enriched_at = first_seen_at + interval '2 hours'
 WHERE enriched_at IS NULL AND first_seen_at < now() - interval '24 hours';

-- (collection_months는 기존 brand_account INSERT가 이미 (ARRAY[1,3,6,12])[1+(g%4)]로
--  편차를 넣고 있어 별도 조정 불필요 — 리뷰 확인)
```

주의: `TRUNCATE brand_post_meta`는 이 INSERT 직전에 필요하다 — 구간 첫머리
`TRUNCATE brand_account ... CASCADE`는 brand_post_meta(게시물 전역 테이블, FK 없음)를 안 지운다.

- [ ] **Step 2: seed-red.sql monitoring 구간 끝에 추가**

```sql
-- [브랜드] 수집 현황 빨강 — 오늘 신규 태그 게시물 0(스윕 불발 양상) + 백필 미완 브랜드 4
-- + enrich 잔여 600(빨강 임계 500 초과)
DELETE FROM brand_tagged_post
 WHERE (first_seen_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date;
UPDATE brand_account SET last_swept_on = NULL WHERE id IN (1, 2, 3, 4);
UPDATE brand_tagged_post SET enriched_at = NULL
 WHERE short_code IN (SELECT short_code FROM brand_tagged_post
                       WHERE first_seen_at < now() - interval '24 hours'
                       ORDER BY short_code LIMIT 600);

-- [브랜드] 광고 표기 빨강 — 오늘 판정 0건(판정 잡 정지 양상)
UPDATE brand_post_meta SET ad_judged_at = ad_judged_at - interval '2 days'
 WHERE (ad_judged_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date;
```

- [ ] **Step 3: 시드 재적용 + 수치 검증** (레포 루트에서 — zsh는 `$C` 관용구가 안 되니 명령을 풀어 쓴다):

```bash
sed -n '/^-- BEGIN monitoring/,/^-- END monitoring/p' deploy/grafana/dev/seed.sql | docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -q -U dev -d monitoring
docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -U dev -d monitoring -c "SELECT count(*) FILTER (WHERE judged_caption_hash IS NOT NULL) AS judged, count(*) FILTER (WHERE judged_caption_hash IS NULL) AS pending, count(*) FILTER (WHERE (ad_judged_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date) AS today FROM brand_post_meta" -c "SELECT count(*) AS enrich_backlog FROM brand_tagged_post WHERE enriched_at IS NULL AND first_seen_at < now() - interval '24 hours'" -c "SELECT collection_months, count(*) FROM brand_account GROUP BY 1 ORDER BY 1"
```

기대: judged = 4,800 · pending = 3,200 · today ≥ 72(고정분 72 + 랜덤 분기가 오늘로 떨어진 덤 —
실행 시각에 비례) · enrich_backlog = 0 · collection_months 4분포(각 32~33).

- [ ] **Step 4: 커밋**

```bash
git add deploy/grafana/dev/seed.sql deploy/grafana/dev/seed-red.sql
git commit -m "chore(deploy): 하니스 시드 확장 — brand_post_meta 광고 판정·enrich 분포·collection_months 편차 + 빨강 재현"
```

---

### Task 3: [브랜드] 수집 현황 대시보드 신설

**Files:**
- Create: `deploy/grafana/provisioning/dashboards/json-brand/hypenow-brand-collection.json`

**Interfaces:**
- Consumes: Task 1의 `json-brand/` 디렉토리, Task 2의 시드.
- Produces: uid `hypenow-brand-collection`. Task 5의 GRANT 재검산 대상.

- [ ] **Step 1: JSON 작성** — 최상위 골격은 기존 hypenow-brand.json 복사(실측: `"timezone": "Asia/Seoul"`, `"refresh": "5m"`, `"time": {"from": "now-7d", "to": "now"}`, `"editable": false`, `"schemaVersion": 39, "tags": ["hypenow", "brand"]`) 후 `"uid": "hypenow-brand-collection"`, `"title": "[브랜드] 수집 현황"`으로 교체. 행 2개(건강 → 사용량), 패널·타깃 양쪽 datasource uid `hypenow-monitoring-pg`. 패널 8개:

행 1 「건강」 (stat 4개, h=5, w=6씩):

1. **오늘 신규 태그 게시물** — fail-loud 견본(홈 "멈춘 등록") 복사 + 매핑은 홈 타일 6 관용구(0 → 텍스트 "스윕 확인" 빨강). textMode `value`:
```sql
SELECT count(*) AS value FROM brand_tagged_post
WHERE (first_seen_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date
```
2. **enrich 잔여 (24h+)** — fail-loud, thresholds green 0 / yellow 1 / red 500, textMode `value`:
```sql
SELECT count(*) AS value FROM brand_tagged_post
WHERE enriched_at IS NULL AND first_seen_at < now() - interval '24 hours'
```
3. **백필 미완 브랜드** — fail-loud, thresholds green 0 / yellow 1 / red 5, textMode `value`:
```sql
SELECT count(*) AS value FROM brand_account WHERE last_swept_on IS NULL AND closed_at IS NULL
```
4. **해시태그 감지 7일** — 중립 멀티필드 견본("오늘 스윕 성공") 복사, textMode `value_and_name`:
```sql
SELECT count(*) FILTER (WHERE verdict = 'RELEVANT')  AS "관련",
       count(*) FILTER (WHERE verdict = 'UNCERTAIN') AS "불확실",
       count(*) FILTER (WHERE verdict = 'IRRELEVANT') AS "무관",
       count(*) FILTER (WHERE verdict IN ('SELF', 'DIRECT_TAGGED')) AS "자사·직태그"
FROM brand_hashtag_post WHERE first_seen_at > now() - interval '7 days'
```

행 2 「사용량」 (timeseries 2 + stat 1):

5. **태그 게시물 적재 추이 30일** (timeseries, w=9) — KST 일 경계(Global Constraints 식):
```sql
SELECT date_trunc('day', first_seen_at AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul' AS time,
       count(*) AS "신규 태그 게시물"
FROM brand_tagged_post
WHERE first_seen_at > now() - interval '30 days'
GROUP BY 1 ORDER BY 1
```
6. **해시태그 감지 추이 30일** (timeseries, w=9) — 같은 골격, `brand_hashtag_post` 대상, 시리즈명 "신규 감지 게시물".
7. **수집 기간 설정 분포** (stat 멀티필드 중립, w=6):
```sql
SELECT count(*) FILTER (WHERE collection_months = 1)  AS "1개월",
       count(*) FILTER (WHERE collection_months = 3)  AS "3개월",
       count(*) FILTER (WHERE collection_months = 6)  AS "6개월",
       count(*) FILTER (WHERE collection_months = 12) AS "12개월"
FROM brand_account WHERE closed_at IS NULL
```

- [ ] **Step 2: 하니스 육안 검증** — 60초 대기(또는 grafana restart) 후 `http://localhost:3300/d/hypenow-brand-collection`: 7패널 전부 그려지고(No data 없음), 타일 1 오늘 신규 > 0 초록, 타일 2 = 0 초록, 타일 3 = 0 초록, 추이 2장이 30일 막대·KST 경계로 렌더.

- [ ] **Step 3: 커밋**

```bash
git add deploy/grafana/provisioning/dashboards/json-brand/hypenow-brand-collection.json
git commit -m "feat(deploy): [브랜드] 수집 현황 대시보드 — 태그 게시물·해시태그 감지·enrich·백필 관측"
```

---

### Task 4: [브랜드] 광고 표기 대시보드 신설

**Files:**
- Create: `deploy/grafana/provisioning/dashboards/json-brand/hypenow-brand-ad.json`

**Interfaces:**
- Consumes: Task 2의 `brand_post_meta` 시드.
- Produces: uid `hypenow-brand-ad`. Task 5의 GRANT 재검산 대상.

- [ ] **Step 1: JSON 작성** — 최상위 골격은 Task 3과 동일(uid `hypenow-brand-ad`, title `[브랜드] 광고 표기`). 행 3개(건강 → 판정 결과 → 목록), datasource 전부 `hypenow-monitoring-pg`. 패널 6개:

행 1 「건강」:

1. **오늘 판정 건수** (stat fail-loud, w=12) — 매핑 0 → "판정 잡 확인" 빨강, textMode `value`:
```sql
SELECT count(*) AS value FROM brand_post_meta
WHERE (ad_judged_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date
```
2. **미판정 잔여** (stat **중립**, w=12 — 백필 진행 중 큰 수가 정상, 스펙 §3-3 예외 승인. colorMode `none`, 매핑·임계 없음, textMode `value`, unit `locale`):
```sql
SELECT count(*) AS value FROM brand_post_meta WHERE judged_caption_hash IS NULL
```

행 2 「판정 결과」:

3. **verdict 분포** (stat 멀티필드 중립, w=6):
```sql
SELECT count(*) FILTER (WHERE ad_verdict = 'DISCLOSED')     AS "표기",
       count(*) FILTER (WHERE ad_verdict = 'NOT_DISCLOSED') AS "미표기",
       count(*) FILTER (WHERE ad_verdict = 'INSUFFICIENT')  AS "판단 불충분",
       count(*) FILTER (WHERE ad_verdict = 'UNCERTAIN')     AS "불확실"
FROM brand_post_meta WHERE ad_verdict IS NOT NULL
```
4. **판정 경로** (stat 멀티필드 중립, w=6):
```sql
SELECT count(*) FILTER (WHERE ad_verdict_source = 'RULE') AS "규칙",
       count(*) FILTER (WHERE ad_verdict_source = 'LLM')  AS "LLM"
FROM brand_post_meta WHERE ad_verdict_source IS NOT NULL
```
5. **판정 추이 30일** (timeseries, w=12) — `metric` 컬럼이 시리즈명이 된다(Grafana postgres 규약):
```sql
SELECT date_trunc('day', ad_judged_at AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul' AS time,
       ad_verdict AS metric, count(*) AS value
FROM brand_post_meta
WHERE ad_judged_at > now() - interval '30 days'
GROUP BY 1, 2 ORDER BY 1
```

행 3 「목록」:

6. **최근 미표기 15건** (table, w=24, noValue "미표기 없음 (정상)" — 견본 "발송 실패 알림"):
```sql
SELECT ad_judged_at AS "판정 시각", short_code AS "게시물", username AS "작성자",
       ad_violations::text AS "위반 코드"
FROM brand_post_meta WHERE ad_verdict = 'NOT_DISCLOSED'
ORDER BY ad_judged_at DESC LIMIT 15
```
(`caption`·`ad_evidence`는 조회하지 않는다 — 스펙 §3-3 최소권한.)

- [ ] **Step 2: 하니스 육안 검증(초록)** — `/d/hypenow-brand-ad`: 6패널 렌더, 오늘 판정 = 72 초록, 미판정 잔여 = 3,200 중립색(locale "3,200"), verdict 4필드·경로 2필드 전부 값 있음, 미표기 목록 15행.

- [ ] **Step 3: 빨강 재현 검증** — seed-red 적용 후 확인, 검증 끝나면 초록 시드로 복원:

```bash
sed -n '/^-- BEGIN monitoring/,/^-- END monitoring/p' deploy/grafana/dev/seed-red.sql | docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -q -U dev -d monitoring
```

확인: 광고 표기 "오늘 판정 건수" 0 → "판정 잡 확인" 빨강 · 수집 현황 "오늘 신규 태그 게시물" 0 → "스윕 확인" 빨강 · "백필 미완 브랜드" 4 노랑 · "enrich 잔여" 600 빨강. 복원:

```bash
sed -n '/^-- BEGIN monitoring/,/^-- END monitoring/p' deploy/grafana/dev/seed.sql | docker compose -f deploy/grafana/dev/compose.dev.yaml exec -T postgres psql -v ON_ERROR_STOP=1 -q -U dev -d monitoring
```

- [ ] **Step 4: 커밋**

```bash
git add deploy/grafana/provisioning/dashboards/json-brand/hypenow-brand-ad.json
git commit -m "feat(deploy): [브랜드] 광고 표기 대시보드 — 판정 분포·경로·추이·미표기 목록"
```

---

### Task 5: GRANT 런북 증보 + 문서 갱신 + 재검산

**Files:**
- Modify: `deploy/README.md` (§14-1 탭 목록, §14-2-2 monitoring 블록)
- Modify: `deploy/grafana/dev/README.md` (밀도표·"운영과 다른 점")

**Interfaces:**
- Consumes: Task 3·4의 최종 rawSql.

- [ ] **Step 1: rawSql 기계 추출 재검산** — 신설 2장 + 이관 1장의 실제 조회 컬럼을 뽑아 아래 GRANT와 대조(불일치 시 GRANT를 JSON에 맞춘다):

```bash
python3 - <<'EOF'
import json, glob
for f in sorted(glob.glob('deploy/grafana/provisioning/dashboards/json-brand/*.json')):
    d = json.load(open(f))
    def walk(ps):
        for p in ps:
            yield p
            yield from walk(p.get('panels', []))
    for p in walk(d['panels']):
        for t in p.get('targets', []):
            if t.get('rawSql'): print(f"[{f.split('/')[-1]}] {t['rawSql']}")
EOF
```

- [ ] **Step 2: §14-2-2 monitoring 블록 증보** — `docker exec ... -d monitoring` 명령 블록에 3줄 추가, `brand_account` 줄은 `collection_months` 포함으로 교체:

```bash
  -c "GRANT SELECT (id, username, registered_at, closed_at, last_swept_at, last_swept_on, collection_months) ON brand_account TO grafana_reader" \
  -c "GRANT SELECT (first_seen_at, enriched_at) ON brand_tagged_post TO grafana_reader" \
  -c "GRANT SELECT (verdict, first_seen_at) ON brand_hashtag_post TO grafana_reader" \
  -c "GRANT SELECT (short_code, username, ad_verdict, ad_verdict_source, ad_violations, ad_judged_at, judged_caption_hash) ON brand_post_meta TO grafana_reader"
```

증보 설명문에 "08-18 폴더 분리로 브랜드 3장이 추가 조회(스펙: 2026-08-18-grafana-brand-folder-design.md)" 한 줄 추가.

- [ ] **Step 3: §14-1 목록 갱신** — 브랜드 항목을 폴더 구조로 교체:

```markdown
  - **브랜드 모니터링**(별도 폴더, 08-18 분리 — 3장): `[브랜드] 운영 건강`(hypenow-brand,
    스윕 신선도·오늘 성공/소요·처리 간격 — 브랜드 스윕은 런 기록이 없어 당일 유도 근사) ·
    `[브랜드] 수집 현황`(hypenow-brand-collection, 태그 게시물·해시태그 감지·enrich·백필) ·
    `[브랜드] 광고 표기`(hypenow-brand-ad, 판정 분포·경로·추이·미표기 목록)
```

"6탭" 표현이 남는 자리(§14 머리말)는 "6탭 + 브랜드 폴더 3장"으로 손본다.

- [ ] **Step 4: dev README 갱신** — 밀도표 monitoring 행에 `brand_post_meta 8,000(판정 60%)` 추가, §2-3 빨간불 절에 브랜드 2장 재현 항목(오늘 판정 0·오늘 신규 0·백필 미완 4) 한 줄 추가.

- [ ] **Step 5: 전체 최종 확인 + 커밋** — 하니스에서 폴더 2개·대시보드 8장(HypeNow 5 + 브랜드 3) 모두 열리는지 마지막 확인 후:

```bash
git add deploy/README.md deploy/grafana/dev/README.md
git commit -m "docs: §14-2-2 브랜드 3장 GRANT 증보 + §14-1·dev README 폴더 구조 반영"
git push
```

push 후 PR #501 본문에 "브랜드 폴더 분리 포함" 요지를 한 단락 추가(`gh pr edit 501 --body ...`).

---

## Self-Review 결과

- 스펙 §2(프로바이더)→Task 1, §3-1→Task 1, §3-2→Task 3, §3-3→Task 4, §4(GRANT)→Task 5,
  §5(시드)→Task 2, §6(검증·롤아웃)→각 태스크 검증 스텝 + Task 5 Step 5. 갭 없음.
- 타입/이름 일치: uid 3종·폴더명·디렉토리명 전 태스크 동일. seed 수치(120/12/3,200±)와
  Task 3·4 검증 기대값 일치.
- 임계값(enrich 500·백필 5 등)은 계획이 확정값을 명시 — 운영 1주 후 임계 보정 트랙에서 재조정.
