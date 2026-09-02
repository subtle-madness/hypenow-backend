# 브랜드 연결(유저)별 표시 기간 설계 — 링크 레벨 collection_months

> 상태: 🟢 활성 · ✅ 구현됨 (2026-08-17)

## 배경

브랜드 계정 수집 범위(collectionMonths, 1|3|6|12)는 2026-08-12 설계에서 **자산 레벨 max**로
확정됐다 — `monitoring brand_account.collection_months` 하나로 관리하고 절대 줄지 않으며,
응답도 자산 값을 그대로 실었다("3개월 유저가 12개월치를 보는 쪽은 무해").

실사용에서 이 전제가 깨졌다: cclime.beauty처럼 **여러 유저가 등록한 브랜드**는 자산이 12라,
3개월을 신청한 유저도 12개월치 게시물 전량(실측 463건)을 그대로 받는다. 유저가 신청한
기간은 monitoring 자산에 max로 흡수될 뿐 **어디에도 저장되지 않아** 표시를 자를 근거
자체가 없었다.

이번 설계는 08-12 결정의 "구독 레벨 별도 관리 기각"을 뒤집되, 크롤 구조는 건드리지 않는다
— 자산 창(크롤 범위, 유저 간 max)은 그대로 두고, 그 위에 **유저-브랜드 링크 레벨 표시
창**을 얹는다.

## 결정 요약

| 항목 | 결정 |
|---|---|
| 신청값 정본 | `app.brand_monitorings.collection_months` 신설 — 유저가 등록 시 신청한 표시 기간 |
| 크롤 자산 | 변경 없음 — `brand_account.collection_months`는 여전히 유저 간 max, 축소 없음 |
| 값 공간 | 1 \| 3 \| 6 \| 12 (CHECK 제약, was 검증은 기존 `BrandCollectionMonths` 재사용) |
| 기존 행 백필 | `DEFAULT 12`(현행 표시 유지) + **cclime 3개월 신청 유저 연결만 운영 수동 UPDATE** — 신청값이 영속화된 적이 없어 자동 복원 불가 |
| 재등록(재-POST) | `collectionMonths` 명시 시 **요청 값 그대로 설정(축소 허용)**, 생략(null) 시 링크 값 불변 |
| 표시 창 적용 표면 | 게시물 목록(`/accounts/{id}/posts`) + `meta.counts` + 상세(`/posts/{postId}`) |
| direct 게시물 | 창과 무관하게 **항상 포함** — 유저가 URL을 명시 등록한 추적 대상 |
| 계정 응답 collectionMonths | 자산 값 → **링크 값**으로 대체(FE 드롭다운·잠금 정합) |
| 범위 밖 | hashtag-posts, 성과 대시보드(comparison covered는 자산 창 유지), monitoring 크롤 로직 전부 |

## 1. 데이터 모델 — app `brand_monitorings`

UTC 타임스탬프 채번 마이그레이션 1개(신규 컬럼 + DEFAULT뿐이라 expand-contract 안전):

```sql
ALTER TABLE app.brand_monitorings
    ADD COLUMN collection_months int NOT NULL DEFAULT 12
    CHECK (collection_months IN (1, 3, 6, 12));
```

- 기존 행은 전부 12 — 현행 표시가 그대로라 데이터가 갑자기 줄어드는 유저가 없다.
- **cclime 보정은 마이그레이션에 넣지 않는다**(운영 특정 유저 데이터를 마이그레이션에
  하드코딩하지 않음). 배포 후 운영 DB에서 1회 수동 UPDATE:

```sql
-- cclime.beauty 브랜드에 3개월을 신청했던 유저의 연결만 보정
UPDATE app.brand_monitorings
   SET collection_months = 3
 WHERE user_id = :userId AND brand_id = :cclimeBrandId AND deleted_at IS NULL;
```

## 2. 쓰기 경로 — `V1BrandAccountService.register`

