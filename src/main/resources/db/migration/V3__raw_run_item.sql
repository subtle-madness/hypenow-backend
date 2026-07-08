CREATE TABLE raw_run_item (
    id           bigserial PRIMARY KEY,
    crawl_run_id bigint  NOT NULL REFERENCES crawl_run(id),
    item_index   integer NOT NULL,
    payload      jsonb   NOT NULL
);
CREATE INDEX idx_raw_run_item_run ON raw_run_item(crawl_run_id);
