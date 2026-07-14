-- 계정 LLM 카피 이력 (스펙 §3). content_analyses(불변 1회)와 달리 stale 재분석 — INSERT로만 쌓고
-- was/E는 계정별 최신 1행(analyzed_at DESC)을 읽는다. 미러 테이블과 FK 없음(논리 참조).
-- 컬럼 이름·순서 = AccountAnalysis record (FlywaySchemaTest 대조). PK가 최신 조회 인덱스를 겸한다.
CREATE TABLE account_analyses (
    handle               text NOT NULL,
    analyzed_at          timestamptz NOT NULL,
    model                text NOT NULL,
    input_last_posted_at timestamptz,
    input_analyzed_count bigint,
    tagline              text,
    summary              text,
    trend_note           text,
    chart_note           text,
    traits               jsonb,
    ad_headline          text,
    pace_note            text,
    PRIMARY KEY (handle, analyzed_at)
);