| 케이스 | 링크 `collection_months` |
|---|---|
| 신규 연결 | 요청 값(생략 시 12 — `BrandCollectionMonths.orDefault`) |
| 재-POST, 값 명시 | 요청 값 그대로(축소 허용 — 링크는 유저 개인 표시 범위) |
| 재-POST, 값 생략(null) | 불변 — 구 클라이언트 재-POST가 12로 되돌리는 사고 방지 |

- `BrandLinkRepository.insertLink`에 months 파라미터 추가.
- 링크 갱신용 `updateCollectionMonths(userId, brandId, months)` 추가 —
  `updateAccountType` 관용구와 동형(활성 행 대상, 반환값은 "행이 있었나").
- **자산 확장 게이트는 현행 그대로**: 요청 > 자산이면 monitoring 등록 API 재호출.
  링크 축소가 자산을 건드리는 경로는 없다.

## 3. 읽기 경로 — 표시 창

### 게시물 목록 `GET /v1/brand-monitoring/accounts/{accountId}/posts`

- `requireOwnership`이 조회한 링크 행을 버리지 않고 반환하도록 변경 → 추가 쿼리 없이
  `collection_months` 확보.
- `assembler.assembleForBrand(...)` 결과(전량)를 **컨트롤러에서** 링크 창으로 자른다:
  - 컷 = `LocalDate.now(KST).minusMonths(linkMonths)` — 기존 `windowCutoff`의 KST 달력일
    관용구와 동형.
  - `source=direct`는 무조건 통과. tagged는 `uploadedOn ≥ 컷`만(uploadedOn null인 tagged는
    제외 — 수집 구조상 거의 없고, 판정 불가 제외는 기존 `withinUploadWindow` 규칙과 일치).
- 잘린 결과가 새 "전량" — `meta.counts`·필터·정렬 모두 그 위에서 동작한다(탭 뱃지가
  3개월치로 바뀜). 유저 지정 `uploadedFrom/To`는 창과 교집합.
- Assembler는 손대지 않는다 — "전량 반환" 계약 유지(성과 대시보드가 같은 assembler를
  소비하며 그쪽은 자산 창 기준이 맞다).

### 상세 `GET /v1/brand-monitoring/posts/{postId}`

같은 창을 적용 — 창 밖 게시물은 404(목록에 없는 게 상세로 열리는 불일치 방지). 브랜드별
순회 시 각 링크의 months로 판정한다.

### 계정 응답 `collectionMonths`

`BrandAccountAssembler`가 자산 값 대신 **링크 값**을 싣는다 — FE의 "업로드 기간" 안내·
드롭다운 잠금이 유저 신청값과 맞아떨어진다. 자산 값은 확장 게이트 내부용으로만 남는다.
목록·단건 조회 호출부는 링크 행을 이미 갖고 있으므로 파라미터 추가로 전달한다.

## 4. 에러 처리

- 값 공간 검증·400 계약은 현행 그대로(`BrandCollectionMonths.isValid`, 리포지토리 도달 전).
- CHECK 제약은 코드 결함 시 최후 방어(현행 자산 CHECK와 동형).

## 5. 테스트

- `V1BrandAccountsControllerTest`: 응답이 링크 값을 싣는지(기존 "자산 값 그대로" 테스트는
  계약 변경으로 수정), 재-POST 축소 반영, null 생략 시 불변.
- `V1BrandPostsController` 테스트: 3개월 링크가 12개월 자산 데이터를 자르는지(목록·counts),
  direct 포함, 창 밖 상세 404, 창 경계(컷 당일 포함).
- 마이그레이션은 기존 Testcontainers 경로로 자동 검증. 테스트 스키마
  `monitoring-brand-schema.sql`이 아닌 app Flyway 실본이 적용되는 테스트는 그대로 통과해야
  한다(DEFAULT 12).

## 6. FE 영향 (사전 공유 필요)

- 계정 응답 `collectionMonths`가 자산 값 → 링크 값으로 바뀐다(3개월 신청 유저는 12 → 3).
- 게시물 목록·counts가 링크 창으로 줄어든다.
- 요청 계약(등록·재등록 본문)은 불변.
