-- 분류 3단계: 대분류(category) > 중분류(subcategory) > 소분류(keyword = 해시태그 검색어).
-- 기존 행은 키워드 자신을 중분류로 백필.
ALTER TABLE category_keyword ADD COLUMN subcategory text;
UPDATE category_keyword SET subcategory = keyword WHERE subcategory IS NULL;
ALTER TABLE category_keyword ALTER COLUMN subcategory SET NOT NULL;

ALTER TABLE content ADD COLUMN subcategory text;
UPDATE content SET subcategory = discovery_keyword WHERE subcategory IS NULL;
ALTER TABLE content ALTER COLUMN subcategory SET NOT NULL;
