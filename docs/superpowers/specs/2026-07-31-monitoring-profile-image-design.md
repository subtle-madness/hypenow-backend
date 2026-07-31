# 모니터링 인플루언서 프로필 이미지 — 만료 대응·업스트림 검증 설계

> 상태: 🟢 활성
> 작성: 2026-07-31 · 트랙: KK · 결함 출처: 07-31 모니터링 QA(§6-4)

## 1. 문제

07-31 QA 실측으로 모니터링 영역 프로필 이미지 결함 2건이 확인됐다.

### 결함 ① — 종료된 항목의 이미지가 영구히 깨진다

`TargetRepository.findActive()`가 `status IN ('WATCHING','TRACKING')`만 조회하므로
CANCELED·EXPIRED로 전이된 target은 스윕 대상에서 영구 제외되고, 그 순간부터
`profile_meta.profile_image_url`이 다시는 갱신되지 않는다. 인스타 CDN URL은 `oe=`
쿼리파라미터 기준 **약 4일 뒤 만료**(403)되므로, 종료 후 마지막 성공 스윕으로부터 4일이
지나면 그 항목의 프로필 이미지는 재생성 경로 없이 영구히 깨진 링크가 된다.

등록 목록은 종료돼도 삭제되지 않으므로 유저는 끝난 캠페인을 계속 열어본다 — 실사용에서 만난다.

### 결함 ② — 업스트림 값을 검증 없이 저장·서빙한다

`ProfileMetaRepository`가 Hiker 응답의 `profile_pic_url`을 스킴 검증 없이 저장하고,
was `TrackingItemAssembler`가 그 값을 raw로 응답에 싣는다. test DB에서 Hiker가 리터럴
문자열 `"exception://"`(무효 URL)을 보낸 사례를 실측 확인했다(`cherish__sy`, 19건 중 1건).
`curl "exception://"` → `URL rejected: No host part`로 즉시 거부되는, 애초에 유효하지 않은 값이다.

## 2. 실측 근거 — 기존 아카이브 파이프라인 재사용 가능성

