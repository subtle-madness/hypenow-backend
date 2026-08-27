# F&B 재판정 루프 안정화 설계

> 상태: 🟢 활성

## 배경 — 무엇이 문제인가

08-25 F&B 백필(4만 계정 판정)과 `fnb.pipeline-enabled` 켜짐이 겹치면서, 기존 재판정
경로의 전제가 깨졌다.

- `findRejudgeTargets`(비뷰티 재판정)는 "beauty=false 계정은 수집하지 않으므로 프로필이
  거의 갱신되지 않는다"는 전제로 설계됐다 — 새 raw_profile 스냅샷이 생긴 계정만 골라
  재판정하면 자연히 드물고 자기 종결적이었다.
- F&B 파이프라인이 켜지자 F&B 인플루언서 ~6,500명이 "beauty=false인데 매 재방문마다
  프로필이 갱신되는" 계정이 됐다. 재방문 주기마다 전원이 재판정 큐에 재진입한다.
- 재판정은 양축을 모두 적용하므로 F&B 판정이 반복해서 덮인다. 08-26 18:04~19:41 UTC
  재판정 런(5,024명)에서 F&B INFLUENCER 이탈 899 / 편입 16(순감 883) — 대시보드 ③-2가
  ~7,390 → 6,507로 하락했다(Loki 로그 전수 대조 실측).
- 이번 이탈 자체는 정당한 교정이었다(백필이 캡션 없는 얇은 재료로 과편입한 것을 실측
  캡션으로 걸러냄 — 샘플 검증 완료). 문제는 교정이 아니라, **정착된 판정까지 매 주기
  모델 노이즈로 계속 뒤집히는 구조**다.

## 결정

### 1. F&B 축 정착 규칙 — `BeautyJob.applyVerdicts`

F&B 축 적용 가드를 다음으로 교체한다:

```
applyFnb = 이번 응답에 fnbClass 있음
        AND fnb_source != MANUAL                 (기존 가드 유지)
        AND ( fnb_class IS NULL                  (첫 판정 — 캡션 수 무관, 현행과 동일)
              OR (fnb_caption_count = 0 AND 이번 캡션 수 > 0) )   (업그레이드 재판정)
```

- **캡션 기반(fnb_caption_count > 0) 판정은 자동 재적용 금지** — 이후 변경은 수동
  교정(MANUAL)만. positive/negative 구분 없이 대칭 적용한다: negative만 열어두면
  노이즈 편입이 정착 규칙에 걸려 고착되는 상향 래칫이 생긴다.
- 캡션 0건 판정은 캡션이 생겼을 때 1회 업그레이드 재판정(뷰티 축
  `findCaptionRejudgeTargets`와 동일 철학). "이번 캡션 수 > 0" 조건이 없으면 0건 →
  0건 재판정이 같은 품질의 판정만 반복해서 덮는다.
- 현재 업그레이드 대기: F&B positive 기준 306명(fnb_caption_count=0). `fnb_caption_count`
  는 전 판정분에 NULL 없이 기록돼 있어(08-27 운영 실측 40,790건) 추가 백필 불요.
- F&B 백필 경로(fnbOnly)는 fnb_class NULL이므로 가드를 그대로 통과한다 — 동작 불변.

### 2. 비뷰티 재판정 쿨다운 — `findRejudgeTargets`

선정 조건에 `beauty_judged_at < :cooldownBefore`(now − 쿨다운 일수)를 추가한다.

- 목적: 수집 루프로 프로필이 매 주기 갱신되는 F&B 계정이 **매일** 재선정돼 LLM 호출을
  낭비하는 것을 차단(08-27 실측: 하루 대기 1,451명). 비뷰티→뷰티 뒤집힘 기회는 계정당
  월 1회로 유지된다.
- 쿨다운 값은 `app_setting` 키 **`beauty.rejudge-cooldown-days`**(기본 30) —
  `SettingsService.effective()` 패턴. 기본값은 crawler 숫자 설정 관례대로 yml
  `@ConfigurationProperties`(`BeautyProperties`)에 두고 app_setting은 오버라이드만
  담당한다(Flyway 시드는 뷰가 직접 읽는 키 전용 패턴이라 여기선 불필요 — 마이그레이션 없음).
- `findCaptionRejudgeTargets`는 재판정 시 judged_at 갱신으로 조건이 닫히는 자기
  종결형이라 쿨다운을 붙이지 않는다.

### 3. 크론은 매일 유지

beauty 크론(03:03 KST, rejudge=true)은 그대로 둔다. 신규 QUALIFIED 판정(하루 0~15명
실측)은 수집 편입을 게이트하므로 늦추지 않고, 재판정 물량은 1·2가 구조적으로 줄인다 —
할 일이 없으면 대상 0명으로 사실상 무료다.

### 반영 전 마지막 재판정 런에 대해

이 설계가 배포되기 전에 도는 재판정 런(08-27 밤 대기 1,450명 포함)은 막지 않는다 —
백필 잔여의 실측 정착이라 의도된 과정이다. 배포 후에는 캡션 0건 잔여분만 업그레이드
재판정되고 명단이 고정된다.

## 영향 범위

- 변경 파일: `BeautyJob`(적용 가드), `InfluencerRepository.findRejudgeTargets`(쿨다운
  파라미터), `SettingsService`(+키), `BeautyProperties`(+기본값)·yml. 스키마 변경·마이그레이션 없음.
- 뷰티 축 판정 로직·MANUAL 보호·대시보드 쿼리 불변.
- 기대 효과: 대시보드 ③-2 F&B 수치가 정착 후 고정적(306명 업그레이드 재판정분만 등락),
  일일 재판정 LLM 호출 ~1,450명분 → 쿨다운 만료분으로 감소.

## 테스트

`BeautyJob` 통합 테스트에 추가:

1. 캡션 기반(count>0) 기존 F&B 판정은 재판정 응답이 와도 덮이지 않는다.
2. 캡션 0건 판정 + 이번 캡션 > 0 → 업그레이드 적용, fnb_caption_count 갱신.
3. 캡션 0건 판정 + 이번에도 캡션 0 → 미적용(같은 품질 반복 방지).
4. fnb_class NULL 첫 판정은 캡션 0건이어도 적용(백필·신규 경로 회귀 방지).
5. F&B MANUAL 보호 회귀(기존 테스트 유지).
6. 쿨다운: beauty_judged_at이 쿨다운 이내면 재선정 제외, 초과 + 새 스냅샷이면 선정.
