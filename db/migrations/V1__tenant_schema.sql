-- Requirement IDs: IAM-01, ARC-01, ARC-02, OPS-02
-- Tenant-owned records carry organization_id; every foreign key includes it.
CREATE TABLE public.organization (
    organization_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name    text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.user_identity (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    subject         text NOT NULL,
    display_name    text NOT NULL,
    status          text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'deactivated')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, subject)
);

CREATE TABLE public.facility (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    code            text NOT NULL,
    name            text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, code)
);

CREATE TABLE public.membership (
    organization_id uuid NOT NULL,
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL,
    status          text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
    valid_from      timestamptz NOT NULL DEFAULT now(),
    valid_to        timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, id),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    FOREIGN KEY (organization_id, user_id)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE TABLE public.role_assignment (
    organization_id uuid NOT NULL,
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    membership_id   uuid NOT NULL,
    role_key        text NOT NULL,
    granted_by      uuid NOT NULL,
    valid_from      timestamptz NOT NULL DEFAULT now(),
    valid_to        timestamptz,
    PRIMARY KEY (organization_id, id),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    FOREIGN KEY (organization_id, membership_id)
        REFERENCES public.membership(organization_id, id),
    FOREIGN KEY (organization_id, granted_by)
        REFERENCES public.user_identity(organization_id, id)
);

CREATE TABLE public.audit_event (
    organization_id uuid NOT NULL REFERENCES public.organization(organization_id),
    id              uuid NOT NULL DEFAULT gen_random_uuid(),
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    actor_id        uuid,
    action          text NOT NULL,
    resource_type   text,
    resource_id     text,
    purpose         text,
    scope           text,
    correlation_id  text,
    outcome         text NOT NULL,
    seq             bigint,
    previous_hash   bytea,
    row_hash        bytea,
    PRIMARY KEY (organization_id, id),
    UNIQUE (organization_id, seq)
);

CREATE INDEX membership_user_idx       ON public.membership (organization_id, user_id, valid_from DESC);
CREATE INDEX role_assignment_member_idx ON public.role_assignment (organization_id, membership_id);
CREATE INDEX audit_event_occurred_idx   ON public.audit_event (organization_id, occurred_at DESC);
