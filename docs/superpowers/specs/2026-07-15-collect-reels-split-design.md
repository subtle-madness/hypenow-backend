# collect 분리 — 게시물 프로필 수집 / 릴스 수집

> 상태: 🟢 활성 · 2026-07-15 설계 확정 (feat/beauty-captions 세션)

## 배경

collect 방문 1회 = 프로필 갱신(SELF GQL, $0) + 내장 피드 12개 추출($0) + 릴스 1페이지(HikerAPI, $0.001)가
한 버튼에 묶여 있었다. 유료 호출(릴스)과 무료 호출(프로필·피드)을 따로 통제할 수 없고,
UI 문구("프로필·게시물·릴스")도 실제 동작(프로필 방문이 곧 피드 수집)을 드러내지 못했다.

## 결정

### 1. COLLECT — "게시물을 위한 프로필 수집"으로 축소

- 프로필 방문(SELF GQL) + 내장 타임라인 피드 12개 추출 + content upsert만 수행.
- **릴스(HIKER_V2_CLIPS) 호출 제거** → 정상 경로 유료 요청 0.
- 프로필 갱신 실패 시 HIKER_GQL_MEDIAS 피드 1페이지 폴백은 유지(비정상 경로에만 Hiker 1요청).
- 북키핑(`first/last_collected_at`)·재방문 주기·뷰티 전용 대상 선정은 기존 그대로.

### 2. REELS — 신설 잡

- `JobName.REELS`. 대상: `QUALIFIED · beauty=true · (last_reels_at IS NULL OR < 재방문 주기)`,
  백필(null) 우선 · id 순 · 배치 한도(`reels.batch-limit`, 기본 10).
- 계정당 HikerAPI `/v2/user/clips` 1페이지 → raw_media_page 저장 + content upsert(shortCode dedup,
  DISCOVERY→ENUMERATION 승격 동일). 성공 시 `last_reels_at` 갱신(방문 단위 트랜잭션).
- **pk(`ig_user_id`) 없는 계정은 스킵 + 경고 로그** — 해석 요청을 쓰지 않는다. 프로필 수집이
  pk를 채우면 다음 실행에서 잡힌다. 릴스 잡은 **엄격히 계정당 Hiker 1요청** 보장.
  (2026-07-15 실측: 뷰티 406명 전원 pk 보유 — 스킵 경로는 현재 데이터에서 안 탄다.)
- 재방문 주기는 `collect.revisit-interval-days` 공유(주기 개념 동일, 키 중복 회피).

### 3. 스키마·설정

- Flyway V13: `influencer.last_reels_at timestamptz` 추가.
- `reels.batch-limit` — yml 기본값 + app_setting 오버라이드 + 설정 UI 자동 노출(기존 컨벤션).

### 4. UI

- 잡 실행 화면: 버튼 분리 — "게시물을 위한 프로필 수집"(collect) / "릴스 수집"(reels). 상태바에 릴스 행.
- 대시보드 ④ 수집 대기열: READY(게시물을 위한 프로필 수집 대기) + REELS_READY(릴스 수집 대기) 타일.
- 비용 추정: collect에서 릴스 단가 제거, reels 추정 행 신설(대기 × $0.001).

## 트레이드오프

- 북키핑 컬럼 1개 추가 비용으로 두 잡을 서로 다른 주기·속도로 독립 운용.
- pk 스킵 정책은 릴스 잡의 비용 보장(1요청/계정)을 위해 자립성(자동 해석)을 포기 —
  프로필 수집 선행이 사실상 항상 성립하므로 실질 제약 없음.
