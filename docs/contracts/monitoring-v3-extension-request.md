# monitoring 모듈 확장 요구 — v3 프론트 계약 대응 (팀원 전달)

> 상태: ✅ 반영됨(P1·P2 구현 완료 — 계약 v2.2)
> 수신: monitoring 모듈 담당자. 계약 정본 [monitoring-was-contract.md](monitoring-was-contract.md)(v1.0)의
> 확장 요청이다 — 채택 시 계약 문서를 먼저 갱신한 뒤 코드에 반영하는 기존 절차를 따른다.
> 근거 계약: [monitoring-frontend-api-spec.md](monitoring-frontend-api-spec.md)(프론트 v3, 6.25~6.33).

## 배경 요약

프론트 모니터링 v3 계약이 확정됐다(2026-07-29 2차 개정). 핵심 변화:

1. **승인 큐 폐기** — 감지된 게시물은 유저 확인 없이 그대로 수집을 시작한다.
   (was가 approve 명령을 자동 대행하는 방식으로 monitoring 변경 없이 흡수 가능 — P3 참조)
2. **화면이 게시물 실체를 보여준다** — 캡션 원문·썸네일·게시일·댓글 원문이 카드에 뜬다.
   현재 조회 표면은 지표 숫자뿐이라 이 데이터의 산지가 없다.
3. **게시물 이상 상태 가시화** — 비공개/삭제(`hidden`), 우리 쪽 수집 오류(`error`)를
   유저에게 상태·알림으로 보여준다. 현재 계약에는 이 신호가 없다
   (숨김 갭은 07-29 이메일 알람 세션에서 이미 계약 노트로 기록된 그 건이다).

아래 우선순위는 was 쪽 영향 기준이다. **P1이 없으면 핵심 화면이 계약 위반(필수 필드 결손)으로 뜨지 못한다.**

## P1 — 필수 (없으면 프론트 계약의 null 불가 필드가 결손)

### 1. 게시물 메타 표면

프론트 `TrackedPost`는 `caption`(원문 전문, 개행 유지)·`uploadedAt`(게시일)이 **null 불가**,
`thumbnailUrl`이 nullable이다. 현재 표면엔 `detected_candidate.caption_excerpt`(발췌)뿐이다.

제안 (형태는 자유 — 계약을 만족하면 됨):

```sql
CREATE TABLE post_meta (
    short_code    text PRIMARY KEY,
    username      text NOT NULL,
    content_type  text,             -- REELS / FEED (post_snapshot과 동일 어휘)
    uploaded_at   date NOT NULL,    -- 게시일 (KST)
    caption       text NOT NULL,    -- 원문 전문, 개행 유지
    thumbnail_url text,
    first_seen_at timestamptz NOT NULL DEFAULT now()
);
```

- 적재 시점: 등록 동기 수집·후보 감지·approve 즉시 수집 등 게시물을 처음 만나는 시점.
- 캡션은 발췌가 아니라 **전문**이어야 한다(프론트가 원문 그대로 렌더).
- was_reader는 default privileges로 자동 SELECT 가능.

### 2. 숨김(비공개·삭제) 감지 신호

일일 스윕에서 추적 게시물 접근 불가(존재하지 않음·비공개 전환)를 감지해 노출:

- 제안: `target.tracked_hidden_at timestamptz null` — 감지 시각 기록,
  기간 내 재공개 감지 시 null로 복귀(프론트 상태 `hidden` ↔ `tracking` 복귀 규칙).
- "게시물이 없어진 것"과 "일시 수집 실패"의 구분은 monitoring이 확정한다
  (생산자 확정 원칙). was는 이 신호를 해석 없이 상태로 매핑한다.

### 3. 수집 오류(재시도 소진) 신호

프론트 상태 `error` = "게시물은 정상인데 우리 쪽 원인으로 지표를 못 쌓는 상태,
재시도 소진 뒤에만". 판정 기준(연속 실패 N회/N일)은 monitoring이 확정하고 결과만 노출:

- 제안: `target.fetch_failing boolean NOT NULL DEFAULT false` (또는 연속 실패 카운트 노출).
- 일시 실패(당일 1회 타임아웃)로 켜지면 안 된다 — 알림(`content_issue`)이 전 유저에게 나간다.

### 4. 스윕 배치 워터마크

프론트 `meta.lastCollectedAt` = "마지막으로 **성공한** 배치의 완료 시각". 부분 수집 중
조회 시 서로 다른 날짜의 증가분이 섞이는 것을 막는 유일한 수단이다. 현재 표면에는
배치 실행 메타가 없다(target.last_fetched_at은 대상별이라 "배치 완료"를 뜻하지 않음).

- 제안: `sweep_run(id bigserial PK, started_at timestamptz, completed_at timestamptz null, ok boolean)`
  1행/실행. was는 `max(completed_at) where ok`를 읽는다.

## P2 — 기능 완성 (빈 폴백으로 개통은 가능, 조기 확정 요망)

### 5. 댓글 수집

프론트 `PostComment`: 작성자(마스킹은 **was 응답 단계 책임** — monitoring은 원본 저장),
본문, 좋아요 수, 작성 시각, **게시물 작성자 본인 답글**(제3자 답글은 수집 대상 아님).

