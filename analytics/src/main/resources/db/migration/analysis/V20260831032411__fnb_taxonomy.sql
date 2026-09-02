-- F&B 어휘 시드 (2026-08-31). 출처: 피처링 콘텐츠 랭킹 필터 트리(app.featuring.co, 08-31 채취).
-- 경쟁 서비스가 시장에서 검증한 분류라 자체 발명보다 낫고, 대분류 slug는 피처링 URL 파라미터
-- 값을 그대로 쓴다(main_category=beverage 등).
--
-- ⚠️ 요리/레시피의 소분류는 피처링 원본이 '음료'인데 중분류 '음료'와 문자열이 같다.
-- sub_categories는 정확 일치 매칭이라 중분류 필터가 이 소분류를 오탐하므로 '음료 레시피'로
-- 분리했다(에스테틱의 '필링 시술' ↔ 클렌징 '필링' 선례와 동일).
--
-- 순수 additive INSERT — expand-contract 안전, 롤링 배포 무해.
INSERT INTO beauty_taxonomy (main_value, main_label, mid_label, sub_label, main_order, mid_order, sub_order, axis) VALUES
  ('beverage','음료','음료','탄산',8,1,1,'fnb'),
  ('beverage','음료','음료','주스',8,1,2,'fnb'),
  ('beverage','음료','음료','기능성음료',8,1,3,'fnb'),
  ('beverage','음료','음료','커피',8,1,4,'fnb'),
  ('beverage','음료','음료','단백질음료',8,1,5,'fnb'),
  ('alcohol','주류','주류','소주',9,1,1,'fnb'),
  ('alcohol','주류','주류','맥주',9,1,2,'fnb'),
  ('convenience','가공/간편식','가공/간편식','즉석식품',10,1,1,'fnb'),
  ('convenience','가공/간편식','가공/간편식','밀키트',10,1,2,'fnb'),
  ('convenience','가공/간편식','가공/간편식','면류',10,1,3,'fnb'),
  ('convenience','가공/간편식','가공/간편식','이유식',10,1,4,'fnb'),
  ('snack','간식류','간식류','과자',11,1,1,'fnb'),
  ('snack','간식류','간식류','초콜릿',11,1,2,'fnb'),
  ('snack','간식류','간식류','아이스크림',11,1,3,'fnb'),
  ('snack','간식류','간식류','젤리',11,1,4,'fnb'),
  ('health-food','건강식품','건강식품','영양제',12,1,1,'fnb'),
  ('health-food','건강식품','건강식품','비타민',12,1,2,'fnb'),
  ('health-food','건강식품','건강식품','유산균',12,1,3,'fnb'),
  ('health-food','건강식품','건강식품','프로틴',12,1,4,'fnb'),
  ('health-food','건강식품','건강식품','다이어트',12,1,5,'fnb'),
  ('health-food','건강식품','건강식품','이너뷰티',12,1,6,'fnb'),
  ('recipe','요리/레시피','요리/레시피','요리',13,1,1,'fnb'),
  ('recipe','요리/레시피','요리/레시피','디저트/베이킹',13,1,2,'fnb'),
  ('recipe','요리/레시피','요리/레시피','음료 레시피',13,1,3,'fnb');

-- F&B 유통 채널. 뷰티(올리브영·다이소)와 축이 다르다 — 프롬프트는 전체를 축 라벨과 함께 싣고
-- sanitize가 축 정합성을 검사한다(설계 §4).
INSERT INTO beauty_distributors (name, sort, slug, axis) VALUES
  ('GS25', 10, 'gs25', 'fnb'),
  ('CU', 11, 'cu', 'fnb'),
  ('세븐일레븐', 12, 'seven-eleven', 'fnb'),
  ('이마트24', 13, 'emart24', 'fnb'),
  ('이마트', 14, 'emart', 'fnb'),
  ('홈플러스', 15, 'homeplus', 'fnb'),
  ('롯데마트', 16, 'lottemart', 'fnb'),
  ('코스트코', 17, 'costco', 'fnb'),
  ('쿠팡', 18, 'coupang', 'fnb'),
  ('마켓컬리', 19, 'kurly', 'fnb'),
  ('네이버쇼핑', 20, 'naver-shopping', 'fnb');