"태스크 J 서빙 이미지 아카이브"(07-21, PR #98)가 이미 존재하고 결함 ①과 존재 이유가 같다
(`ImageArchiveJob` → `ParImageStore` → OCI `hypenow-images` → was `COALESCE('/img/' || ip.object_path, ...)`).
그러나 **후보군이 원리적으로 맞지 않는다.**

test monitoring DB `profile_meta` 19건 기준 정량 확인:

| 비교 대상 | 겹침 |
|---|---|
| analysis DB `image_assets` (kind='profile') | **0 / 19** (test는 아카이브 잡 미실행, 테이블 0행) |
| analysis DB `accounts` 미러 | **10 / 19 (53%)** |

운영 monitoring DB는 `target` 0건 — 아직 실사용 유입이 없다(운영 `image_assets`는
profile 7,116 / thumbnail 70,535건으로 정상 가동 중).

`ImageArchiveJob`의 프로필 대상은 `v_accounts`(크롤러가 뷰티 인플루언서로 판정·분석 중인 계정)
뿐인데, 모니터링은 **유저가 임의의 IG 계정을 등록해 추적**하는 기능이라 두 집합이 겹칠 구조적
이유가 없다. 실측 53%는 test 표본(19건)이라 일반화할 수 없으나, **원리적으로 전수 커버가
불가능하다**는 점은 표본 크기와 무관하다.

→ 기존 잡의 후보군 확장으로는 결함 ①을 해소할 수 없다.

## 3. 결정

### 3-1. 결함 ① — monitoring 모듈이 자기 프로필 이미지를 자기 DB 기준으로 아카이브한다

**핵심 구조**: 아카이브 대상은 `target`이 아니라 **`profile_meta` 전체**다.
`profile_meta`는 `username` PK로 target 상태와 무관하게 영구 존속하므로,
`findActive()`를 전혀 건드리지 않고 종료 항목이 자동으로 커버된다.
종료된 항목은 **마지막으로 관측된 프로필 이미지가 우리 저장소에 영구 보존**된다.

```
DailySweepJob 종료
  └→ ProfileImageArchiveJob (monitoring 소유)
       profile_meta 스캔 → source_name(원본 URL 파일명) 변경분만
       → ImageDownloader(JDK HttpClient) → ParImageStore(OCI PUT)
       → UPDATE profile_meta SET image_object_path / image_source_name / image_archived_at

was: image_object_path 있으면 '/img/' || path, 없으면 원본 CDN 폴백
     (monitoring DB 단일 조회 — 크로스 DB 조인 없음)
```

**검토했으나 기각한 대안:**

| 대안 | 기각 사유 |
|---|---|
| analytics `ImageArchiveJob`이 monitoring DB를 읽도록 확장 | 시스템 경계 위반(analytics는 raw 읽기 + 분석 결과 쓰기). §2의 후보군 문제도 그대로 |
| 종료 항목도 주기적으로 Hiker 재조회 | 종료 캠페인이 누적될수록 Hiker 콜이 무한 증가하고, 여전히 CDN 만료에 종속 |
| 만료 추정 시 was가 null 서빙(플레이스홀더) | 10줄로 끝나지만 이미지가 복원되지 않는다 — 종료된 캠페인의 프로필 사진은 영구히 안 보임 |

**세부 결정:**

- **스키마**: `profile_meta`에 nullable 3컬럼 추가 — `image_object_path`, `image_source_name`,
  `image_archived_at`. additive라 expand-contract의 expand 단계로 안전(롤링 중 구 코드는 무시).
- **키 스킴**: `monitor-profile/<username>.jpg`. analytics의 `profile/<handle>.jpg`와 프리픽스를
  분리해 같은 버킷 안에서 소유권이 충돌하지 않게 한다(핸들이 겹쳐도 서로 덮어쓰지 않음).
  프론트 `next.config.ts` rewrite는 `/img/:path*` 글롭이라 **프론트 변경 불필요**.
- **재다운로드 판정**: 원본 URL의 경로 마지막 세그먼트(파일명)를 `image_source_name`과 비교.
  쿼리스트링(`oe=`·서명)은 매 조회마다 바뀌므로 반드시 제외한다 — 안 그러면 매일 전량
  재다운로드한다. analytics `ImageArchiveJob.profileChanged`와 같은 관용구.
- **실행 시점**: 별도 크론이 아니라 **스윕 종료 직후 이어서**. 갓 갱신된 신선한 URL을 잡고,
  크론 설정이 한 곳으로 통제되며(K 원칙상 test는 스윕 크론이 꺼져 있다), 수동 스윕
  트리거(#260)로 검증이 가능하다. 스윕 성공/실패와 무관하게 마지막 단계로 실행한다.
- **PAR 미설정 시**: 잡이 로그만 남기고 no-op(기동 실패가 아니다). test 환경
  (`ANALYTICS_IMAGE_PAR_URL=""`)은 현행 동작 그대로 원본 CDN 폴백으로 서빙된다.
- **실패 처리**: 건 단위 catch-log-continue. 한 계정의 다운로드 실패가 나머지를 막지 않는다.
- **클래스 복제**: `ImageDownloader`/`ImageStore`/`ParImageStore`를 `com.celfit.monitoring.image`로
  복제한다(~90줄, 순수 JDK, Spring 무의존). 계약 모듈 `contract-analysis`는 분석 결과의
  record·enum 전용이라 HTTP 어댑터를 넣을 수 없고, 모듈 간 import는 §4-4가 금지한다.
  90줄 중복이 새 공유 모듈보다 원칙에 맞다.
- **배포**: `deploy/compose.yaml`의 monitoring 서비스에
  `MONITORING_IMAGE_PAR_URL: ${ANALYTICS_IMAGE_PAR_URL}`(같은 버킷·같은 쓰기 PAR 재사용),
  `compose.test.yaml`은 `""`.

### 3-2. 결함 ② — 스킴 검증은 저장·서빙 양쪽에서

- **저장 측(monitoring)**: 정규화를 `ProfileMetaRepository`의 두 upsert **진입점**에 둔다.
  호출자 3곳(`SnapshotWriter`)에 각각 넣으면 향후 호출자 추가 시 누락된다.
  `http://`/`https://`로 시작하지 않으면 null로 강등.
- **계정 모드 upsert 시맨틱 통일**: 현재 `upsert`는 `profile_image_url = EXCLUDED.profile_image_url`
  덮어쓰기라, 정규화만 하면 `exception://`이 올 때 기존 유효값이 NULL로 날아간다.
  POST 모드 `upsertOwnerFromPost`가 이미 쓰는 `COALESCE(EXCLUDED, 기존)` 보존 시맨틱으로
  **`profile_image_url` 한 컬럼만** 통일한다(`display_name`·`last_uploaded_at`은 현행 유지).
  업스트림이 일시적으로 쓰레기를 줘도 마지막 유효 이미지가 남는다.
- **서빙 측(was)**: `TrackingItemAssembler`에 얇은 가드. 저장 측이 막아도 이미 DB에 박힌
  값이 있으므로 이중 방어.
- **기존 오염행**: monitoring Flyway 신규 마이그레이션(UTC 채번)으로
  `profile_image_url !~ '^https?://'` 행을 NULL 정정.

## 4. 검증

**monitoring** — fake `ImageStore`/`ImageDownloader`로:
1. 신규 아카이브(object_path·source_name·archived_at 기록)
2. source_name 미변경 시 재다운로드 스킵
3. 한 건 실패가 나머지를 막지 않음
4. PAR 미설정 시 no-op
5. `ProfileMetaRepository` 스킴 정규화(무효값 → null, 기존 유효값 보존)

**was** — `TrackingItemAssembler`:
1. `image_object_path` 있으면 `/img/...` 서빙
2. 없으면 원본 CDN 폴백
3. 무효 스킴이면 null

현재 `TrackingItemAssemblerTest`는 `profile_meta`를 아예 시드하지 않아 `profileImageUrl`이
항상 null로 지나가고 있다 — 시드 추가가 선행되어야 한다.

## 5. PR 분리

- **PR-1** (결함 ②): 스킴 검증 + 오염행 정정. 작고 라이브 영향이 있어 먼저 머지한다.
- **PR-2** (결함 ①): 아카이브 — 스키마·잡·was 서빙·배포 env. PR-1 머지 후 develop 기준으로 뗀다.
