# is_beauty NULL 스트래글러 복구 + develop/운영 괴리 정리

> 상태: ✅ 태스크 A 실행 완료(2026-07-20) — 968건 DELETE COMMIT + analyze 잡 수동 트리거, 재분석분 is_beauty 정상 채움 확인. 잔여는 새벽 배치 배수 모니터링.

## 0. 한 줄 요약

"서버 게시물 개수가 2,100 → 1,100으로 줄었다"의 원인은 **비뷰티 과탈락도, 캐시/미러 stale도 아니고**,
게시물 단위 `is_beauty` 판정을 못 받은 **미판정(NULL) 968건이 `is_beauty=true` 필터에서 빠진 것**이었다.
조사 도중(운영 analytics 컨테이너가 07-20 12:33 UTC 새 이미지로 재기동) **새 is_beauty 파이프라인이 라이브되며
백로그 대량 재분석이 자동 실행돼 상당 부분 자기치유**됐다. 남은 실제 작업은 **옛 NULL 968 스트래글러 재분석 1건뿐**이다
(is_beauty 기능 자체는 [[mvp-analysis-fix-split]] PR #83로 develop 머지·운영 배포 완료 — 코드 괴리 없음).

## 1. 무슨 일이 있었나 (확정된 근본 원인)

- 서빙/랭킹 경로는 게시물 단위 `content_analyses.is_beauty` 를 필터한다 (`is_beauty=true`만 노출).
  이 코드/컬럼은 PR #83(merge `ed8c286`)로 **develop 머지·운영 배포 완료** — 코드/DB 괴리 없음.
  (조사 초기 "develop에 없다"는 **로컬 develop이 뒤처진 공유 체크아웃** 때문의 오판이었고, `origin/develop`엔 V34·파이프라인·WAS 필터 모두 존재함을 재확인.)
- V34 백필은 `UPDATE content_analyses SET is_beauty=true WHERE main_category IS NOT NULL` —
  **카테고리가 있는 행만 true로 채우고, `main_category IS NULL` 행은 손대지 않아 NULL로 남긴다.**
- 캡션분류 게이트 이전(및 sanitize 드랍)으로 `main_category`가 비어 있던 옛 분석분이 NULL로 남았고,
  `is_beauty=true` 필터가 NULL도 제외하면서 서빙 개수가 반토막 났다.
- 실제 "비뷰티(false)"로 판정된 건 조사 초기 시점엔 **단 1건**뿐이었다 → 과탈락(감지 오류)이 주원인이 아님.

## 2. 조사 중 자동 복구된 사실 (07-20 UTC 기준 실측)

| 시점 | content_analyses 총계 | is_beauty=true | =false | =null |
|---|---|---|---|---|
| 조사 초기 (프리-재기동) | 2,182 | 1,213 | 1 | 968 |
| 12:33 재기동 후 대량 재분석 완료(12:58) | **16,493** | **9,888** | **5,637** | **968** |

- 서빙풀(`contents`, 30,358) 기준 실측(현재): **is_beauty=true 노출 9,886** / false 제외 5,637 /
  NULL 미판정 제외 966 / 아직 미분석 13,869.
- 즉 사용자가 본 "1,100"은 프리-재기동 상태였고, **지금 노출 수는 ~9,886으로 이미 회복·확장**됐다.

### 원 질문에 대한 답 (A vs B)

새 파이프라인이 게시물 단위로 판정한 결과 **비뷰티가 5,637건(분류분의 ~36%)** — 뷰티 인플루언서라도
일상/라이프스타일 글이 상당수라 **"원래 비뷰티가 꽤 많다"(B)가 상당 부분 사실**이다. 감지 과탈락(A)은
주원인이 아니다. 다만 남은 NULL 968 안에는 계정 프록시 추정상 **~2/3(≈618)가 "뷰티 계정인데 라벨 드랍된
오탈락"**, ~1/3(≈350)이 진짜 비뷰티로 섞여 있어, 재분석 시 ~500~650건이 true로 복구될 것으로 추정.

## 3. 남은 작업

### 태스크 A — NULL 968 스트래글러 재분석 (self-heal) 【운영 변경, 승인 필요】

- **스크립트**: `analytics/ops/reprocess_uncategorized_content_analyses.sql` (feat/content-beauty-flag).
  대상 `is_beauty IS NULL AND main_category IS NULL`, DELETE → 데일리 잡이 새 프롬프트로 재자격 재분석.
  `is_beauty=false`는 안 건드림 → **재실행 멱등**. 무료 쿼터(일 ~1,500콜) 내 968건 소화 가능.
- **전제(하드 게이트)**: 새 is_beauty 파이프라인이 운영에서 정상 가동 중 ✅ (12:33 재기동 후 12:00시 버킷
  14,312건 전부 is_beauty 채워짐으로 확인). 미가동 상태면 재분석해도 다시 NULL → COMMIT 금지.
- **절차**:
  1. 스크립트 끝을 `ROLLBACK` 그대로 두고 dry-run — `to_delete` 카운트가 실측 NULL(현재 966~968)과 일치하는지 확인.
  2. 최근 백업 존재 확인(일일 backup cron + 오프사이트 rclone). 필요 시 즉시 스냅샷.
  3. `ROLLBACK`→`COMMIT`으로 바꿔 실행(analysis DB, 운영 postgres).
  4. 이후 1~2회 데일리 잡 사이클 뒤 NULL이 줄고 is_beauty=true/false로 갈렸는지 재확인(§4 체크).
- **주의**: DELETE 후 회복은 즉시가 아니라 데일리 잡 주기를 탄다. 삭제된 옛 요약문은 새 분석으로 대체됨(손실 아님, 백업 존재).

### 태스크 B — 재분류 결과 스팟체크 【읽기 전용】

- 비뷰티 5,637건(~36%)이 타당한지 표본 검증: 계정 단위로 "뷰티 계정인데 전부 false"인 케이스가 있으면
  역유도/프롬프트 재점검. is_beauty=false ∩ main_category IS NOT NULL(모순) 0건 유지 확인.

## 4. 검증 쿼리 (analysis DB, 읽기 전용)

```sql
-- 현재 노출/제외 실측
WITH j AS (SELECT c.short_code, ca.is_beauty
           FROM contents c LEFT JOIN content_analyses ca USING (short_code))
SELECT count(*) FILTER (WHERE is_beauty)               AS shown,      -- 랭킹 노출
       count(*) FILTER (WHERE is_beauty IS FALSE)      AS excl_nonbeauty,
       count(*) FILTER (WHERE is_beauty IS NULL
             AND short_code IN (SELECT short_code FROM content_analyses)) AS excl_null,
       count(*) FILTER (WHERE short_code NOT IN (SELECT short_code FROM content_analyses)) AS unanalyzed
FROM j;

-- NULL 스트래글러 잔여
SELECT count(*) FROM content_analyses WHERE is_beauty IS NULL AND main_category IS NULL;

-- 모순 케이스(0이어야 함)
SELECT count(*) FROM content_analyses WHERE is_beauty IS FALSE AND main_category IS NOT NULL;
```

## 5. 결정 기록 / 반증된 가설

- **스냅샷 캐시/미러 stale 가설 → 반증.** 캐시=라이브=147,398, 미러 `contents`=raw `v_contents`=30,358,
  07-20 미러 신선(updated_at 09:20Z). 감소 원인은 캐시가 아니라 **게시물 단위 is_beauty 미판정(NULL)** 이었다.
- **서버 refresh cron 미배선 추정 → 반증.** 실재함(`25 19 * * *` snapshot-cache-refresh). 정상.
- **"is_beauty가 develop에 없다(괴리)" 추정 → 반증.** 로컬 develop이 뒤처진 공유 체크아웃 때문의 오판.
  `origin/develop`엔 PR #83(ed8c286)로 전부 존재. develop/운영 코드 괴리 **없음** → 별도 정리 태스크 불필요.
- **남은 진짜 원인·작업 = NULL 968 스트래글러 재분석(태스크 A) 뿐.** 파이프라인은 07-20 12:33Z 라이브,
  백로그 자동 재분석으로 서빙 노출 ~1,213 → ~9,886 회복. NULL만 immutable이라 자동 회복 대상에서 빠져 잔존.
