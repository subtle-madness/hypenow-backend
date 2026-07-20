-- 콘텐츠 1:1 분석 결과 (분석 시점 고정·불변 — 스펙 §2). 미러 테이블과 FK 없음(논리 참조).
-- 기준선 스냅샷은 "AI 텍스트가 참조한 수치"라 같은 행에 고정한다.
CREATE TABLE content_analyses (
    short_code                   text PRIMARY KEY,
    analyzed_at                  timestamptz NOT NULL DEFAULT now(),
    model                        text NOT NULL,

    -- LLM 텍스트
    ai_content_summary           text,
    contents_pattern             text,
    ai_comment_insight           text,

    -- 기준선 스냅샷 (비LLM — 분석 시점 고정)
    recent_reels_avg_views       bigint,
    rank_in_recent_reels         smallint,
    recent_reels_count           smallint,
    recent_contents_count        smallint,
    recent12_avg_engagement_rate numeric,
    recent12_avg_like_count      bigint,
    recent12_avg_comment_count   bigint,
    category_top_percentile      smallint,
    category_avg_views           bigint,
    category_sample_size         bigint,

    -- VLM 산출물 (F-2 검증 전 NULL 허용)
    detected_brands              jsonb,
    sponsored_signal_level       text CHECK (sponsored_signal_level IN ('high','mid','low')),
    sponsored_signal_reasons     jsonb,
    ad_disclosure                text,
    detected_product_categories  jsonb,
    vlm_attributes               jsonb,
    main_category                text,
    sub_categories               jsonb,
    ad_type                      text CHECK (ad_type IN ('organic','sponsored')),

    -- 댓글 종합 판정
    comment_authenticity_grade   text CHECK (comment_authenticity_grade IN ('high','normal','suspect')),
    comment_authenticity_note    text
);
