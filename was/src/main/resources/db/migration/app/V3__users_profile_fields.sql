-- 스펙 6.12/6.15 프로필·동의 필드. 기존 행(dev 테스트 계정뿐)은 기본값 백필.
ALTER TABLE app.users
    ADD COLUMN name                text        NOT NULL DEFAULT '',
    ADD COLUMN nickname            text,
    ADD COLUMN user_type           text        NOT NULL DEFAULT 'brand'
        CHECK (user_type IN ('brand', 'agency', 'distributor', 'influencer')),
    ADD COLUMN signup_route        text        NOT NULL DEFAULT 'other'
        CHECK (signup_route IN ('portal_search','blog_community','pr_article','social_media','offline_event','referral','other')),
    ADD COLUMN phone_country_code  text        NOT NULL DEFAULT '+82'
        CHECK (phone_country_code IN ('+82','+1','+81','+86')),
    ADD COLUMN phone_number        text        NOT NULL DEFAULT '',
    ADD COLUMN company_name        text        NOT NULL DEFAULT '',
    ADD COLUMN company_size        text        NOT NULL DEFAULT '2-10'
        CHECK (company_size IN ('2-10','11-50','51-200','201-500','501-1000','1001+')),
    ADD COLUMN industry            text        NOT NULL DEFAULT 'beauty'
        CHECK (industry IN ('fashion','beauty','fnb','home_living','baby_kids')),
    ADD COLUMN job_title           text        NOT NULL DEFAULT 'other'
        CHECK (job_title IN ('representative','executive','team_lead','staff','other')),
    ADD COLUMN agreed_terms        boolean     NOT NULL DEFAULT true,
    ADD COLUMN agreed_privacy      boolean     NOT NULL DEFAULT true,
    ADD COLUMN agreed_age14        boolean     NOT NULL DEFAULT true,
    ADD COLUMN agreed_marketing    boolean     NOT NULL DEFAULT false,
    ADD COLUMN marketing_updated_at timestamptz,
    ADD COLUMN profile_image_url   text;
