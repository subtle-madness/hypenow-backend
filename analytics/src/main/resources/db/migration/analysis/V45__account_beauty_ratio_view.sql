-- 발굴 목록 뷰티 게시물 비율 게이트 원시 카운트 (07-30). 계정 단위 뷰티 판정(crawler
-- influencer.beauty_class)이 bio 키워드·자기신고 카테고리에 낚여 육아·다이어트·여행·피트니스
-- 계정을 뷰티 인플루언서로 잘못 서빙하는 사례가 있다(0% 구간 스팟체크 20개 중 오판 17개).
-- 계정 판정 로직은 crawler 트랙(팀원 영역)이라 손대지 않고, 발굴 표면에서 게시물 실측
-- 기반으로 걸러내는 접근이다.
--
-- 이 뷰는 계정별 "창 내(account_content_series) 분석 완료 게시물 수"와 그중 "뷰티 판정
-- 게시물 수"만 원시로 집계한다. 임계값(최소 분석 건수·최소 비율) 같은 정책은 이 뷰에 없다 —
-- was가 조회 시점에 상수로 적용한다(V1InfluencerDiscoveryRepository). 정책을 뷰에 박지
-- 않는 이유는 표면마다(발굴 목록·유사 인플루언서) 문턱이 달라질 수 있어서다.
CREATE VIEW account_beauty_ratio AS
SELECT s.account_handle,
       count(*) FILTER (WHERE an.is_beauty IS NOT NULL) AS analyzed_count,
       count(*) FILTER (WHERE an.is_beauty IS TRUE)     AS beauty_count
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code
GROUP BY s.account_handle;

COMMENT ON VIEW account_beauty_ratio IS
  '계정별 뷰티 게시물 비율 원시 카운트 — 창 내(account_content_series) 분석 완료·뷰티 판정 건수.
   임계값 정책은 여기 없음, was가 조회 시점에 적용(07-30 V45).';
