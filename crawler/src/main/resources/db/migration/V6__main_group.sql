-- 분류 4단계: 카테고리(category) > 대분류(main_group) > 중분류(subcategory) > 소분류(keyword).
-- 기존 행은 중분류 값을 대분류로 백필.
ALTER TABLE category_keyword ADD COLUMN main_group text;
UPDATE category_keyword SET main_group = subcategory WHERE main_group IS NULL;
ALTER TABLE category_keyword ALTER COLUMN main_group SET NOT NULL;

ALTER TABLE content ADD COLUMN main_group text;
UPDATE content SET main_group = subcategory WHERE main_group IS NULL;
ALTER TABLE content ALTER COLUMN main_group SET NOT NULL;
