# 게시물 캡션 원문 보존 — 설계

> 상태: 🟢 활성

## 1. 배경 — 조사 착수 시점의 전제와 그 정정

계정 뷰티 판정 오판을 조사하던 중 "게시물 캡션 원문이 DB에 없다"는 결론이 나왔다. 근거는
`content` ↔ `raw_post_detail`/`raw_discovery_post` 조인이 빈 문자열만 반환했다는 것이었다.

**이 전제는 틀렸다.** 캡션은 raw DB에 보존되어 있고, 조인 대상 테이블이 잘못 선택된 것이다.

| 조사에서 본 곳 | 실제 캡션 원천 |
|---|---|
| `raw_post_detail` — 0행, LEGACY (07-22 접근 코드 삭제) | `raw_media_page` (`source='HIKER_V2_CLIPS'`) |
| `raw_discovery_post` — `origin='DISCOVERY'`만 커버 | `raw_profile` (`source='SELF_GQL'`) 내장 타임라인 |

정본 추출식은 [analytics/views/00_base.sql](../../../analytics/views/00_base.sql)의
`v_base_reel_item`(88행)·`v_base_timeline_item`(113행)에 이미 존재하며, `origin` 구분 없이
`short_code`로만 조인한다. 표본 125건이 전부 빈 문자열이었던 것은 유실이 아니라 조인 대상 부재다.

그러나 조사가 오답에 도달했다는 사실 자체가 실재하는 결함을 가리킨다. **캡션에 도달하는 유일한
경로가 5~7단 jsonb 표현식이면, 그것을 모르는 사람에게 캡션은 존재하지 않는 것과 같다.**

## 2. 실측 근거 (2026-07-30, 운영 raw DB 읽기 전용)

### 2-1. 캡션 커버리지 — `origin='ENUMERATION'`

| content_type | 총건수 | 캡션 존재 | 비율 |
|---|---|---|---|
| REELS | 89,857 | 88,878 | **98.9%** |
| FEED | 67,184 | 59,618 | **88.7%** |

집계는 `analytics.content_snapshot_cache`(839,003행, `max(captured_at)=2026-07-29 19:02 UTC`)
기준. 원본 뷰를 직접 UNION해 전체 jsonb를 재플래튼하는 방식은 6분 초과로 운영 부하 우려가 있어
중단하고 물질화 캐시로 대체했다.

### 2-2. raw 원형 테이블 — source별 실측

| 테이블 | source | 행수 | payload 합계 | 기간 |
|---|---|---|---|---|
| `raw_media_page` | `HIKER_V2_CLIPS` | 36,759 | 7,040 MB | 07-16 ~ 07-29 |
| `raw_media_page` | **`HIKER_V1_MEDIAS`** | **9,691** | **1,062 MB** | **07-18 ~ 07-29 (진행 중)** |
| `raw_profile` | `SELF_GQL` | 58,512 | 5,225 MB | 07-16 ~ 07-29 |
| `raw_profile` | `HIKER_MOBILE` | 40,826 | 307 MB | 07-16 ~ 07-29 |
| `raw_profile` | `DATALIKERS` | 88 | 141 kB | 07-16 (하루 만에 종료) |

### 2-3. 캡션 보존 비용

- 캡션 있는 distinct content: **148,559건**
- content당 최신 캡션 1건씩 총 원문: **96 MB** (평균 676 bytes)
- **pglz 실측 압축률 93.3%** — 이 길이대 한글 캡션은 압축 이득이 거의 없다(짧아서 임계 이득 미달).
  따라서 **압축 설계는 불필요**하다.

`raw_media_page` 8,437 MB + `raw_profile` 5,802 MB(둘 다 TOAST 비중 99.7% 이상) 대비 두 자릿수
배 작다. crawler DB 전체는 18 GB.

### 2-4. `HIKER_V1_MEDIAS` 복원 가능성 — 표본 40건

`origin='ENUMERATION' AND content_type='FEED'`이고 캡션이 없는 게시물 **랜덤 40건**을
`raw_media_page WHERE source='HIKER_V1_MEDIAS'` payload와 대조:

- **36건 매치(90%), 그중 33건 캡션 비어있지 않음(82.5%)**
- 추정: FEED 누락 7,566건 중 **약 6,240건 복원 가능**
  (표본 40건 기준 이항비율 표준오차 ≈ ±6%p — 방향성은 신뢰할 만하나 정밀 신뢰구간은 아닌 거친 추정)
- 2025-07 업로드(크롤 개시 이전) 게시물도 잡혔다

## 3. 유실 지점 — 정확한 특정

캡션은 **저장에 실패하는 것이 아니라 추출되지 않는다.**

[MediaItemExtractor.java:17](../../../crawler/src/main/java/com/celfit/crawler/crawling/application/service/MediaItemExtractor.java)

```java
public record MediaItem(String shortCode, Instant takenAt, ContentType type, boolean pinned) {}
```

파일 전체에 `caption` 문자열이 등장하지 않는다. 세 소스 모두 `shortCode`·`takenAt`·`type`·`pinned`만
뽑고 캡션은 버린다. `content` 테이블에 캡션 컬럼이 없는 것은 이 설계의 결과다.

### 3-1. 갭 정리

| 갭 | 내용 | 이 설계의 처리 |
|---|---|---|
| **A. 접근성** | 캡션에 도달하는 유일 경로가 5~7단 jsonb 표현식 | 해소 — 단일 테이블 조회 |
| **B. `HIKER_V1_MEDIAS` 미파싱** | 9,691행·1 GB 현역 소스를 **어떤 analytics 뷰도 읽지 않음**. 캡션 ~6,240건 사장 | **저장 측면만** 해소 (§6 참조) |
| **C. 진짜 복원 불가** | SELF_GQL 타임라인 롤링 윈도우 밖 옛 게시물 | 해소 불가 — 약 1,300건으로 축소 |
| **D. 향후 유실** | 지금도 수집되는 캡션이 계속 버려짐 | 해소 — 추출기가 파싱 |

갭 C의 규모는 조사 초기에 "FEED 누락 11.3% 전량이 구조적 복원 불가"로 판단했으나, 이는 표본 2건에
근거한 과잉 결론이었다. §2-4 실측으로 대부분이 갭 B(파싱 부재)임이 확인되어 7,566 → 약 1,300건으로
축소됐다.

## 4. 설계

### 4-1. 저장 — `content_caption` (raw DB, crawler 소유)

```sql
CREATE TABLE content_caption (
  content_id  bigint      PRIMARY KEY REFERENCES content(id) ON DELETE CASCADE,
  caption     text        NOT NULL,   -- 빈 문자열 허용
  source      text        NOT NULL,   -- HIKER_V2_CLIPS | SELF_GQL | HIKER_V1_MEDIAS
  captured_at timestamptz NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now()
);
```

결정과 근거:

- **`content`에 컬럼을 붙이지 않는다.** 148k행 백필 UPDATE가 36 MB 테이블을 블로트시키고 TOAST를
  새로 만든다. 별도 테이블은 순수 INSERT로 깨끗하다.
- **신규 테이블이라 expand-contract 무해** — `DROP`·`RENAME`·기존 컬럼 타입 변경·기존 컬럼
  `SET NOT NULL`이 없다. 새 테이블 안의 `NOT NULL`은 롤링 배포 위험이 아니다.
- **빈 캡션도 행을 만든다.** 행 존재 = "확인했음", `caption=''` = "게시물에 캡션이 없음".
  이렇게 하면 미백필과 무캡션이 구분되고 커버리지를 SQL로 측정할 수 있다. §2-4에서 캡션이 진짜
  비어있는 게시물이 실재함을 확인했다(매치 36건 중 3건).
- **`source` 기록** — 어느 원천에서 건졌는지 남아 커버리지 추적과 사후 검증이 가능하다.
- **최신 1건만 보존.** 캡션 수정 이력은 비목표(§7). 스냅샷 전량이면 83만 행·570 MB, 최신만이면
  148k 행·96 MB. 충돌 시 `captured_at`이 더 최신인 쪽이 이긴다(cross-source 포함).
- `jsonb` 규약(배열은 `text[]` 대신 `jsonb`)은 이 건에 해당하지 않는다 — 배열이 아니라 단일 텍스트다.
- 압축·보존기간 제한은 두지 않는다 — §2-3 실측대로 압축 이득이 7%뿐이고 총량이 96 MB다.

