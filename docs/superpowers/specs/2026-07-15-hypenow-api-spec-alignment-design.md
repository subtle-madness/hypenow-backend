# HypeNow API 스펙 정렬 설계 (프론트 계약 v1 채택)

> 상태: 🟢 활성
> 6.17 이메일 인증 [TBD]는 [2026-07-18-email-verification-design.md](archive/2026-07-18-email-verification-design.md)로 해소(07-19 구현)
> 작성: 2026-07-15 · 근거: 프론트 전달 API 스펙 v1(노션 "hypenow API 문서", 2026-07-15) + 백엔드 현황 대조

## 1. 배경과 결정

프론트(celfit-front)가 목데이터·타입 분석 기반의 API 계약 문서(HypeNow API Spec v1)를 전달했다.
**스펙 전체를 was API 계약 정본으로 채택**하고, 단계별로 구현한다.

| 결정 | 내용 |
|---|---|
| 채택 범위 | 스펙 전체(6.18 fit 제외). fit은 스펙에서도 [TBD]이며 취소된 캠페인 추천 피봇(닫힌 PR #5)과 같은 영역이라 별도 재논의 |
| 분석 윈도우 | 스펙의 "최근 12개 게시물"에 정렬. develop 기준 `analytics.recent-window` 기본값이 이미 12라 추가 조치 없음(값 고정 확인만) |
| /v1 도입 방식 | was에 `v1` 패키지 신설, 기존 `/api/*`·내부 페이지(`/posts`·`/coverage`·대시보드)와 **병존**. 프론트 전환 완료 후 구 `/api` 제거 |
| 인증 | **오늘 머지된 트랙 G(PR #18, Spring Security 세션 쿠키+CSRF)를 유지하고 확장** — HttpSession을 Spring Session JDBC로 교체해 스펙의 세션 목록·개별 로그아웃 요구 충족(§6) |
| 세션 만료 | 슬라이딩 30일 (Spring Session `maxInactiveInterval` = 30일이 곧 슬라이딩) |
| 도메인 구도 | 프론트를 hypenow.io 서브도메인(Vercel 커스텀 도메인)으로 — same-site. 쿠키 `SameSite=Lax`, `Domain=.hypenow.io` |

## 2. 단계 분해

| 단계 | 내용 | 스펙 절 | 데이터 전제 |
|---|---|---|---|
| **P1. V1 읽기 API** | envelope·에러 공통 + 리더보드·콘텐츠 AI 리포트·인플루언서 프로필·인플루언서 AI 리포트 | 3, 6.1, 6.3, 6.4, 6.5 | 기존 미러로 대부분 충당 + 데이터 층 소보강(§4) |
| **P2. 서비스 데이터 정렬** | 기존 G 확장: Spring Session JDBC 전환, users 프로필 필드, 저장 2종 스펙 계약화, me/세션/탈퇴, 게이트 이벤트. P1 응답에 개인화 필드(isContentsSaved 등) 활성화 | 4, 6.6~6.17, 6.19 | `app` 스키마 마이그레이션 V2+ |
| **P3. 부가** | 랜딩 통계 + **유사 콘텐츠**(유사도 사전계산 — analytics 신규 태스크) | 6.20, 6.2 | 유사도 테이블 신설 |
| 보류 | fit | 6.18 | — |

P1은 인증 없이도 스펙 계약대로 응답 가능(Optional 엔드포인트는 비로그인 시 개인화 필드 자체를 미포함하는 게 스펙 규약)
→ 프론트가 P1만으로 실데이터 연동을 시작할 수 있다.

## 3. P1 — V1 읽기 API (was `v1` 패키지)

- `v1/common` — `ApiResponse<T>`(success/data/error/meta) envelope record, `@RestControllerAdvice`로
  스펙 3.2 에러 코드 표 전체 매핑(error.message는 사용자 노출용 한국어). 카테고리 계약(스펙 5.5)은
  이미 analysis DB `beauty_taxonomy`(B4에서 신설, 프론트 필터와 1:1)가 단일 원천 — was는 이 테이블을
  읽어 중분류→소분류 확장 매칭에 사용(Java 상수 하드코딩 불필요, 어휘 변경은 분석 층 마이그레이션).
- `GET /v1/contents` — 필터: 카테고리 3단(대분류 슬러그, 중분류는 소속 소분류로 확장 매칭, 소분류 한글 라벨),
  follower 밴드(3k-10k/10k-30k/30k-50k, min 이상 max 미만), keyword(caption ILIKE, AND 스택),
  postedAt 구간(필수, KST 달력 날짜), adType, distributorId(슬러그, `none` 포함), sort 3종(hype/latest/views,
  동점 시 id 오름차순), limit≤100. `meta.total`(필터 매칭 전체 건수)·`limit`·`distributors`(유통사 옵션 전체).
- `GET /v1/contents/{id}/ai-report` — `content_analyses` + `comment_classifications` + `content_comments` 조립.
  스펙 6.3의 scope/summary/comparison/categoryContext/vlmAnalysis/commentAnalysis/comments 구조.
- `GET /v1/influencers/{id}` — `accounts`+`account_summaries` 프로필 + 최근 12개 Content 카드.
- `GET /v1/influencers/{id}/ai-report` — `account_summaries`+`account_analyses`+`account_content_series`+
  `account_category_stats`로 스펙 6.5의 tagline/stats/trend/chart/contentMix/ads/activity 조립.
- **ID 정책**: contentId = `short_code`, influencerId = `handle` 그대로(스펙상 string이면 형식 자유).
- **노출 조건**: Content 카드의 mainCategory/subCategories/adType은 `content_analyses` 산출이므로
  **분석 완료 콘텐츠만 리더보드에 노출**한다(미분석 콘텐츠는 서빙 대상 아님).
- Content 카드 조립은 분석 결과 테이블 간 조인(허용). 서비스 데이터(`app`)와의 조합은 was 코드에서(§4-4 준수).

## 4. P1 병행 — 데이터 층 보강 (analytics)

1. **hypeScore 재정의**: 현재 미러의 `hype_score`는 릴스=조회수 원값·피드=좋아요+댓글 원값(0~100 아님).
   스펙 5.4 산식(도달 효율 × 참여의 질 × 신선도의 세제곱근, 0~100 정수)으로 뷰 재작성.
   - 피드(views NULL)는 스펙 산식 적용 불가 → **views 축을 제외한 참여·신선도 기반 대체 산식**으로
     동일 0~100 스케일 산출(상세 산식은 구현 계획에서 확정, 스펙 [확인 필요] #7 회신 사항).
   - 신선도 축이 조회 시점 의존이라 미러 갱신 주기와 정렬 일관성(sort=hype ↔ hypeScore 내림차순 일치)을
     구현 계획에서 함께 다룬다.
2. **유통사 슬러그**: 스펙의 유통사 필터는 ID(`oliveyoung` 등) 기준. 유통사 어휘는 이미
   `beauty_distributors`(B4)가 단일 원천이나 한글명뿐 → **slug 컬럼 추가** 마이그레이션
   (생산자=분석 층이 슬러그 확정, was는 전달만 — ARCHITECTURE §4-4 원칙).
3. **products(제품명)**: `content_analyses.detected_products`([{name, brand}], B4 신설)의 name을 서빙.
   B4 이전 분석분은 값이 없으므로 재분석 커버리지를 구현 계획에서 점검.
4. **email/externalLink**: 미러에 없음. `raw_profile` payload 보유 여부 조사 → 있으면 미러 추가, 없으면 null 확정.
5. **updatedAt**: 지표 확정 스냅샷(+N일 고정 규칙)의 수집 시각을 미러에 노출.

## 5. P2 — 서비스 데이터 정렬 (기존 G 유지 + 확장)

기존 구현(PR #18: Spring Security·BCrypt·쿠키 CSRF·`/api/auth/*`·`/api/saved/**`)을 보존하고 확장한다.

- **세션 저장소**: HttpSession(인메모리) → **Spring Session JDBC**(`app` 스키마, Flyway로 테이블 정의,
  `initialize-schema=never`). `FindByIndexNameSessionRepository`의 principal 인덱스로 사용자별 세션
  목록 조회·개별 삭제(`GET/DELETE /v1/me/sessions`)를 구현. browser/os는 로그인 시 User-Agent를 1회
  파싱(주요 패턴 매칭 수준)해 세션 attribute로 저장, `current`는 현재 요청 세션 id 비교.
- **쿠키**: 이름 `hypenow-session`, HttpOnly·Secure·`SameSite=Lax`·`Domain=.hypenow.io`(로컬 프로필은
  Domain 생략)·Max-Age 30일. 슬라이딩은 Spring Session 기본 동작.
- **CSRF**: 기존 XSRF-TOKEN 쿠키 → `X-XSRF-TOKEN` 헤더 방식 유지(same-site의 SameSite=Lax와 이중 방어).
  프론트 공통 헤더에 `X-XSRF-TOKEN` 왕복 추가 필요 — 프론트 협의 사항(§7 회신표).
- **users 확장**: V2 마이그레이션으로 스펙 6.12/6.15의 프로필·동의 필드 전부 추가
  (name, nickname, user_type, signup_route, phone_*, company_*, industry, job_title, agreed_*,
  marketing_updated_at, profile_image_url). 기존 행은 기본값 백필.
- **저장 2종**: `saved_contents`에 memo 추가(공백·빈 문자열은 null 정규화, upsert 시맨틱).
  `saved_influencers`의 status(검토중/컨택 예정/협업 중)는 스펙에 없지만 **컬럼 유지·v1 응답 미노출**
  (후보 관리 기능 재도입 대비). 응답은 스펙 6.6~6.11 계약(Content 카드 조인, 최근 저장 순, 삭제는 멱등 204).
- **계정 부속**: `PATCH /v1/me`(부분 업데이트, agreedMarketing 변경 시 marketing_updated_at 갱신),
  비밀번호 변경(변경 시 **현재 세션 제외 전 세션 무효화** — 스펙 미결 #14 회신), 프로필 이미지
  업로드(1MB·PNG/JPEG — 저장소는 구현 계획에서), 회원탈퇴(비밀번호 확인, 전 세션 무효화,
  저장 데이터 즉시 삭제 — 미결 #12 회신).
- **게이트 이벤트**: `POST /v1/events/gate` — `app.gate_events`(user_id nullable, event_type, payload jsonb).
  fire-and-forget이므로 검증 실패도 204.
- **레이트리밋**(로그인/가입/이벤트): 이메일+IP 카운터를 인메모리로(단일 인스턴스 전제), 스케일아웃 시 재검토.
- **API 표면**: `/v1/auth/*`·`/v1/me*`·`/v1/saved-*`를 envelope 계약으로 신설(기존 컨트롤러 로직 재사용),
  구 `/api/auth`·`/api/saved`는 병존 후 제거.
- 이메일 인증(6.17)은 발송 인프라 미정으로 계속 [TBD] — 라우트만 예약. 프론트의 개발용 마스터 비밀번호
  백도어는 승계하지 않는다(스펙 4절).

## 6. P3 — 부가

- `GET /v1/stats`(6.20): 미러 집계 + 주간 갱신 전제의 강한 HTTP 캐시. totalViews/avgViews 모수는
  "조회수 측정 가능 콘텐츠(릴스)"로 정의해 회신(#16).
- `GET /v1/contents/{id}/similar`(6.2): **유사도 사전계산 테이블 신설** — 이미지(썸네일 임베딩)/텍스트(캡션
  임베딩) 유사도, kind 3종(image/text/both). 계산 주체는 analytics(원칙 §4-2), 갱신 주기·임베딩 방식은
  착수 시 별도 설계. 응답에 source 콘텐츠 포함(스펙 [제안] 수용).

## 7. 스펙 7절([확인 필요]·[TBD]) 백엔드 회신표

| # | 항목 | 회신 |
|---|---|---|
| 1 | 피드 views | **수집 불가 확정** — Instagram이 피드 조회수를 제공하지 않음. null 규약 유지 |
| 2 | email/externalLink | raw_profile 보유 여부 조사 후 확정(§4 항목 4). 미보유면 null |
| 3 | 유사도 테이블 | 현재 없음 — P3 신규 구축. 갱신 주기는 P3 설계에서 |
| 4 | AI 리포트 생성 주기 | 오프라인 배치(현재 수동 트리거). 미생성분은 404 NOT_FOUND. 자동화 주기는 미러 운영 결정과 함께 |
| 5 | 댓글 수집 | **주의: 07-14 결정으로 댓글 수집이 MVP에서 제외**돼 신규 유입이 없음(기존 수집분은 게시물당 최대 50개). 스펙 6.3의 commentAnalysis·comments를 살리려면 수집 재개 결정 필요 — PO 재확인 항목. author 마스킹 규칙은 P1 구현에서 확정 |
| 6 | percentile 모수 | `category_sample_size` = 동일 대분류로 분류된 수집 콘텐츠 전체(윈도우 내). 정의 문구를 P1에서 응답 필드 주석으로 명문화 |
| 7 | 피드 hypeScore | views 축 제외 대체 산식(참여·신선도 기반, 동일 0~100 스케일)로 산정 — §4 항목 1 |
| 8 | 이미지 CDN | 인스타 CDN 서명 만료 실측 확인됨(122건 중 118건 실패). 재수집/프록시 전략은 별도 태스크 — P1은 보유 URL 그대로 |
| 9 | 날짜 타임존 | postedAt은 KST(Asia/Seoul) 달력 날짜로 변환해 응답 — 확정 |
| 10 | 이메일 인증 | [TBD] 유지 — 발송 인프라 결정 필요 |
| 11 | 콘텐츠 단건 조회 | 스펙대로 미도입(유사 응답 source로 대체), 딥링크 수요 생기면 재논의 |
| 12 | 탈퇴 데이터 | 즉시 삭제(저장 목록 CASCADE). 유예 필요 시 재설계 |
| 13 | fit | 보류 — 취소된 캠페인 추천 피봇과 같은 영역, 재논의 필요 |
| 14 | 비번 변경 시 세션 | 현재 세션 제외 전 세션 무효화 |
| 15 | AI 리포트 잠금 | 스펙대로 전체 응답 + 프론트 블러 유지. 민감해지면 서버 마스킹 전환 |
| 16 | 랜딩 통계 모수 | totalViews/avgViews = 릴스(조회수 측정 가능분) 기준. 주간 배치 |
| 추가 | CSRF 헤더 | 상태 변경 요청에 `X-XSRF-TOKEN` 헤더 왕복 필요(XSRF-TOKEN 쿠키 반사) — 스펙 3절 공통 헤더에 추가 요청 |
| 추가 | 지표 신선도 | 지표는 업로드 +N일 고정 스냅샷 기준(계정 간 공정 비교), updatedAt은 해당 스냅샷 수집 시각 — "조회 시점 최신값" 문구와 다름을 프론트에 고지 |

## 8. 검증

- was: MockMvc 계약 테스트(envelope 형태·에러 코드 표·개인화 필드 부재/존재·필터 조합) + Testcontainers.
- analytics: 신규·변경 뷰는 SQL 하니스(`NN_*.test.sql`, 더미 시드 + BEGIN/ROLLBACK) 컨벤션.
- 스펙 예시 JSON ↔ 실응답 필드 대조 체크리스트를 구현 계획에 포함.

## 9. 미결

- 인증 이메일 인증 플로우(6.17) — 발송 인프라.
- 이미지 CDN 전략(#8) — 썸네일 재수집/프록시.
- 유사도 계산 방식(P3) — 임베딩 모델·갱신 주기.
- fit(6.18) — 재논의.
- 프로필 이미지 저장소(P2) — 로컬/S3.
