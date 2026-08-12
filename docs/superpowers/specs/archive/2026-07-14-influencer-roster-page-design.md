# 인플루언서 명단 페이지 설계

> 상태: ✅ 구현/반영됨 — 크롤러 어드민 명단 페이지(`/ui/influencers`)로 구현, 현재도 사용 중

날짜: 2026-07-14
상태: 승인됨

## 배경

관리 UI의 "수집 데이터" 메뉴는 게시물(Content) 목록이다. 파이프라인의 실제 산출물은
"발굴되고 판정까지 끝난 인플루언서"이므로, 모니터링 첫 화면급 목록도 인플루언서 명단이어야 한다.
게시물 목록은 여전히 유용하므로(수집 원형 열람) 별도 메뉴로 유지한다.

## 결정 사항

- **명단 범위**: 판정 완료 전체 — `status IN (QUALIFIED, EXCLUDED)`. DISCOVERED(판정 전)는 제외.
  QUALIFIED / EXCLUDED 체크박스 필터로 좁힐 수 있고, 체크 없음 = 둘 다.
- **게시물 목록**: `/ui/contents` URL 그대로 별도 메뉴("수집 게시물")로 유지 — 상세 링크·북마크 호환.
- **네비**: 모니터링 섹션 = 대시보드 → 인플루언서(신규 `/ui/influencers`) → 수집 게시물(기존).

## 구성

- `UiController`에 `GET /ui/influencers` 추가. 페이지당 50, id 내림차순 — 게시물 목록과 동일 패턴.
- `InfluencerRepository.findByStatusIn(statuses, pageable)` (Page) 추가.
- `InfluencerDiscoveryRepository`에 "인플루언서별 최초 발굴(키워드·발굴일)" 프로젝션 쿼리 추가.
  발굴 이력은 append-only이므로 인플루언서별 `min(id)` 행이 최초 발굴이다.
  현재 페이지의 인플루언서 id들로만 조회해 붙인다(전건 조인 없음).
- 템플릿 `influencers.html` — contents.html과 같은 스타일.
  컬럼: id · username · 상태 · 팔로워 · 발굴 키워드 · 발굴일 · 최근 수집.
  배지 CSS는 기존 `.badge.QUALIFIED` / `.badge.EXCLUDED` 재사용.
- 쿼리 파라미터로 판정 외 상태(DISCOVERED)가 들어오면 무시한다 — 명단 범위 불변식 유지.

## 테스트

- UiSmokeTest: 명단 렌더(QUALIFIED·EXCLUDED 표시 + 최초 발굴 키워드 표시, DISCOVERED 미표시),
  상태 필터 동작(QUALIFIED만 체크 시 EXCLUDED 미표시).