```sql
CREATE TABLE post_comment (
    id               text NOT NULL,          -- 인스타 댓글 ID
    short_code       text NOT NULL,
    author           text NOT NULL,          -- 원본 핸들 (was가 응답 시 마스킹)
    body             text NOT NULL,
    like_count       bigint NOT NULL,
    commented_at     timestamptz NOT NULL,
    owner_reply_text text,                   -- 작성자 본인 답글 (없으면 null)
    PRIMARY KEY (short_code, id)
);
```

- 게시물당 상한: 프론트 표시는 8개, 여유분 포함 **20개 제안**(최신순 교체 갱신).
- 수집 주기: 일일 스윕 동승이면 충분.
- 필드 결손 댓글(본문·좋아요 수집 실패)은 저장하지 않는 편이 낫다 — 프론트에 부분 결손 렌더 경로가 없어 was가 어차피 버린다.

### 6. 감지 매칭 키워드

`detected_candidate`에 `matched_keywords jsonb`(매칭된 키워드 배열). 프론트
`TrackedPost.matchedKeywords`의 소스. 캡션 전문(P1-1)이 있으면 was가 재계산할 수도
있지만, 매칭 판정의 정본은 감지를 수행한 monitoring이다.

### 7. 계정 메타

`TrackingItem` 카드 표시용(전부 nullable — 없어도 계약 위반은 아니고 화면이 비어 보일 뿐):

- `display_name`(계정 풀네임 — 현재는 was가 핸들로 대체 예정), `profile_image_url`,
  `last_uploaded_at`(계정의 최근 게시일 — 감지 중·미업로드 카드에 표시).
- 제안: `profile_snapshot`에 컬럼 추가 또는 `profile_meta(username PK, ...)` 신설.

### 8. 공유 단축 링크 해소

`instagram.com/share/...` 토큰은 shortcode가 아니라 리다이렉트 해소가 필요하다.
was에는 인스타 접속 수단이 없으므로(Hiker는 monitoring 소유) 등록 API에서 해소해 주면
좋다: `POST /api/targets`의 POST 등록이 `shareUrl`을 받아 해소 후 등록, 응답에
`resolvedShortCode` 포함. 실패 어휘 제안: `SHARE_LINK_UNRESOLVED`(422).
(대안: was가 직접 HTTP 리다이렉트를 따라간다 — 차단·비신뢰 환경이라 실패율 미지수.
MVP는 was 직접 시도로 가고, 실패율이 높으면 이 확장을 채택하는 순서도 가능.)

## P3 — 선택·확인

### 9. 자동 승인 (선택)

was가 02:30 크론으로 PENDING 후보를 approve 대행할 계획이라 **monitoring 변경 없이 동작한다**.
단 등록 시 `autoApprove: true` 플래그를 받아 감지 즉시 TRACKING 전환해 주면 감지→수집
시차가 사라지고 was 크론이 없어진다. [제안 — 여유 있을 때]

### 10. 캐러셀 유형 매핑 확인 (D4)

프론트 계약은 `reels`/`feed` 2종뿐. Hiker가 캐러셀(sidecar)을 어떤 값으로 주는지 확인하고
`content_type` 매핑을 확정해 달라. 제안: **FEED로 접기**(조회수 항상 null 규약과 일치).
매핑 결과를 계약 문서에 한 줄로 기록.

### 11. 스윕 완료 시각

09:00에 was가 다이제스트를 만든다 — **02:00 스윕이 09:00 전에 완료**된다는 기존 소비자
노트 전제를 v3에서도 유지한다. 지연 시 was는 다이제스트를 미루므로(늦게라도 그날 발송)
장애는 아니지만, 상습 지연이면 알람이 늦어진다.

## 참고: was가 자체 해결해서 monitoring 변경이 필요 없는 것

- 승인 큐 폐기 → was가 approve 자동 대행(P3-9 채택 전까지)
- `metrics_private` 알림(지표 값→null 전환) → post_snapshot 시계열 비교로 유도
- `not_uploaded` vs `ended` 구분 → EXPIRED + tracked_short_code 유무로 유도
- 취소 상태 매핑 → was가 취소 시점 상태를 자체 기록
- 유저별 스냅샷 창 자르기(등록일 이후만 노출·소급 금지) → was 쿼리에서 처리
- 같은 유저의 이중 추적 방지 → was가 approve 전 검사, 해당 후보는 reject(재제안 금지 효과)
- 댓글 작성자 마스킹 → was 응답 생성 단계

## 추기 (2026-07-30, PR #183 클로즈 확인 후)

#183 클로즈 코멘트로 monitoring 쪽 개편 방향(승인 플로우 제거·target.user_id 저장·알람 이벤트
대장·이벤트 4종)을 확인했다 — 이 문서의 P1·P3-9와 같은 방향이라 정합된다. 새 설계 확정 시
이 문서와 대조해 주고, 아래 두 가지는 계약에 명시가 필요하다:

1. **이메일 발송 주체** — 알람 모듈이 monitoring으로 가면 다이제스트 이메일도 monitoring이
   보내는가? 유저별 이메일 주소·옵트아웃 설정(6.33, was `app` 스키마 소유)과의 동기화 방법이
   계약에 필요하다. 대안: 이벤트 대장은 monitoring, **다이제스트 생성·이메일 발송은 was**(유저
   설정·주소를 이미 아는 쪽) — 이 경우 monitoring 알람 모듈은 이벤트 대장 적재까지만.
2. **이벤트 대장 조회 표면** — was 다이제스트(09:00)가 읽을 이벤트 테이블의 스키마
   (event_type·target_id·발생 시각·관련 shortcode)와 "같은 이벤트 1회만" 보장 방식.