### 4-2. 파싱 — 단일 정본

`MediaItem`에 `caption` 필드를 추가하고 `MediaItemExtractor`가 세 소스 전부에서 캡션을 뽑는다.

| source | 캡션 JSON 경로 | 식별자 |
|---|---|---|
| `HIKER_V2_CLIPS` | `items[].media.caption.text` (중첩 객체) | `items[].media.code` |
| `SELF_GQL` | `...edges[].node.edge_media_to_caption.edges[0].node.text` (7단) | `...node.shortcode` |
| `HIKER_V1_MEDIAS` | `medias[].caption_text` (**평문 1단**) | `medias[].code` |

**라이브 경로와 백필이 같은 파서를 쓴다.** 추출기는 이미 V1_MEDIAS payload를 처리하고 있어
(`CollectJob.supplementFeedPage()` → `extract()`) 캡션 파싱 추가는 기존 분기 확장이다. 백필은
저장된 payload를 같은 추출기에 다시 흘려보내므로 **파싱 로직이 두 벌로 갈라질 위험이 없다.**

호출부([CollectJob.java:195](../../../crawler/src/main/java/com/celfit/crawler/crawling/application/service/CollectJob.java),
[ReelsJob.java:141](../../../crawler/src/main/java/com/celfit/crawler/crawling/application/service/ReelsJob.java))의
`ContentUpserter.upsert(items, inf)` 시그니처는 변경하지 않는다 — `MediaItem`이 캡션을 실어오므로
`upsert()` 내부에서 캡션 upsert를 함께 수행한다.

### 4-3. 백필 잡

`JobName.CAPTION_BACKFILL` 신규. `@Scheduled` 등록 없이 `/ui` 수동 트리거만
([JobService.java:71](../../../crawler/src/main/java/com/celfit/crawler/crawling/application/service/JobService.java) switch 분기 관용구).

- `BeautyJob` 패턴 차용 — 청크 단위 처리 + **청크당 1트랜잭션**(`txTemplate.execute`) + 진행률 로그
- **페이지 주도 스캔**(content 주도 아님) — `raw_media_page`/`raw_profile`을 id 순으로 훑으면
  페이지 1건이 캡션 약 12건을 내놓는다. content별 역방향 조회는 LATERAL 탐색이라 훨씬 비싸다
- 재시작: 워터마크 키를 `app_setting`에 두고 **crawler Flyway로 `ON CONFLICT DO NOTHING` 시드**
  (V16 관용구). upsert가 멱등이라 중복 실행도 안전하다
- 일회성 총량: jsonb 약 14 GB 읽기 1회. 운영에서는 크롤 잡과 겹치지 않는 시각에 수동 실행

### 4-4. 동반 정리 — `raw_post_detail` DROP

0행·24 kB. Java 엔티티/리포지토리가 존재하지 않으며(`find`·`grep` 각 0건), 잔존 참조는 V1/V8
마이그레이션 DDL 이력, [SchemaTest.java:23](../../../crawler/src/test/java/com/celfit/crawler/SchemaTest.java)의
존재 확인 리스트, 문서성 주석 4곳뿐이다. **참조 코드가 이미 끊긴 상태라 expand-contract의 contract
단계 조건을 충족한다.**

별도 마이그레이션·별도 커밋으로 처리하고, `-- allow-destructive:` +
`-- no-backfill: 0행이라 보정할 데이터 없음` 주석을 남긴다. `SchemaTest`의 `.contains(...)`에서
이름을 제거해야 한다.

### 4-5. 마이그레이션 번호

`origin/develop` 기준 최대는 `V21__beauty_foreign_influencer.sql`이므로 **V22부터**.
**머지 직전 재확인 필수** — 과거 V18 번호 경합 사고 전력이 있다.

## 5. CI 안전망 부재 — 주의

[.github/scripts/check-migration-safety.sh](../../../.github/scripts/check-migration-safety.sh)의
`migration-guard`는 **`was`+`analytics`(analysis DB) 마이그레이션만 검사한다**(diff 경로 L73-75).
주석 L14-16에 "crawler(raw)는 대상 외, crawler 트랙은 팀원 담당"이라 명시돼 있다.

