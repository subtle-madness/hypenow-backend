# MON-BT — 브랜드 태그 모니터링

- **소속 트랙군**: 모니터링 트랙 — 2026-08-06 설계 확정: [specs/2026-08-06-brand-tag-monitoring-schedule-design.md](../superpowers/specs/archive/2026-08-06-brand-tag-monitoring-schedule-design.md) (**주기·스키마는 같은 날 설계 재논의로 개정 — DECISIONS 08-06 개정 행이 정본**)
- **의존**: MON
- **상태**: ✅ (08-07 운영 개통 — 수집 파이프라인(PR #351) + was API 전체(PR #354, [spec 2026-08-07](../superpowers/specs/archive/2026-08-07-brand-monitoring-was-api-design.md)) 급행 승격 배포, 스윕 크론 KST 03:00 가동)

## 내용

브랜드 회원가입 계정에 태그된 게시물 자동 모니터링(가입 시 자동 시작~탈퇴까지, 스케일 가정 2,000계정). 수집은 `/v2/user/tag/medias` 열거 단일 경로(단건 게시물 콜 전면 배제 — 태그 열거는 릴스 조회수 인라인, findings §11). **매일 전량 수집**(08-06 개정 — 감지/트래킹 구분 폐지. **주기·윈도우·비용은 08-09 크롤링 정책 v1로 재개정 — 아래 "크롤링 정책 v1" 문단이 정본**): 브랜드마다 프로필 1콜(최신값 + 추이 일 1행) + 105개 깊이 열거(~5콜), 윈도우 90일 & 105개 안 전 게시물이 매일 1행 스냅샷. 댓글은 열거 `comment_count` > 저장값일 때만 최대 3콜 45개(기지 페이지 중단), 게시자 프로필은 `/v2/user/by/id` 브랜드 간 전역 캐시 최신 1행(미보유·30일 stale 등장 시만 콜). 복권 3종(저장·공유·리포스트)은 DB 적재·FE 미노출 — 재시도 콜 없이 부재=0·0 캐리 규칙 재사용. 비용 2,000계정 월 ~$550~600(매일 전량 개정 반영 — 사용자 수용. **08-09 정책 v1로 폐기된 값 — 현행 비용으로 인용 금지**).

구현(08-06): **전면 브랜드 전용 7테이블**(`brand_account`·`brand_tagged_post`(링크+댓글 게이트)·`brand_post_snapshot`·`brand_post_meta`·`brand_post_comment`·`brand_profile_snapshot`·`author_profile`) — 캠페인 테이블 불간섭(볼륨 격리 + 겹침 게시물 덮어쓰기 차단, DECISIONS 08-06 개정 행). fb 캐리포워드·역전파·0 캐리는 `BrandSnapshotRepository`에 동형 이식, 쓰기 경계는 `BrandSnapshotWriter`(알람 미경유). 진입점 `POST /api/brands`(동기 프로필 1콜 + 비동기 백필 `brandBackfillExecutor`, 멱등 replay)·`DELETE /api/brands/{username}`. 백필 상태는 `last_swept_on`(null=수집 준비 중)으로 판별. 스윕은 전용 크론(`monitoring.brand.schedule.sweep-cron`, 기본 비활성 — 운영 KST 03:00 권장) + 브랜드 단위 격리, 실패 시 다음날 백스톱. 윈도우 이탈 데이터는 영구 보존.

백필 단계식 ready(08-07 — DECISIONS 08-07 행): 등록 백필을 `sweepCore`(열거+적재, ~30초) / `enrich`(게시자+댓글, 수 분)로 분리 — core 완료 직후 touchSwept(ready. **08-12 스트리밍 개정으로 ready 마킹이 core 완주 전 서빙 창 커버 시점으로 앞당겨졌다 — 아래 08-12 문단이 정본**), 보강은 `brandEnrichExecutor`(신설) 별도 큐. 운영 실측(cclime_official 등록→ready 8.5분: 앞 계정 대기 5분 + 보강 콜 ~85%)이 근거. 보강 실패는 backfill_error 미기록(로그만) — 게시자 stale·댓글 워터마크로 다음 스윕 백스톱. 매일 스윕은 `sweep`(합본) 그대로.

보강 병렬화(08-07 — DECISIONS 08-07 행, [spec 2026-08-07](../superpowers/specs/archive/2026-08-07-brand-enrich-parallel-design.md)): `enrich` 내부 Hiker 콜(게시자 프로필·댓글)을 공유 워커 풀 `brandEnrichWorkerPool`(고정 6스레드, `monitoring.brand.enrich-concurrency`)로 제한 병렬화 — 보강 ~3분 → **~30초**, 등록→보강 완료 ~3.5분 → ~1분(ready는 종전대로 ~30초). 근거는 08-07 운영 실측(순차 ×6 = 11s vs 동시 4/8 각 웨이브 2s, 동시 8까지 레이턴시 열화·429 전무) — 종전 "동시 2 = 부하 완충" 전제 반증. 공유 빈이라 스윕·등록이 겹쳐도 전역 동시 콜 최대 8(워커 6 + core 2)로 실측 한계 이내. 게이트·워터마크·격리·backfill_error 규칙, 브랜드 단위 큐잉은 불변.

크롤링 정책 v1 — 나이 기반 티어(08-09 — DECISIONS 08-09 행, [spec 2026-08-09](../superpowers/specs/archive/2026-08-09-brand-crawl-policy-v1-design.md)): 수집 주기를 "매일 전량(90일 & 105개)"에서 **게시물 나이 티어 주기**로 전환 — 14일 이하 매일 / 14~30일 3일 / 30~90일 7일 / 90~180일 30일 / **180일 초과 영구 제외**(발견 시 스냅샷 1회). 등록 백필은 90일 → **365일**, 개수 상한 105는 폐지(안전 밸브 `max-posts-per-sweep:2000`만 — 정상 경로에서 닿으면 안 되는 값. 설정 `window-days`·`window-posts` 제거, `registration-window-days:365` 신설). 판정은 저장 티어 상태 없이 `taken_at`·`last_crawled_at`·현재 시각만 보는 순수 함수 `BrandCrawlPolicy`(`last_crawled_at` null = 무조건 due → 마이그레이션 기존 행이 첫 스윕에서 자연 수렴, 스윕 하루 실패도 다음 스윕이 밀린 깊이 자동 커버). 실행은 게시물 단위가 아니라 **"오늘의 열거 깊이"**로 번역 — 컷 = min(now−14일, 가장 오래된 due의 `taken_at`)이라 due가 없어도 신규 태그용 14일 깊이는 매일 보장되고, 깊은 열거가 얕은 구간을 자동 포함(스킵 로직 불필요). 열거에서 만난 게시물은 due 여부 무관 전부 적재(콜 0 추가). 신규 발견은 ≤180일 추적 / 180~365일 스냅샷 1회 종료 / >365일 무시. **현행 유지 확정**(사용자 결정 — 정책 문서의 단건 상세 콜·30일 댓글 보충·첫 등록 부스트 크롤은 채택 안 함): 단건 상세 콜 금지, 복권 3종 기회 적재, 댓글 게시물당 45개(3콜) 상한. 스키마는 `brand_tagged_post.last_crawled_at` 1컬럼 추가(`V20260809120000__brand_tagged_post_last_crawled_at.sql`)가 전부. 게이팅·워터마크·격리·단계식 ready·보강 병렬화는 불변. **비용 재산정**(스펙 §8, 콜당 $0.0006 — 종전 "2,000계정 월 ~$550~600"을 대체): 계정당 등록 1회 **$0.2~0.6**(cclime_official급 847개/12개월 기준 ~300~900콜) + **월 유지 ~$0.05~0.07**(+댓글 게이트·게시자 stale 변동분 $0.02~0.05) — 자릿수가 다르다. 2,000계정 총액은 게시자 프로필 콜 수(N명)가 미실측이라 아직 재산정하지 않았다(배포 후 등록 1건 실측으로 스펙 §8 표와 함께 갱신 — 미결·후속 참조). 실질 리스크인 IG 요청량도 매일 전량 대비 준다. 커밋 d18afdf9(정책 함수)·8e5114ad(스키마·`trackedPosts`/`touchCrawled`)·3749db01(스윕 배선).

해시태그 감지 확장(2026-08-11 — 구현 완료, 브랜치 `feat/brand-hashtag-detection`, PR 대기): 브랜드
계정 태그(`@핸들`)뿐 아니라 브랜드명·계정명 및 정규화 변형 **해시태그**도 매일 열거해 자동 발견한다.
요지 — monitoring에 신규 3테이블(`brand_hashtag`·`brand_hashtag_exclusion`·`brand_hashtag_post`,
V20260811085943) + 매일 브랜드 스윕에 합류하는 해시태그 파이프라인(Hiker `/v2/hashtag/medias/recent`
열거 → 자사 태그·직접 태그·단순 멘션 필터 → Gemini(`BrandMentionJudge`)로 브랜드 관련성 판정,
이름 충돌 방어·키 미설정 fail-closed) + 등록 시 태그 시드·백필 편승 + 제외 문자열 관리 API. was
표면은 **08-12 정정**(브랜치 `feat/brand-hashtag-separate-api`) — 최초 설계(08-11)는 브랜드 게시물
피드에 `source: "hashtag"`로 병합했으나, FE 결정으로 **전용 API**
`GET /v1/brand-monitoring/accounts/{accountId}/hashtag-posts`(슬림 `BrandHashtagPostResponse`,
`BrandHashtagPostAssembler`)로 분리 — §6-1 목록·`meta.counts`는 병합 이전 상태로 되돌아갔다.
신규 `GET/PUT /v1/brand-monitoring/accounts/{accountId}/hashtag-exclusions` 프록시는 불변 —
상세 계약은
[monitoring-was-contract.md §8](../contracts/monitoring-was-contract.md#8-브랜드-태그-모니터링-확장--해시태그-감지-v28-2026-08-11-08-12-api-형태-정정).
커밋 범위 `8d5958f1`~(설계 문서 동기화 포함, monitoring 신규 테이블·파이프라인 + was 피드 병합 시도
+ 08-12 전용 API 분리 전부 포함). 배포 env(`GEMINI_API_KEY`)는 `deploy/compose.yaml` monitoring
서비스에 이번에 추가. **잔여**: FE 공유 필요 — 해시태그 발견 게시물 별도 탭·제외 문자열 관리
UI(계약 §8-4).

안전 상한 개정(08-12 — DECISIONS 08-12 행, [spec 2026-08-12](../superpowers/specs/2026-08-12-brand-sweep-cap-revision-design.md)):
`max-posts-per-sweep` **2,000 → 10,000** + 도달 시 error 로그(열거 건수·목표 컷·커버 깊이).
tooq.official(id=34, 11.8건/일 정상 고물량) 등록 백필이 상한 2,000에 걸려 365일 창의 172~365일
구간이 조용히 영구 공백이 된 운영 실측 대응 — 상한 중단도 정상 반환이라 touchSwept가 찍혀
이후 스윕이 그 구간을 영영 안 연다. 방치 시 09-11경부터 심층 티어 일제 due로 일일 스윕 상한
루프(매달 ~열흘 × ~96콜/일)도 예정돼 있었다. 재개형 백필은 커서 체인 재열거 특성상 무진전이라
기각, 상한 도달 시 touchSwept 유지(서빙 우선) 확정. **tooq 공백 보정은 배포 후
`UPDATE brand_account SET last_swept_on=NULL WHERE id=34` → 야간 스윕 재백필(~205콜)** —
was ready가 `last_swept_at` 기준(08-10)이라 FE 무영향. 실행 대기(운영 DB 쓰기 — 사용자 확인 필요).

백필 페이지 스트리밍 적재 + 조기 서빙(2026-08-12 — [spec 2026-08-12](../superpowers/specs/2026-08-12-brand-backfill-streaming-serving-design.md)):
tooq.official 등록 실측(운영)에서 등록 → ready가 **8분 24초**(365일 열거 96콜 × p50 4.9초를
전부 끝낸 뒤에야 일괄 적재·ready)라 그동안 FE가 이미 받아온 데이터까지 로딩 화면으로 가렸다.
`sweepCore`를 **페이지(~21건) 단위 즉시 적재**로 바꾸고(중복 콜 0 — 커서 체인은 그대로,
`knownCodes` 1회 로드 + 이번 실행 처리분 누적으로 커서 드리프트 중복만 스킵), **서빙 창
30일**(`monitoring.brand.serving-window-days`) 커버 시점에 신설 `markServing`으로
**`last_swept_at`만** 조기 마킹해 ready를 연다(tooq 실측 ~17콜 ≒ 1분 30초). 게시자·댓글
보강도 이 시점에 선행 시작. `last_swept_on`·`backfill_completed_at`은 **완주 시 touchSwept**로
현행 유지 — 30일 시점에 `last_swept_on`을 찍으면 이후 백그라운드 열거가 죽었을 때 다음 스윕이
14일 컷만 돌아 30~365일이 영구 공백이 된다(현행 유지 = 백필이 중간에 죽어도 다음 일일 스윕이
전체를 백스톱하는 자가 치유). 신호 3개의 의미가 갈렸다는 점이 소비자 쪽 파급: was ready 판정
(`BrandAccountAssembler`)은 `last_swept_at`(= 서빙할 데이터 있음) 그대로지만, **성과 대시보드
covered는 `backfill_completed_at`(최초 완주) 기준으로 정정**했다 — `last_swept_at`은 더 이상
365일 전량을 보장하지 않는다.

OOM 재발 방지 + 백필 core 2병렬(08-12 — DECISIONS 08-12 행):
운영 monitoring(Xmx 384m)이 브랜드 5연속 등록(05:06~05:22 UTC) 백필 중 `Java heap space`로
2건(lagom·mude) 실패(다음 스윕 백스톱으로 자동 복구). 원인은 소비처 0인 죽은 필드
`PostInfo.rawJson`이 열거 페이지 원문 전체(TAGGED 평균 859KB)를 붙든 채 365일 백필분이
무제한 enrich 큐에 브랜드당 ~150MB씩 상주한 것 — 필드 제거로 ~6MB로 축소. 동시성 램프
실측(동시 20까지 무차단, 12부터 꼬리 상시화)을 근거로 백필 executor 1→2스레드
(`monitoring.brand.backfill-concurrency:2`, 전역 동시 콜 최악 9), compose Xmx 384→512m +
`-XX:+ExitOnOutOfMemoryError`. 힙 예산 공식은 "동시 in-flight 콜 × ~10MB" — 추가 병렬화는
이 공식과 꼬리 레이턴시(request-timeout 15초 초과 시 과금 2배)로 판단한다.

수집 범위 선택 + 기간 확장(2026-08-12 — DECISIONS 08-12 행,
[spec 2026-08-12](../superpowers/specs/2026-08-12-brand-collection-months-design.md)): 등록에
수집 창 선택(1/3/6/12개월)을 도입했다. 창은 유저 단위가 아니라 **공유 크롤 자산 단위**
(`brand_account.collection_months`, 신설 — 기존 행은 DEFAULT 12로 사실과 일치)이고 **절대
줄이지 않는다**(유저 간 max). 전역 `registration-window-days:365`가 이 컬럼으로 대체돼
백필·스윕 열거 컷이 브랜드마다 달라진다. **더 큰 값 재등록 = 기간 확장**: `collection_months`
상향 + `last_swept_on` 클리어 + 백필 재제출이 전부라, 확장 백필이 죽어도 다음 스윕이 새 창
전체를 다시 여는 기존 백스톱을 그대로 상속한다(신규 복구 경로 0). 확장 중에도 기존 데이터는
계속 서빙하고, was 유도 규칙에 분기 하나를 더했다 — `last_swept_on null && backfill_completed_at
있음 → collecting`(최초 등록 중과 구분). FE 폴링 앵커로 `collection_started_at`(확장 시작 시
now()) 신설, 응답에 `collectionMonths` 추가. 부수 정정: 브랜드 스윕 크론은 서버 override
드리프트값 **KST 02:00**이 실제 가동값이라 이를 정본으로 수용했다(레포 `deploy/compose.yaml`
정렬 + was `nextScheduledAt` 표기 기본값 3 → 2 — 종전 표기 03:00은 실제와 1시간 어긋나 있었다).
**08-17 개정으로 "자산 레벨 하나"는 부분 뒤집혔다 — 아래 링크 레벨 항목 참조.**

링크 레벨 표시 창(2026-08-17 — DECISIONS 08-17 행,
[spec 2026-08-17](../superpowers/specs/2026-08-17-brand-link-collection-months-design.md)):
08-12의 "자산 레벨 값 하나로 관리"가 공유 브랜드에서 무너졌다 — 3개월을 신청한 유저가
12개월치 전량을 받는다(cclime 실사례). 신청값이 어디에도 영속화되지 않아 유저별로 자를
근거 자체가 없었다. **크롤 자산은 그대로 두고**(`brand_account.collection_months` = 유저 간
max, 축소 없음 — 수집한 사실이 정본) 유저-브랜드 링크에 신청값을 저장해
(`app.brand_monitorings.collection_months` 신설, 기존 행은 DEFAULT 12로 백필) **서빙 계층에서만
자른다**. 응답 `collectionMonths`는 이제 자산이 아니라 **링크 값(= 그 유저가 신청한 기간)**이고,
게시물 목록·`meta.counts`·상세가 전부 링크 창으로 서빙된다(counts는 이미 잘린 목록에서 파생돼
자동으로 같은 창, 상세도 같은 필터라 "목록엔 없는데 상세만 열리는" 불일치가 없다 — 같은 404).
**direct 게시물은 예외**(유저가 URL을 명시 등록한 추적 대상이라 창과 무관하게 항상 포함).
쓰기 규칙도 자산과 다르다 — 링크는 **축소를 허용**하고(명시한 값 그대로), 필드를 생략한
재-POST는 링크 기간을 바꾸지 않는다(구 클라이언트가 3개월 신청을 12로 되돌리지 않게 raw
값으로 판정). 직접 등록의 중복 게이트도 같은 창을 쓴다 — 자산 창으로 판정하면 링크 창 밖
tagged가 목록·상세 어디에도 없는데 등록만 DUPLICATE로 막혀 영구 도달 불능(데드엔드)이 된다.

완결 배치 서빙(2026-08-13 — **구현 완료**, 커밋 `2d0d9b60`~`43b8a6a7` ·
[spec 2026-08-13](../superpowers/specs/2026-08-13-brand-initial-batch-serving-design.md)):
FE 요청서(08-13)로 "완성된 게시물만 내려달라"는 계약 변경이 들어와, 서빙 판정을
"열거 적재됨"에서 **게시물 단위 보강 정산**(`brand_tagged_post.enriched_at` 신설)으로 옮겼다.
방출 단위는 열거 페이지(21건) 배치 — **첫 페이지 배치 완료가 곧 `markServing`**이라 08-12의
서빙 창 30일 기준(`serving-window-days`)은 제거됐다. 운영 실측(08-13, 브랜드 5개·470여 콜)이
근거: 첫 페이지 완결이 **~10초**로 현행 ready(1분 30초)보다 빨라 완결성과 속도가 상충하지
않는다. 같은 측정에서 **`/v2/user/by/id`의 404가 결정적 부재가 아님을 실측 반증**했고
(404율 2.0%, 재시도 1회로 2/2 복구 · 5초 초과 1.0%), 이는 완결 서빙에서 곧바로 영구 미노출
구멍이 되므로 보강 단계의 `by/id`에만 404 재시도 1회를 건다(전송 계층 전면 승격은 기각 —
`by/username`·단건의 404는 여전히 결정적). 정산은 "시도가 끝났다"이지 "필드가 다 찼다"가
아니다 — 소진 후에는 빈 채로 방출하고 워터마크·stale이 다음 스윕에서 채운다. 완주 신호는
**기존 `collectionCompletedAt` 재사용**(status에 `end` 값 추가는 FE가 `=== "ready"` 분기 9곳을
근거로 거부 — 3값 계약 고정), 대신 `expandWindow`에 `backfill_completed_at = NULL`을 더해
확장 중 폴링이 멎지 않게 한다. 그 결과 08-12에 추가했던 was 유도 분기(`last_swept_on null &&
backfill_completed_at 있음 → collecting`)가 도달 불가가 되어 제거되고, **확장 중 상태는
`collecting` → `ready`로 뒤집힌다**(진행 판정이 status에서 필드로 옮겨간 것이 근거). 동시성은
enrich executor 1 → 2(설정화)가 필수 — core 2병렬인데 보강이 단일 스레드라 연속 등록 시
둘째 브랜드 화면이 분 단위로 빈다. 워커 6 → 10은 그 반감 상쇄분(공유 풀이라 executor 2면
브랜드당 실효 3). 전역 동시 콜 최악 9 → 13인데, 08-12 램프의 "12부터 꼬리 상시화"와 달리
08-13 실측에서는 워커 6/8/10 어느 레벨에서도 5초 초과 콜이 늘지 않았다(꼬리는 동시성이 아니라
특정 콜에 산발적으로 붙었다). 마이그레이션(`V20260813115041`)은 **기존 25,759행 백필 필수**
(누락 시 게이트 도입 순간 전 브랜드 목록 공백), 배포 순서 monitoring → was(**롤백도 같은
의존** — monitoring만 되돌리면 was가 없는 컬럼을 조회한다).

**실행 중 설계에서 갈라진 셋**(스펙 §2·§3·§5에 정정 반영):
① **등록 백필은 비동기 파이프라인**이고 **`touchSwept`은 전 페이지 정산 후**다 — 페이지를
enrich executor에 제출하고 열거는 계속 앞서 달린다(열거 ~5초/페이지와 보강 ~5.4초/페이지가
겹쳐 완주가 절반). `markServing`은 첫 제출분 보강 완료 지점이지만, `touchSwept`은 응답
`collectionCompletedAt` = FE 폴링 종료 조건이라 **미정산 페이지가 남은 채 찍으면 FE가 미완성
목록을 최종본으로 알고 폴링을 멈춘다**. 이 개정으로 열거 완주 ≠ 수집 완주가 됐다. core
스레드가 `join()`에 묶이는 건 의도 — enrich executor가 전역 공유 풀이라 `thenRun`으로 풀어도
대기가 큐로 옮겨갈 뿐이고, 이 블로킹이 **유일한 브랜드 간 백프레셔**다(없애면 08-12 OOM 형태의
큐 적체). ② **스윕 페이지 콜백의 보강 실패를 격리**한다 — `sweepCore`가 콜백 예외를 잡지 않아
1페이지 보강 실패가 열거 루프를 끊고 뒤 페이지가 **적재조차 안 됐다**. 미정산은 지연이지만
미적재는 손실이다(`trackedPosts`에 없어 다음 스윕 깊이 컷 `min(14일, 가장 오래된 due)`을
못 끌어내림 → 소급 태그된 14일 이상 게시물 영구 미수집). ③ **게이트는 표시 표면에만** —
스펙이 "목록·상세·counts 세 표면이 조회 하나를 경유한다"고 단정했으나 실제 소비자는 **4곳**이고
성격이 갈렸다: 표시(`BrandPostAssembler.assembleForBrand`)만 적용, **존재 판정**
(`V2CampaignContentService` — 게이트하면 수집 중 실존 게시물을 `NOT_FOUND`로 답함)·**중복 판정**
(`V1BrandDirectPostService` — 미정산분이 direct로 등록되면 `mergeByShortcode`의 direct 우선
규칙 때문에 카드가 영구히 direct 셰이프로 고정)·**지표 집계**(`PerformanceContentAssembler` —
미정산분도 스냅샷 지표가 있어 제외하면 과소 계상)는 해제. 리포지토리를 두 경로
(`findTaggedPostsInWindow` 전량 / `findEnrichedTaggedPostsInWindow` 정산분)로 나누고
`TaggedScope{ENRICHED_ONLY, ALL}`를 **기본값 없는 필수 인자**로 둬 호출부가 매번 의도를 밝힌다.

브랜드 direct 게시물 파이프라인 통합(2026-08-18 — **구현 완료(E1 monitoring·E2 was), 이관(M)
미실행**, [spec 2026-08-18](../superpowers/specs/2026-08-18-brand-direct-pipeline-unification-design.md) ·
[plan(아카이브)](../superpowers/plans/archive/2026-08-18-brand-direct-pipeline-unification-plan.md)):
브랜드 직접 등록(direct) 게시물을 레거시 추적 파이프라인(`app.monitoring_items` → monitoring
`target`/`post_snapshot`)에서 떼어내 이 트랙의 브랜드 수집 파이프라인(`brand_tagged_post`/
`brand_post_*`)으로 합류시켰다. `brand_tagged_post`에 `source` 단일 enum 대신 시각 컬럼 2개
(`tag_detected_at`·`direct_registered_at`, `V20260818040742__brand_tagged_post_direct_source.sql`)를
추가하고 `source`는 `direct_registered_at IS NOT NULL` 파생값으로 둔다 — 태그 발견과 직접 등록이
한 게시물에서 겹칠 수 있고 PK가 `(brand_id, short_code)` 하나뿐이라 단일 값으로 접으면 취소 시
태그 발견 사실을 잃기 때문이다. 열거 깊이 판정(`trackedPosts`·`touchCrawledDepth`)에는
`tag_detected_at IS NOT NULL` 가드를 추가해 direct-only 행이 열거 깊이를 오염시키지 않게 했고,
신규 `BrandDirectCollectService`가 단건 콜로 direct 게시물을 등록·야간 스윕 2단계(`sweepDirect`)로
수집한다("단건 게시물 콜 전면 금지"(08-06·08-09) 결정과 무충돌 — 그 결정은 열거로 이미 얻은
게시물에 콜을 덧붙이는 제안을 기각한 것이고, direct 게시물은 애초에 열거에 실리지 않는다).
monitoring에 명령 API 2종(`POST`/`DELETE /api/brands/{brandId}/direct-posts`) 신설.

was는 레거시 `monitoring_registrations` 위임·`resolveLazyMappingBrand`(PP 트랙 후속 #1 참조)·seq
인덱스 매칭을 걷어내고 전용 등록 테이블(`app.brand_post_registrations`+`entries`,
`V20260818043332__brand_post_unification.sql`)과 전용 실행기(`BrandDirectRegistrationExecutor`,
5분 stale 복구 + 24시간 정산)로 재작성했다. `BrandPostAssembler`가 `brandPost()` 한 벌로 조립을
통합(`directPost`/`mergeByShortcode`/`promoteSponsorship` 삭제) — 겹침 게시물이 direct 셰이프로
영구 고정되던 비대칭, 창 밖 tagged 데드엔드 우회의 "의도된 대가"(둘 다 아래 미결·후속에서 취소선
처리)가 소멸했다. 캠페인 연결은 `app.brand_post_campaigns` N:M으로 옮겨 tagged에도 열었다(부착·
해제 API는 별도 트랙). 배포 순서는 **monitoring → was 고정**(08-13과 같은 의존 — 역순이면 was가
없는 컬럼을 조회해 브랜드 목록 전면 500). `:monitoring:test` 660개·`:was:test` 1387개 green.

**이관(M)은 운영 미실행** — `app.brand_direct_posts.migrated_at`이 NULL인 행은 과도기 폴백
(`assembleLegacyPending`)이 레거시 셰이프로 계속 조립해 얹으므로 배포 직후 화면은 현행과 동일하게
보인다. 이관 잡(재수집 방식 — 링크 복제가 아니라 레거시 이력 복사 후 재수집으로 채움)은 구현
완료·운영 미실행 — 실행 주체는 배포 후 별도 세션(M1 규모 확인 → 승인 → M2 실행 → M3 콜 증분 2주
실측).

**FE 통지 4건**: `trackingDays` 무시(검증 1~90은 유지) / `BrandPostResponse.trackingStatus` 항상
`"tracking"` / `Entry.monitoringItemId` 항상 null(취소는 계약 v2.12 §8-2 그대로 4xx/204 어휘 유지)
/ 성과 대시보드 direct 콘텐츠의 `item.id`가 숫자에서 `bt_<shortcode>`로 변경. 부수: 같은 브랜드
타 유저 등록분이 목록에 보임(direct도 tagged와 같은 브랜드 스코프 공유로 승격), 180일 초과
게시물 직접 등록은 스냅샷 1행만 남는다.

**신규 미결(R1~R9, 설계 §7)**: R1 이관 대상 규모 미상(배포 전 확인 SQL 필요) · R2 다중 유저
브랜드에서 취소 권한 완화(A 등록을 C가 취소 가능, 영향 브랜드 수 확인 필요) · R3 콜 증분이
예상(0 이하)과 다를 가능성(2주 실측 필요) · R4 180일 초과 등록 UX 후퇴(FE 협의 필요) · R5 열거
깊이 가드 누락 시 조용한 요청량 누수(테스트로만 방어) · R6 `cancel`의 무아카이브 hard delete
(기존 결함, 이번에도 미해결) · R7 stale pending 정산(24시간 초과 → failed, 레거시 동형 이식
완료) · R8 성과 대시보드 `statusCounts` 분포 변화(direct가 항상 tracking) · R9 레거시 이력 복사
컬럼 동형성(구현 시 실제 DDL 대조 완료, 잡 SQL 주석에 기록). 실행 주체는 아래 미결·후속 참조.

계정 게이트 단축(2026-08-18 — **구현 완료**, 브랜치 `fix/brand-serving-first-page`): 완결 배치
서빙(위 08-13 문단)이 **게시물 게이트**(`enriched_at`)는 이미 "게시자 보강 완료" 시점으로
좁혔지만(08-17 개정, 아래 "노출 게이트 의미 변경" 참조), **계정 게이트**(`BrandRegistrationService.
markServing`)는 여전히 옛 배선대로 첫 페이지의 `enrich()` 호출 전체(게시자 보강 + 댓글 수집 +
광고 표기 판정)가 반환돼야 열렸다 — 두 게이트가 08-17 개정 이후로 어긋나 있었다. 등록 직후
계정 자체가 was에 뜨는 시점이 게시물이 뜨는 시점보다 늦어, "등록 → 첫 화면 노출"이 게시자
콜(Hiker 1콜, p50 4.9초/페이지) 하나가 아니라 댓글·판정까지 더한 시간만큼 늦어지는 회귀였다
(광고 표기 판정 트랙 추가로 그 지연이 눈에 띄게 커졌다). `BrandCollectService.enrich`에
onVisible 훅(nullable `Runnable`)을 추가해 markEnriched와 **같은 finally 지점**(ensureAuthors
하드 실패에도 발화)에서 호출하고, `BrandRegistrationService.runBackfillSafely`는 이 훅을
**첫 페이지에만** 걸어(sweepCore 콜백이 단일 스레드 순차라 "제출된 페이지 수 0" 판정에
경합이 없다) `served.compareAndSet` 가드로 `markServing`을 그 지점에서 연다. 야간 스윕
(`BrandCollectService.sweep`)은 onVisible 없는 2-인자 `enrich` 위임을 그대로 쓰므로 무변 —
계정 게이트 자체가 스윕 경로엔 없다. `touchSwept`(FE 폴링 종료 조건)는 무수정 — 여전히 모든
페이지의 댓글·판정까지 끝난 뒤에만 찍힌다. 사용자 확정 트레이드오프: 등록 직후 첫 화면에는
첫 페이지분(~21건)만 보이고 나머지는 스트리밍으로 채워진다.

캡션 기반 광고 표기 판정(2026-08-18 — 구현 완료, 브랜치 `feat/brand-ad-disclosure`,
[spec 2026-08-17](../superpowers/specs/2026-08-17-brand-ad-disclosure-design.md) ·
[plan 2026-08-17](../superpowers/plans/archive/2026-08-17-brand-ad-disclosure.md)): 브랜드 태그 게시물
캡션이 공정위예규 제499호 Ⅴ.6 광고 표기 규정을 지켰는지 게시물 단위로 자동 판정한다. 규칙
선처리(Tier0 메타·Tier1 고신뢰 사전) → LLM은 문구 추출만(Tier2, `AdDisclosureExtractorGemini`,
판단 아님) → 코드가 환각 차단·위치 판정·최종 verdict를 결정(Tier3, `AdPositionRule`·
`AdVerdictCombiner`, 전부 LLM 없이 단위 테스트)하는 구조 — LLM에 verdict 자체를 맡기지 않는다.
전용 소형 LLM 풀(`monitoring.brand.ad-disclosure`, 동시 3~4)로 기존 Hiker 보강 워커와 분리해
판정 지연이 보강 처리량을 잠식하지 않게 했다. **시딩 계정**(`brand_seeded_account`, 신설
`BrandSeededAccountRepository`)의 게시물도 다른 게시물과 동일하게 캡션 판정을 거친다(2026-08-18
오기 정정 — 최초 기재는 "판정 없이 시딩 표기로 확정 노출"이라 코드·스펙과 정반대였다). 시딩
여부(`seededAuthor`)는 판정과 무관하게 was 조회 시점에 별도 조인(시딩 목록 대조)으로 계산되는
boolean 필드다. "시딩 계정 + `NOT_DISCLOSED`" 조합일 때 위반이 확정됐다는 배지를 보여주는 것은
FE의 조합 로직일 뿐이며, 그 배지 표시에도 캡션 판정(`NOT_DISCLOSED`)이 필수 전제 조건으로
들어간다.

- **노출 게이트 의미 변경**: `enriched_at`(= was 노출 게이트, `enriched_at IS NOT NULL`)의 뜻이
  "게시자 보강 완료"로 좁혀졌다(08-17 개정, §5 완결 배치 서빙 문단과 별개 축). 댓글 수집·광고
  표기 판정은 이 게이트 **밖**으로 빠져 각자 격리된 독립 단계가 되고, 프론트 폴링으로 나중에
  채워지는 **프로그레시브 서빙**이다 — 판정 실패·지연이 게시물 노출 자체를 막지 않는다.
- **파이프라인 개통 상태**: 판정 로직 자체는 배포 즉시 브랜드 enrich 체인에 인라인으로 돌기
  시작한다(기존 게시물도 다음 스윕들에서 자연 재판정). 하지만 **was 노출은 별개 토글**
  (`monitoring.brand.ad-disclosure.expose`, `was/src/main/resources/application.yml`, 기본
  `false`)로 막혀 있다 — 판정은 쌓이지만 FE에는 아직 안 보인다. 기존 게시물 전량 판정 +
  verdict 분포 드라이런 검토를 마친 뒤 `true`로 전환하는 것이 노출 개통이며, 이 전환은 이번
  구현 범위 밖이다(스펙 §10-3).
- **시딩 계정 등록 API — 신설 후 08-18 당일 전면 철회, `seededAuthor`는 캠페인 데이터 도출로
  교체**(사용자 확정, `fix/seeded-from-campaign`): monitoring `GET/PUT/POST/DELETE
  .../seeded-accounts`(시딩 계정 CRUD) + was `V1BrandAccountsController` 프록시로 브랜드가
  협업 계정 목록을 별도 등록하는 표면을 08-18 오전에 신설했으나, 잘못된 신설이었다는 판단으로
  같은 날 걷어냈다 — `seededAuthor`는 신규 등록이 아니라 **이미 존재하는 캠페인 관리 데이터**
  에서 나와야 한다는 원칙. 걷어낸 것: monitoring `BrandSeededAccountRepository` + API 5종,
  was `V1BrandAccountsController`/`V1BrandAccountService`의 시딩 엔드포인트·메서드 5종,
  `MonitoringCommandClient` 시딩 프록시 5종, `BrandReadRepository.findSeededUsernames`.
  **새 산출 기준**(user 스코프 — 캠페인은 브랜드가 아니라 유저 단위): (1) `app.monitoring_items`의
  `mode='account' AND campaign_id IS NOT NULL AND canceled_at IS NULL` 행의 핸들(신규
  `MonitoringItemRepository.findCampaignLinkedAccountHandles`), (2) `app.brand_direct_posts`
  중 연결된 아이템이 캠페인 배정·미취소인 short_code들의 게시자(신규 `BrandDirectPostRepository.
  findCampaignLinkedShortCodes` + monitoring `brand_post_meta.username` 조회) — 둘 다 was
  `BrandPostAssembler.resolveSeededUsernames(userId)`가 조합한다(app·monitoring이 물리적으로
  다른 DB라 SQL 조인 불가, 시스템 경계 원칙). `assembleTagged`가 브랜드 스코프
  (`account.id()`)에서 유저 스코프(`userId`)로 바뀌어 `PerformanceContentAssembler`·
  `V2CampaignContentService` 호출부도 함께 갱신됐다. **`brand_seeded_account` 테이블·
  마이그레이션은 이미 develop 머지·스테이징 적용 상태라 DROP하지 않고 미사용 상태로 남는다**
  (expand-contract상 DROP은 다음 contract 단계). 상세 계약은
  [monitoring-was-contract.md §9](../contracts/monitoring-was-contract.md#9-브랜드-태그-모니터링-확장--광고-표기-판정seededauthor-v212-2026-08-18)
  (v2.12).
- **판정 킬 스위치 추가**(2026-08-18 코드리뷰 반영): `monitoring.brand.ad-disclosure.enabled`
  (기본 `true`) — `false`면 `judgeAdDisclosuresSafely` 진입점에서 `adJudge` 호출 자체를
  스킵한다. was 노출 토글(`expose`)과 **독립**이라, 노출은 그대로 두고 판정 파이프라인만
  끌 수 있는 좁은 롤백 수단이다(`GEMINI_API_KEY` 제거는 해시태그 판정까지 함께 죽이므로 이
  토글을 대신 쓰지 말 것).
- **배포 순서: monitoring → was.** was `BrandPostAssembler`가 새 컬럼(`brand_post_meta`의
  ad_verdict 등)을 항상 SELECT하므로, was를 먼저 배포하면(또는 monitoring이 healthy가 아닌
  채로 was를 배포하면) 브랜드 목록 조회가 500 에러가 난다. monitoring이 healthy임을 확인한
  뒤 was를 배포할 것. (08-18 시딩 계정 관리 표면 철회로 `brand_seeded_account`는 이 목록에서
  빠졌다 — was가 더 이상 그 테이블을 조회하지 않는다.)

수집 개수 상한(2026-08-19 — **구현 완료**,
[spec 2026-08-19](../superpowers/specs/2026-08-19-brand-collection-post-limit-design.md) ·
[plan(아카이브)](../superpowers/plans/archive/2026-08-19-brand-collection-post-limit.md)):
전역 설정 `monitoring.brand.collection-post-limit`(기본 **2,000**, **0 이하면 무제한** —
`backfill-max-per-run` 관용 일치) 신설. 한 실행의 열거량이 상한에 닿으면 INFO 로그와 함께
**의도된 자연 종료**로 끊고, `coveredCutoff=true`로 `touchCrawledDepth`를 **목표 컷 전체**에
찍는다 — 컷 밖(더 깊은) 게시물은 실크롤 없이 `last_crawled_at`이 갱신돼 ①매 스윕이 같은
깊이를 다시 여는 due 낭비 루프가 끊기고 ②마지막 수집 시점 지표로 **동결된 채 계속 서빙**된다
(was 목록 상한 `POST_LIMIT` 2,000과 정합). 계기는 marynmay_global 등록 백필 운영 실측 —
10,017건 열거·Hiker 15,298콜(~$10.6)인데 FE에 나가는 건 정렬 앞쪽 2,000건이었다(백필 비용
~$2.5 수준으로 축소). 08-12 안전 밸브(`max-posts-per-sweep` 10,000·ERROR·커버 미처리)는 코드에
그대로 있으나 **기본 설정(2000 < 10000)에서는 안쪽 컷이 먼저 걸려 도달 불가**다 — 컷을 알리는
유일한 신호는 INFO 로그(Loki `{service="monitoring"}`)이고, 밸브는 상한을 10,000 초과로 올리거나
0(무제한)으로 끈 구성에서만 되살아난다(그래서 밸브 보정 절차 "상한 상향 + `last_swept_on` 리셋
재백필"은 이제 `collection-post-limit`도 함께 올려야 성립한다). 티어 정책·저장 스키마·was API·
편입 컷 무변경, DB 마이그레이션 없음. **알려진 여파 2건**: ① was 성과 대시보드
`PerformanceComparisonAssembler.covered`가 `backfill_completed_at` + `collection_months` 기준이라
상한 컷 밖 기간을 "수집했는데 0건"으로 오표시한다 — covered 판정에 실수집 깊이(최고령
`taken_at`)를 반영하는 것이 **후속 과제**(아래 미결·후속). ② 탭 뱃지(전체 행)≠목록(2,000)
불일치는 상한과 무관하게 **고물량 브랜드의 정상 상태**다 — 상한은 열거량을 자르지 저장 행 수를
자르지 않아, 37건/일 브랜드는 평범한 일일 수집만으로 ~54일이면 2,000행을 넘는다(신규 브랜드도
수렴하지 않는다).

## 잔여 작업

- **[staging 승격 전]**
  - ~~연속 실패 서킷브레이커 + 스윕당 판정 상한~~ → **구현 완료(2026-08-18)** — #490(백필 기동
    즉시·상한 제거) 이후 스테이징에서 무료 키 쿼터 공유로 429 폭주(15분간 분당 83~146건) 실측이
    계기. `AdDisclosureJudgeService`에 `llm-failure-abort-threshold`(기본 10)·
    `backfill-max-per-run` 추가, 동반해 monitoring Gemini 호출을 Vertex로 전환
    (`common-llm` 모듈 신설 — DECISIONS.md 08-18 항목 참조). 상한 기본값은 도입 직후
    1000 → **0(무제한)으로 원복**(같은 날 사용자 결정 — 429의 원인은 상한이 아니라 무료 키
    쿼터였고 Vertex + 서킷브레이커로 무제한이 안전. 상한은 env로만 임시 사용).
  - 캡션·videoUrl 저장값 폴백 — 일시적 결손(보강 미완주 등) 시 캡션 부재를 그대로
    `NOT_DISCLOSED`로 오판정하지 않도록 방어.
  - `judgePosts` 성공 요약 로그 — 배치당 verdict 분포를 남겨 드라이런 검토 근거로 쓴다.
- **[expose 토글 켜기 전]**
  - ~~180~365일치 one-shot 백필 경로~~ → **구현 완료(2026-08-18, 스펙 §7-1) → 08-18 재개정
    (상한 제거·기동 즉시 실행)**: 정기 스윕 재열거가 없는 구간(180일 초과)의 기존 게시물은
    앱 기동 완료 시 `AdDisclosureBackfillStartupRunner`가 별도 데몬 스레드에서
    `AdDisclosureJudgeService.backfillUnjudged()`를 즉시 시작해 전량 처리한다(상한
    없음 — 종전 `monitoring.brand.ad-disclosure.backfill-per-night` 설정 삭제, LLM 전용 풀
    동시 4가 자연 속도 제한). 배포 직후 재고가 다음 야간 스윕을 기다리지 않고 바로 판정된다.
    `BrandSweepJob`의 매일 밤 백필 훅은 유지되지만 역할이 "실패 잔량 재시도 안전망"으로
    바뀌었다(같은 함수를 상한 없이 재호출) — 기동 백필과 겹치면 `AdDisclosureJudgeService`
    내부 `AtomicBoolean` 가드가 이중 실행을 막는다.
  - verdict 분포·NULL 잔량을 확인하는 정본 스크립트(`monitoring/check/` 디렉토리에 추가) —
    "분석 잔여 몇 건" 류 질문에 즉석 쿼리로 오답하지 않도록 정본화(다른 정본 스크립트
    `analytics/check/pending.sh`와 같은 취지).
  - 골드셋 200건으로 오탐률(특히 `NOT_DISCLOSED` 오탐) 측정.
- **[후속]**
  - ~~판정 로직과 join(시딩 계정 조인 등)을 분리하는 리팩터~~ → **08-18 시딩 계정 관리 표면
    철회로 전제 소멸**: `seededAuthor`는 이제 별도 등록 목록이 아니라 캠페인 데이터에서 도출되고,
    그 조합(`BrandPostAssembler.resolveSeededUsernames`)은 처음부터 판정 로직(`AdDisclosureJudgeService`)과
    분리된 was 코드다 — 분리할 join이 남아 있지 않다.
  - ~~시딩 계정 username 문자셋 검증~~ → **08-18 시딩 계정 관리 표면 철회로 전제 소멸**: 유저가
    직접 입력하던 등록 표면 자체가 없어져 검증 대상도 함께 사라졌다.
  - `brand_seeded_account` 테이블·마이그레이션 DROP(contract 단계) — 08-18부로 was·monitoring
    양쪽 다 이 테이블을 참조하지 않는다(미사용 확정). expand-contract상 다음 릴리스에서
    `-- allow-destructive` 주석과 함께 제거 가능.
  - ~~백필 굶음 방어~~ → **08-18 상한 제거로 시나리오 자체가 소멸**: 상한(야간 1000건)이 없어져
    "영구 실패가 limit 윈도우를 잠식해 나머지 백로그가 굶는다"는 전제가 사라졌다. 남는 것은
    영구 실패 건의 무한 재시도(매 호출마다 1회씩 재시도 후 배치 종료 — 서비스 코드 §7-1
    "영구 실패 배치는 무한 재조회하지 않는다" 참조)뿐이고, 이는 LLM 비용이 소량 낭비되는
    수준이라 무해하다.

## 미결·후속

- ~~창 밖 tagged 등록이 DUPLICATE로 막혀 데드엔드가 되는 문제, "의도된 대가"로 수용~~(08-17
  링크 레벨 표시 창) → **08-18 direct 파이프라인 통합으로 대가 없이 해소**: 창 밖 tagged를
  직접 등록하면 `direct_registered_at`이 채워지고 direct 행은 표시 창 예외라 그 자리에서 바로
  보인다.
- ~~겹침 게시물(사진 태그+직접 등록)이 `mergeByShortcode`의 "direct 우선" 규칙으로 카드가 영구
  direct 셰이프에 고정되는 문제~~(08-13 완결 배치 서빙 미정산 방어 근거) → **08-18 direct
  파이프라인 통합으로 해소**: 조립 셰이프가 `brandPost()` 한 벌이 되며 "고정"이라는 개념 자체가
  소멸.
- **direct 파이프라인 통합 — 이관(M) 실행 대기**(08-18) — M1(운영 규모 확인 SQL, 설계 §7 R1) →
  승인 → M2(이관 잡 실행) → M3(콜 증분 2주 실측)는 **배포 후 별도 세션**이 수행한다. 배포
  직후에는 과도기 폴백이 레거시 셰이프를 그대로 얹으므로 이관 전까지 화면은 현행과 동일하다.
- **direct 파이프라인 통합 — 신규 미결 R1~R9**(08-18, 설계 §7) — 위 본문 단락 참조. 특히
  R2(다중 유저 브랜드 취소 권한 완화)는 배포 전 영향 브랜드 수 확인이 필요하고, R6(`cancel`
  무아카이브 hard delete)은 기존 결함이 이번에도 미해결로 남는다.
- **direct 파이프라인 통합 — contract(C) 단계는 다음 릴리스**(08-18) — 레거시 폴백 조립
  (`assembleLegacyPending`) 제거, `app.brand_direct_posts.monitoring_item_id`·`migrated_at`
  DROP, `brand_tagged_post.tag_detected_at` DEFAULT 제거. 참조 코드가 끊긴 뒤에만 가능(expand-
  contract).
- ~~was 조회 API·FE 계약~~ → **구현 완료**(08-07, PR #354 — DECISIONS 08-07 행·[spec 2026-08-07](../superpowers/specs/archive/2026-08-07-brand-monitoring-was-api-design.md)). FE 명세 대비 의도적 편차 5개는 FE 공유 필요(스펙 §2).
- ~~`/v2/user/by/id` 응답 셰이프 라이브 미실측~~ → **실측 반영**(08-07): 파라미터명이 `user_id`가 아니라 `id`(422 실측 핫픽스 4ab01545). 응답 셰이프는 by/username 동형 확인.
- ~~운영 크론 env 주입~~ → **가동 중**(08-07): KST 03:00(UTC 18:00), 캠페인 스윕(KST 02:00)과 시차 확보. 서버 override 선주입분을 레포 `deploy/compose.yaml`로 정합(드리프트 해소).
- ~~Task 11(캠페인 v2) 급행 머지로 리뷰 생략~~ → **08-07 사후 리뷰·픽스 완료**: Critical 0. Important 4 중 3(취소 아이템 재등록 경로·제거 전건 해제·레거시 위임 계약 통합 테스트)과 Minor 2(202 조건·trim)는 픽스 반영(DECISIONS 08-07 판정 행). **잔여 후속**:
  - ~~링크 1건당 레거시 patch 전량 재조립(아이템당 ~9쿼리 × 상한 100)~~ → **슬림 경로 분리 완료**(08-07, PR #360): `CampaignItemLinker`(캠페인·아이템 소유 검증 유지 + `updateCampaign`만, 아이템당 3쿼리)로 v2 연결·해제 교체. 레거시 0줄 변경 — 트레이드오프는 DECISIONS 08-07 슬림 경로 행.
  - reasonCode 어휘 이원화 — v2 대문자(`NOT_FOUND`·`CAMPAIGN_CONTENT_ALREADY_EXISTS`) vs 레거시 entry 소문자(`not_found`·`duplicate`). FE와 한 번 정리 필요(스펙 §9 어휘 자체가 두 갈래).
  - tagged 윈도우 밖 게시물의 추가 실패 사유가 "게시물을 찾을 수 없습니다"로 뭉개짐 — 실해 낮음, FE 문의 오면 재론. 정책 v1로 등록 컷이 90일 → 365일이 되면서 해당 케이스 자체가 줄어든다.
- 크롤링 정책 v1은 **배포 전**(08-09 기준 구현 + `:monitoring:test` 423개 통과까지) — 스펙 §8 비용 재산정 표의 게시자 프로필 콜 수(N명)는 운영 배포 후 등록 1건 실측으로 갱신할 것. **스케일 가정 2,000계정 총액도 그때 함께 재산정**(종전 $550~600은 매일 전량 전제라 폐기). 코드 변경 아님.
- **tooq.official 172~365일 공백 일회성 보정 실행 대기**(08-12) — 상한 상향 배포 후 운영 monitoring DB에 `UPDATE brand_account SET last_swept_on=NULL WHERE id=34` 실행(사용자 확인 필요), 익일 KST 03:00 스윕이 재백필. 미루면 365일 창이 실행일 기준이라 하루씩 잘린다 — 조기 실행 권장.
- **완결 배치 서빙 — FE 배포 조율 필요**(08-13) — 이 변경 후 **기간 확장 중 `collectionStatus`가 `collecting` → `ready`**로 바뀐다. FE가 확장 배너 판정을 `collectionCompletedAt == null`로 옮기기 **전에** 운영 승격되면 배너가 조용히 사라진다(FE 회신 문서 §3-7로 통지). 프론트 반영 여부를 확인한 뒤 staging → main 승격할 것.
- **완결 배치 서빙 — 배포 시점 확장 중이던 계정 보정 판단**(08-13) — 배포 순간 이미 기간 확장 중이던 계정은 `expandWindow`의 `backfill_completed_at` 리셋을 못 받고 옛 완주 시각을 들고 있어 FE 폴링이 즉시 종료된다(다음 새벽 스윕까지 화면 갱신 지연). **일회성이고 데이터 유실 없음** — 보정 UPDATE 실행 여부는 배포 시 대상 건수를 보고 판단.
- **링크 레벨 표시 창 — 배포 후 운영 수동 보정 1회**(08-17) — 신청값이 지금까지 어디에도 저장된 적이 없어 마이그레이션이 복원할 수 없다(기존 링크는 전부 12). 대상은 cclime 3개월 유저 + **단독 구독(활성 링크가 정확히 1개) 브랜드 전체** — 단독 구독은 자산값 = 그 유저의 신청값이라 자산에서 복원할 수 있다(다중 구독은 max라 복원 불가 → 그대로 12 유지, 개별 확인). app DB와 monitoring DB가 분리라 2단계(monitoring에서 `collection_months < 12 AND status = 'ACTIVE'` 브랜드 확인 → app에서 단독 링크만 UPDATE). 절차 SQL은 PR #480 본문.
- **링크 레벨 표시 창 — 링크 창 미적용 표면**(08-17) — 성과 대시보드(`PerformanceComparisonAssembler`의 `covered`·집계 모수)와 `hashtag-posts` 목록은 아직 자산 창 전량을 본다. 3개월 유저에게 게시물 counts와 대시보드 모수가 달라 보인다(의도적 범위 밖 — FE 문의·혼선 발생 시 재론).
- **수집 개수 상한 — 성과 대시보드 covered 판정 정정**(08-19, 후속 과제) —
  `PerformanceComparisonAssembler.covered`가 `backfill_completed_at` + `collection_months`만 보므로
  `collection-post-limit` 컷으로 실제로는 열거하지 않은 더 깊은 기간까지 "수집 완료 → 0건"으로
  표시된다. 판정에 실수집 깊이(그 브랜드의 최고령 `taken_at`)를 반영해야 한다
  ([spec 2026-08-19 §3-3](../superpowers/specs/2026-08-19-brand-collection-post-limit-design.md)).
- **링크 레벨 표시 창 — 창 필터 SQL 푸시다운**(08-17) — 지금은 자산 창 전량을 조립한 뒤 메모리에서 자른다. 3개월 유저가 12개월 브랜드를 볼 때 버려지는 조립 비용이 크면 리포지토리 조회 컷을 링크 창으로 내리는 최적화가 후속.
- **완결 배치 서빙 — 운영 반영 직후 백필 확인**(08-13) — `SELECT count(*) FROM brand_tagged_post WHERE enriched_at IS NULL`이 **0**이어야 한다. 0이 아니면 마이그레이션 백필(25,759행)이 안 돈 것이고, 그만큼의 게시물이 목록에서 사라진 상태다.
- **해시태그 FE 요청 일괄(08-17) — 구현 완료, 잔여 4건**(태그 등록 즉시 스윕·제외 규칙 폐기·direct 취소 API·brandPostId·작성자 프로필 아카이브 — DECISIONS 08-17 행, 계약 v2.9):
  - `brand_hashtag_exclusion` 테이블 DROP — contract 단계라 **다음 릴리스**에서(`-- allow-destructive` 주석 필요). 참조 코드는 이번에 전부 제거됨.
  - 기존 브랜드에 이미 시드된 3종 태그(브랜드명·계정명 루트)는 그대로 남는다 — 축소는 신규 시드부터. 정리는 유저 태그 관리 API로 가능하므로 일괄 삭제는 하지 않기로(유저가 의도적으로 쓰는 태그일 수 있음).
  - 기존 저장 verdict 중 제외 문자열 substring 매칭으로 SELF 처리된 행은 불변(판정은 저장 후 불변 원칙) — 신규 수집분부터 정확 일치 기준.
  - 경쟁사(competitor) 계정: 해시태그 감지는 돌지만 direct 등록이 403이라 발견 카드 "성과 측정 시작"은 own 전용 — **FE 회신 필요**(경쟁사 화면 승격 버튼 노출 정책).
  - **08-18 정정**: `brandPostId`가 tagged로 채워지는 경로를 제거 — 발견 목록("태그 안 된 게시물")에서 tagged 겹침 행(사진 태그+해시태그 동시 게시물) 자체를 제외한다, direct 매핑이 있는 행은 tagged 여부와 무관하게 유지(direct 우선). 계약 v2.10 내 정정, `feat/hashtag-hide-tagged-overlap`.
