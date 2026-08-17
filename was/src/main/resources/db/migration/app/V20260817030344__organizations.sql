-- 조직·엔터프라이즈 entitlement: 기업 단위 계약 모델 (스펙: docs/superpowers/specs/2026-08-17-org-entitlements-design.md)
CREATE TABLE app.organizations (
    id             bigserial PRIMARY KEY,
    name           text NOT NULL,
    plan           text NOT NULL DEFAULT 'FREE' CHECK (plan IN ('FREE', 'ENTERPRISE')),
    contract_start date,
    contract_end   date,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.organization_members (
    org_id     bigint NOT NULL REFERENCES app.organizations(id),
    user_id    bigint NOT NULL UNIQUE REFERENCES app.users(id) ON DELETE CASCADE,
    org_role   text NOT NULL DEFAULT 'MEMBER' CHECK (org_role IN ('MEMBER', 'ORG_ADMIN')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);

CREATE TABLE app.organization_feature_overrides (
    org_id      bigint NOT NULL REFERENCES app.organizations(id),
    feature_key text NOT NULL,
    enabled     boolean NOT NULL,
    value       jsonb,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, feature_key)
);