따라서 이 설계의 마이그레이션은 **CI 파괴 가드에 걸리지 않는다.** expand-contract는 컨벤션으로
지키고 `-- allow-destructive:` 주석도 사람 리뷰어용으로 남기되, **자동 차단이 없다는 전제로 더
보수적으로 다룬다.**

## 6. 이 설계의 범위 밖 — 명시

**서빙 커버리지는 바뀌지 않는다.** analytics 뷰는 여전히 jsonb를 직접 읽고 `HIKER_V1_MEDIAS`를
무시하므로, 복원된 약 6,240건은 `content_caption`에 저장되어도 **뷰·랭킹·LLM 판정에 반영되지
않는다.** 그것은 analytics 층 작업이고 이 작업의 제약(crawler 층만)에서 제외됐다.

후속으로 analytics 트랙에 넘길 것:

1. **뷰가 `content_caption`을 읽도록 전환** — jsonb 직접 파싱을 대체하면 V1_MEDIAS 커버리지 갭이
   자동 해소되고 뷰 SQL도 단순해진다.
2. **`ProfileExtractor.recentCaptions()` 소스 집합 점검** — BeautyJob 판정 입력으로 캡션을 읽는
   별도 진입점인데 `MediaItemExtractor`와 다른 소스 집합을 본다. 뷰티 오판 실측에 기록된
   "판정 입력 빈곤 — 캡션 0건 소스"의 원인이 여기일 가능성이 있다.

crawler 트랙(팀원 담당)에 넘길 것:

3. **`HIKER_V1_MEDIAS`가 왜 현역인지** — 07-18부터 지금까지 V2_CLIPS와 기간이 겹치며 계속 유입된다.
   프로필 소스 전환 조건과 이 경로의 의도된 역할이 문서화되어 있지 않다.

백업 트랙(PR #193)에 넘길 것:

4. **`backup.sh`에 파생 캐시 제외 추가** — `pg_dump`에 `-N`/`-T`가 전혀 없어 crawler DB 전체를
   덤프한다. 그 안에 `analytics.content_snapshot_cache`(1,106 MB) +
   `analytics_dev.content_snapshot_cache`(991 MB) = **2.05 GB(DB의 11.4%)**가 매일 실린다. 둘 다
   크론(매일 19:25) `refresh_snapshot_cache()`로 재생성되는 순수 파생물이다. `-N analytics -N
   analytics_dev`로 제외 가능. 단 덤프 실측(07-27 6.5 GB → 07-28 7.6 GB → 07-29 8.5 GB)과 B2 무료
   캡을 감안하면 **이것만으로는 오프사이트 백업이 복구되지 않는다** — 캡 상향이 전제다.

## 7. 비목표

- **캡션 수정 이력 보존** — 최신 1건만 유지한다. 이력이 필요해지면 별도 테이블로 확장한다.
- **압축·보존기간 제한** — §2-3 실측(압축 이득 7%, 총량 96 MB)으로 불필요하다고 판단했다.
- **jsonb 원형 정리 정책** — 이 설계는 그것의 *전제*를 만들 뿐(96 MB만 남기면 근거가 유지된다)
  실제 정리는 하지 않는다. 현재 crawler에는 purge/retention 로직이 코드상 전무하며(grep 0건)
  raw jsonb가 무기한 누적된다.
- **`origin='DISCOVERY'` 캡션** — `raw_discovery_post`(344행)로 이미 조회 가능하므로 이번 범위에서
  제외한다. 통일이 필요해지면 같은 테이블에 `source='DISCOVERY'`로 추가할 수 있다.
- **analytics·was 층 변경** — §6.

## 8. 산출물

- 캡션이 `SELECT caption FROM content_caption WHERE content_id = ?` 한 줄로 조회 가능 (갭 A)
- `HIKER_V1_MEDIAS` 캡션 약 6,240건이 사장 → 보존 상태로 전환 (갭 B 저장 측면)
- 향후 수집 캡션 유실 중단 (갭 D)
- 14 GB jsonb에 보존기간 정책을 도입할 수 있는 전제 확보
- ARCHITECTURE.md §3에 캡션 조회 경로 문서화 — 이번 오조사의 재발 방지
