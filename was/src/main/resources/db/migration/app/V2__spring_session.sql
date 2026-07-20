-- Spring Session JDBC 표준 스키마(spring-session-jdbc 4.1.0 jar의 schema-postgresql.sql)의 app 스키마 사본.
-- initialize-schema=never — 이 파일이 유일한 DDL 원천. 테이블명은 spring.session.jdbc.table-name과 일치.
CREATE TABLE app.spring_session (
    primary_id            CHAR(36) NOT NULL,
    session_id            CHAR(36) NOT NULL,
    creation_time         BIGINT NOT NULL,
    last_access_time      BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time           BIGINT NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON app.spring_session (session_id);
CREATE INDEX spring_session_ix2 ON app.spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON app.spring_session (principal_name);

CREATE TABLE app.spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES app.spring_session (primary_id) ON DELETE CASCADE
);
