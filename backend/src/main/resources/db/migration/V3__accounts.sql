-- What signs in. See ADR-011.
--
-- One row per account, and an account is a member of exactly one thing: an organisation, in one
-- role, or the platform itself. The two shapes share a table because they are the same aggregate
-- read the same way -- a sign-in has only a name to go on and does not yet know which kind it is
-- about -- so a second table would be joined on every authentication and answer nothing extra.
-- account_membership_consistent is what keeps the shapes from blurring: an operator's row carries
-- no organisation, no role and no employee, and a tenant's row carries the first two.
--
-- The operator is the one exception to "organisation_id on every aggregate root", like organisation
-- itself: it belongs to no tenant, which is the whole point of it. Every scoped read in the adapter
-- therefore filters on organisation_id and finds no operator, which is the behaviour that makes an
-- operator invisible to a tenant's own administration.
--
-- sign_in_name is unique across the platform, not per organisation: sign-in is given a name and
-- nothing else, so the same name in two tenants would make the account ambiguous exactly when it
-- matters most. It is stored normalised, and the CHECK is what keeps a writer that skipped
-- SignInName from storing a form nobody can look up again.
--
-- employee_id has no foreign key, deliberately. ADR-004 keeps foreign keys inside a bounded
-- context, and employee belongs to workforce; access validates the link through workforce's
-- published port instead. The partial unique index is this context's own rule, not a borrowed one:
-- one account per employee within an organisation.
--
-- No policy number appears here. How long a lock lasts and how many failures cause one are
-- configuration (ADR-011); this table stores only the count and the instant a lock ends.

CREATE TABLE account (
    id                       uuid        PRIMARY KEY,
    sign_in_name             text        NOT NULL
        CONSTRAINT account_sign_in_name_unique UNIQUE
        CONSTRAINT account_sign_in_name_normalised
            CHECK (sign_in_name <> '' AND sign_in_name = lower(btrim(sign_in_name))),
    password_hash            text        NOT NULL
        CONSTRAINT account_password_hash_not_blank CHECK (btrim(password_hash) <> ''),
    kind                     text        NOT NULL
        CONSTRAINT account_kind_known CHECK (kind IN ('TENANT', 'OPERATOR')),
    organisation_id          uuid        REFERENCES organisation (id),
    role                     text
        CONSTRAINT account_role_known CHECK (role IN ('EMPLOYEE', 'SCHEDULER', 'ADMIN')),
    employee_id              uuid,
    active                   boolean     NOT NULL,
    password_change_required boolean     NOT NULL,
    failed_sign_ins          integer     NOT NULL
        CONSTRAINT account_failed_sign_ins_not_negative CHECK (failed_sign_ins >= 0),
    locked_until             timestamptz,
    created_at               timestamptz NOT NULL,
    CONSTRAINT account_membership_consistent CHECK (
        (kind = 'OPERATOR'
            AND organisation_id IS NULL AND role IS NULL AND employee_id IS NULL)
        OR (kind = 'TENANT'
            AND organisation_id IS NOT NULL AND role IS NOT NULL)
    )
);

-- One account per employee, within an organisation. A partial index rather than a constraint
-- because most accounts are linked to nobody, and NULLs would otherwise not be compared at all.
CREATE UNIQUE INDEX account_one_per_employee ON account (organisation_id, employee_id)
    WHERE employee_id IS NOT NULL;

-- Leads with organisation_id like every other index here, because every scoped read starts there.
-- Carries the listing's order so it does not have to be sorted afterwards.
CREATE INDEX account_by_organisation ON account (organisation_id, created_at, id);
