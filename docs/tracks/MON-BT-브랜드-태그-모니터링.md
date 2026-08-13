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

완결 배치 서빙(2026-08-13 — **설계 합의만, 미구현** ·
[spec 2026-08-13](../superpowers/specs/2026-08-13-brand-initial-batch-serving-design.md)):
FE 요청서(08-13)로 "완성된 게시물만 내려달라"는 계약 변경이 들어와, 서빙 판정을
"열거 적재됨"에서 **게시물 단위 보강 정산**(`brand_tagged_post.enriched_at` 신설)으로 옮긴다.
방출 단위는 열거 페이지(21건) 배치 — **첫 페이지 배치 완료가 곧 `markServing`**이라 08-12의
서빙 창 30일 기준(`serving-window-days`)은 제거된다. 운영 실측(08-13, 브랜드 5개·470여 콜)이
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
특정 콜에 산발적으로 붙었다). 마이그레이션은 **기존 25,759행 백필 필수**(누락 시 게이트
도입 순간 전 브랜드 목록 공백), 배포 순서 monitoring → was.

## 미결·후속

- ~~was 조회 API·FE 계약~~ → **구현 완료**(08-07, PR #354 — DECISIONS 08-07 행·[spec 2026-08-07](../superpowers/specs/archive/2026-08-07-brand-monitoring-was-api-design.md)). FE 명세 대비 의도적 편차 5개는 FE 공유 필요(스펙 §2).
- ~~`/v2/user/by/id` 응답 셰이프 라이브 미실측~~ → **실측 반영**(08-07): 파라미터명이 `user_id`가 아니라 `id`(422 실측 핫픽스 4ab01545). 응답 셰이프는 by/username 동형 확인.
- ~~운영 크론 env 주입~~ → **가동 중**(08-07): KST 03:00(UTC 18:00), 캠페인 스윕(KST 02:00)과 시차 확보. 서버 override 선주입분을 레포 `deploy/compose.yaml`로 정합(드리프트 해소).
- ~~Task 11(캠페인 v2) 급행 머지로 리뷰 생략~~ → **08-07 사후 리뷰·픽스 완료**: Critical 0. Important 4 중 3(취소 아이템 재등록 경로·제거 전건 해제·레거시 위임 계약 통합 테스트)과 Minor 2(202 조건·trim)는 픽스 반영(DECISIONS 08-07 판정 행). **잔여 후속**:
  - ~~링크 1건당 레거시 patch 전량 재조립(아이템당 ~9쿼리 × 상한 100)~~ → **슬림 경로 분리 완료**(08-07, PR #360): `CampaignItemLinker`(캠페인·아이템 소유 검증 유지 + `updateCampaign`만, 아이템당 3쿼리)로 v2 연결·해제 교체. 레거시 0줄 변경 — 트레이드오프는 DECISIONS 08-07 슬림 경로 행.
  - reasonCode 어휘 이원화 — v2 대문자(`NOT_FOUND`·`CAMPAIGN_CONTENT_ALREADY_EXISTS`) vs 레거시 entry 소문자(`not_found`·`duplicate`). FE와 한 번 정리 필요(스펙 §9 어휘 자체가 두 갈래).
  - tagged 윈도우 밖 게시물의 추가 실패 사유가 "게시물을 찾을 수 없습니다"로 뭉개짐 — 실해 낮음, FE 문의 오면 재론. 정책 v1로 등록 컷이 90일 → 365일이 되면서 해당 케이스 자체가 줄어든다.
- 크롤링 정책 v1은 **배포 전**(08-09 기준 구현 + `:monitoring:test` 423개 통과까지) — 스펙 §8 비용 재산정 표의 게시자 프로필 콜 수(N명)는 운영 배포 후 등록 1건 실측으로 갱신할 것. **스케일 가정 2,000계정 총액도 그때 함께 재산정**(종전 $550~600은 매일 전량 전제라 폐기). 코드 변경 아님.
- **tooq.official 172~365일 공백 일회성 보정 실행 대기**(08-12) — 상한 상향 배포 후 운영 monitoring DB에 `UPDATE brand_account SET last_swept_on=NULL WHERE id=34` 실행(사용자 확인 필요), 익일 KST 03:00 스윕이 재백필. 미루면 365일 창이 실행일 기준이라 하루씩 잘린다 — 조기 실행 권장.
