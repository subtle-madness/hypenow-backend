# PP — 경쟁사 계정 타입(accountType)

- **상태**: 🔵 구현 완료 — PR 미개설(브랜치 `feature/competitor-monitoring-api-spec-b730b6`)
- **설계 문서**: [specs/2026-08-12-competitor-account-type-design.md](../superpowers/specs/2026-08-12-competitor-account-type-design.md)
- **FE 회신**: 채팅으로 전달(08-12) — 문서로 남기지 않았다
- **의존**: 브랜드 모니터링 was 표면(08-07 다계정 개정 — `app.brand_monitorings`)

## 목표

경쟁사 모니터링이 FE에서 별도 메뉴로 분리됐는데 경쟁사 여부가 브라우저 `localStorage`에만
있어 기기를 바꾸면 사라졌다. 이 분류를 서버로 옮긴다. 브랜드 구독(`app.brand_monitorings`)에
`account_type`(own/competitor)을 추가하고, 계정 API 응답·타입별 상한·PATCH 타입 변경·성과
대시보드 `/contents` 필터와 `comparison` 필드·캠페인 연결 서버 방어까지 관통시켰다.

**타입은 계정이 아니라 유저-계정 관계의 속성**이라 관계 테이블에만 저장한다 — 같은 인스타
계정이 유저마다 다른 타입일 수 있고(A사 담당자에겐 own, B사 담당자에겐 competitor),
전역 `brand_account`에 두면 한 유저의 지정이 다른 유저 화면을 바꾼다. 수집은 브랜드당
전역 1회를 유지한다(크롤링 대상 테이블은 건드리지 않았다).

## 범위

- **스키마**: `V20260811164500__brand_monitorings_account_type.sql`(was `app` 스키마) —
  `account_type` 컬럼 + CHECK 제약. `DEFAULT 'own'`이라 기존 행 백필용 UPDATE가 없다.
  순수 확장이라 expand-contract 상 파괴적 변경 없음.
- **값 공간 단일 정의**: `BrandAccountType` — 값(`OWN`/`COMPETITOR`)·타입별 상한(own 6 /
  competitor 3)·상한 초과 에러 코드·메시지를 한 곳에서 낸다.
- **상한 강제**: 세 쓰기 경로(`link`, `precheck`의 타입 변경, `changeType`) 전부 기존
  `users` 행 `FOR UPDATE` 직렬화 아래에서 타입별로 센다. 초과는 409 —
  own `BRAND_ACCOUNT_LIMIT_REACHED`(기존 유지) / competitor `COMPETITOR_ACCOUNT_LIMIT_REACHED`(신설).
- **표면**: 계정 응답에 `accountType` 추가, `PATCH /v1/brand-monitoring/accounts/{id}` 신설,
  POST 재등록도 다른 타입이면 재수집 없이 타입만 옮긴다.
  `meta`는 `total`·`limit`(호환 키, 값 9)·`limits {own,competitor}`·`counts {own,competitor}`.
- **성과 대시보드**: `/v1/performance-dashboard/contents`에 `accountType` 필터 —
  미지정·`own`은 경쟁사를 빼되 브랜드 미귀속 개인 추적은 남기고, `competitor`는 경쟁사만,
  `all`은 전부, 그 외 값은 400. **분류 필터라 `meta.statusCounts`에도 같이 적용된다.**
  `comparison`은 계정별 `accountType`을 내보내되 **요청 파라미터는 의도적으로 두지 않았다**
  (비교 화면은 활성 링크 전량 순회가 계약).
- **귀속 규칙**: own 구독 귀속이 competitor 귀속을 이긴다 — tagged 병합 순서, direct 대
  tagged 동률 해소, 캠페인 조회 세 지점에 관통.
- **캠페인 방어**: 경쟁사 게시물의 캠페인 연결은 건별 실패(`COMPETITOR_CONTENT_NOT_ALLOWED`),
  경쟁사 구독 브랜드의 직접 등록은 원천에서 403(`COMPETITOR_ACCOUNT_NOT_ALLOWED`)으로 거절.

## 요청서와 다르게 간 지점

FE 요청서(08-11)와 다르게 결정한 4개는 설계 문서 §요청서와 다른 점에 근거를 남겼다 —
① 한도 초과는 400이 아니라 409 유지(POST가 이미 409를 내리고 FE가 그 코드로 분기 중이라,
같은 사건이 경로마다 다른 상태가 되는 것을 피했다) ② 캠페인 방어는 요청 전체 400이 아니라
건별 실패(그 엔드포인트는 100건 배치의 부분 성공이 계약) ③ `/contents` 기본 범위에
individual(브랜드 미귀속 개인 추적) 포함 ④ `meta.limit` 값 변경.

## 남은 후속(의도적 이월)

리뷰에서 드러났으나 이번 범위 밖으로 미룬 것 2개다.

1. **own → competitor 전환 후에도 살아남는 직접 등록 매핑.** 직접 등록을 마친 링크를
   나중에 competitor로 뒤집으면 기존 `app.brand_direct_posts` 매핑과 그 추적 아이템이
   그대로 남고, `V2CampaignContentService.add()`의 첫 갈래(이미 아이템이 있으면 캠페인만
   연결)로 여전히 캠페인에 붙는다. 단순 잔존 데이터보다 범위가 넓다 —
   `V1BrandDirectPostService.get()`의 지연 share-link 매핑에는 타입 검사가 없어서
   (타입 검사는 `register()`의 `requireOwnership`에만 있다), 전환 뒤 폴링만 해도 **새**
   경쟁사 매핑이 생길 수 있다.

   도달 경로를 "본인이 자초하는 3단계"로 적었던 초기 서술은 **틀렸다**(08-12 최종 리뷰에서
   정정). 두 가지로 그렇다. 첫째, 그 3단계(own으로 연결 → 직접 등록 → PATCH로 competitor
   전환)는 예외적 조작이 아니라 **이번 릴리스가 의도한 롤아웃 그 자체**다 — 기존 연결은 전량
   `own`으로 백필되고 유저는 배포 후 경쟁사를 다시 지정한다(마이그레이션 주석·FE 안내).
   직접 등록 게시물이 이미 있는 브랜드는 배포 첫날 그대로 이 상황에 들어간다. 둘째,
   `V1BrandDirectPostService.resolveLazyMappingBrand`는 형제 매핑이 없으면 **활성 링크가 하나일
   때 타입 검사 없이 `links.get(0).brandId()`로 폴백**한다 — 유일한 링크가 competitor인 유저는
   전환 이력이 전혀 없어도 share-link 등록을 폴링하는 것만으로 **새** 경쟁사 매핑을 만든다.

   그럼에도 이월하는 근거는 도달 난이도가 아니라 **결함의 성격**이다: 남의 데이터가 새는 노출
   결함이 아니라 두 화면이 같은 콘텐츠를 두고 다른 말을 하는 정합 결함이고, 제대로 고치려면
   지연 매핑·기존 매핑·캠페인 첫 갈래 세 지점의 타입 판정을 함께 손대야 해서 이번 범위를
   넘는다. 반대로 **도달은 흔하므로 후속의 우선순위는 낮지 않다**.
2. **`ownFirst` 구현이 둘로 갈려 있다.** "own 귀속이 competitor 귀속을 이긴다"는 규칙이
   지금 세 지점(`PerformanceContentAssembler`의 tagged 병합 순서와 direct 대 tagged 동률
   해소, `V2CampaignContentService`의 캠페인 조회)에 적용되는데, `ownFirst` 구현체는 두
   벌이고 둘을 잇는 것은 javadoc 상호 참조뿐이다. `BrandAccountType.isCompetitor(String)`
   술어를 하나 두면 세 호출부가 한 정의를 공유한다.

## 검증

- `./gradlew :was:test` — 120 클래스 1188 테스트 전량 통과(실패·에러·스킵 0, 1분 1초).
- `.github/scripts/check-migration-safety.sh` — `--versions-tree`, `origin/develop` 대조 모두 OK.
- `./gradlew build -x test` — BUILD SUCCESSFUL(4개 모듈).
