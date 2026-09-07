-- The schema behind the driven ports. See ADR-004.
--
-- Two rules run through all of it.
--
-- Tenancy: every aggregate root carries organisation_id and every index on one leads with it,
-- because every repository lookup takes an OrganisationId (ADR-009) and so every query already
-- knows what its WHERE clause must be. The five child tables -- employee_qualification,
-- employee_unavailability, employee_preference, shift_required_qualification, roster_assignment --
-- deliberately do NOT carry it: they have no identity apart from their root and are only ever
-- reached through it, so a query against one is tenant-scoped by the root it was given. That is an
-- invariant of the adapters, not of the schema, and a child query written without a root's ids
-- would escape it.
--
-- Constraints mirror domain invariants and express nothing the domain does not. Where the model
-- permits something untidy -- two organisations sharing a name, two qualifications sharing a name,
-- two constraints of one kind in a set, repeated swap requests between the same pair -- so does
-- this schema. A unique index the domain does not enforce is a new domain rule smuggled in as a
-- storage detail.
--
-- Foreign keys stay inside a bounded context, and reach the tenant register. A roster's assignment
-- names an employee by identity and nothing more, because scheduling never holds a workforce
-- entity (ADR-008); a foreign key there would couple two contexts in the one place the code is
-- careful not to.

-- access -----------------------------------------------------------------------------------

CREATE TABLE organisation (
    id   uuid PRIMARY KEY,
    name text NOT NULL CONSTRAINT organisation_name_not_blank CHECK (btrim(name) <> '')
);

-- workforce --------------------------------------------------------------------------------

CREATE TABLE qualification (
    id              uuid PRIMARY KEY,
    organisation_id uuid NOT NULL REFERENCES organisation (id),
    name            text NOT NULL CONSTRAINT qualification_name_not_blank CHECK (btrim(name) <> '')
);

-- Not unique on (organisation_id, name): defineQualification does not check for an existing name,
-- so a unique index here would refuse something the domain accepts. Present for the listing, which
-- is ordered by name.
CREATE INDEX qualification_by_organisation ON qualification (organisation_id, name);

CREATE TABLE employee (
    id                        uuid   PRIMARY KEY,
    organisation_id           uuid   NOT NULL REFERENCES organisation (id),
    name                      text   NOT NULL
        CONSTRAINT employee_name_not_blank CHECK (btrim(name) <> ''),
    -- Null for somebody with no contracted hours, such as casual staff; ContractTerms is optional
    -- on the aggregate and strictly positive when present.
    weekly_contracted_seconds bigint
        CONSTRAINT employee_contracted_hours_positive CHECK (weekly_contracted_seconds > 0)
);

CREATE INDEX employee_by_organisation ON employee (organisation_id, name);

-- A reference set, not owned rows: the qualification has a life of its own. Same context, so the
-- foreign key is real.
CREATE TABLE employee_qualification (
    employee_id      uuid NOT NULL REFERENCES employee (id) ON DELETE CASCADE,
    qualification_id uuid NOT NULL REFERENCES qualification (id),
    PRIMARY KEY (employee_id, qualification_id)
);

-- Owned by the employee: unavailability has no life of its own and is only ever read alongside
-- them. The ordinal keeps the aggregate's list order on the way back; it carries no domain
-- meaning. The domain also refuses overlapping periods, which is deliberately NOT enforced here --
-- an EXCLUDE constraint would need btree_gist and is recorded as a rejection in ADR-004.
CREATE TABLE employee_unavailability (
    employee_id uuid        NOT NULL REFERENCES employee (id) ON DELETE CASCADE,
    ordinal     integer     NOT NULL,
    starts_at   timestamptz NOT NULL,
    ends_at     timestamptz NOT NULL,
    PRIMARY KEY (employee_id, ordinal),
    CONSTRAINT employee_unavailability_ends_after_it_starts CHECK (ends_at > starts_at)
);

-- Also owned. An ordinal rather than a natural key because the domain permits duplicates: stating
-- the same preference twice is not an error.
CREATE TABLE employee_preference (
    employee_id uuid        NOT NULL REFERENCES employee (id) ON DELETE CASCADE,
    ordinal     integer     NOT NULL,
    starts_at   timestamptz NOT NULL,
    ends_at     timestamptz NOT NULL,
    strength    text        NOT NULL
        CONSTRAINT employee_preference_strength_known CHECK (strength IN ('PREFERS', 'PREFERS_NOT')),
    PRIMARY KEY (employee_id, ordinal),
    CONSTRAINT employee_preference_ends_after_it_starts CHECK (ends_at > starts_at)
);

-- scheduling -------------------------------------------------------------------------------

CREATE TABLE shift (
    id                 uuid        PRIMARY KEY,
    organisation_id    uuid        NOT NULL REFERENCES organisation (id),
    starts_at          timestamptz NOT NULL,
    ends_at            timestamptz NOT NULL,
    required_headcount integer     NOT NULL
        CONSTRAINT shift_headcount_at_least_one CHECK (required_headcount >= 1),
    CONSTRAINT shift_ends_after_it_starts CHECK (ends_at > starts_at)
);

-- findWithin asks for every shift overlapping a span, and the listing is ordered by start.
CREATE INDEX shift_by_organisation_and_period ON shift (organisation_id, starts_at, ends_at);

-- No foreign key to qualification: that table belongs to workforce, and this one to scheduling.
CREATE TABLE shift_required_qualification (
    shift_id         uuid NOT NULL REFERENCES shift (id) ON DELETE CASCADE,
    qualification_id uuid NOT NULL,
    PRIMARY KEY (shift_id, qualification_id)
);

-- Keyed by (organisation_id, version), not by organisation alone. A published roster records the
-- constraint version that approved it, and that record is worthless if the version cannot be read
-- back; findCurrent reads the highest. Storing only the current version was the debt recorded on
-- ConstraintSetRepository, and it stops accumulating here.
CREATE TABLE constraint_set (
    organisation_id uuid    NOT NULL REFERENCES organisation (id),
    version         integer NOT NULL
        CONSTRAINT constraint_set_version_starts_at_one CHECK (version >= 1),
    PRIMARY KEY (organisation_id, version)
);

-- One row per constraint in the set. The ordinal is the primary key rather than the kind, because
-- ConstraintSet is a plain list and permits two constraints of the same kind; refusing that here
-- would invent a rule.
--
-- Every constraint in the sealed hierarchy carries exactly one value: three are a Duration and one
-- is a count of days. The check makes the wrong shape unstorable rather than merely unlikely.
--
-- NO THRESHOLD APPEARS IN THIS FILE, and none may. A working-time limit is configuration supplied
-- by an organisation, never a number a developer chose.
CREATE TABLE working_time_constraint (
    organisation_id  uuid    NOT NULL,
    version          integer NOT NULL,
    ordinal          integer NOT NULL,
    kind             text    NOT NULL,
    duration_seconds bigint,
    day_count        integer,
    PRIMARY KEY (organisation_id, version, ordinal),
    FOREIGN KEY (organisation_id, version)
        REFERENCES constraint_set (organisation_id, version) ON DELETE CASCADE,
    CONSTRAINT working_time_constraint_kind_known CHECK (kind IN (
        'MINIMUM_REST_BETWEEN_ASSIGNMENTS',
        'MAXIMUM_DAILY_WORKING_TIME',
        'MAXIMUM_WEEKLY_WORKING_TIME',
        'MAXIMUM_CONSECUTIVE_WORKING_DAYS'
    )),
    -- Every branch tests IS NOT NULL explicitly. A CHECK passes when its expression is NULL as
    -- well as when it is TRUE, so `day_count IS NULL AND duration_seconds > 0` would evaluate to
    -- NULL -- and therefore pass -- for a row carrying neither value. Such a row stores, and then
    -- makes the whole organisation's set unreadable, because the mapper reads a SQL NULL back as
    -- zero and no working-time limit of zero can be constructed.
    CONSTRAINT working_time_constraint_carries_its_one_value CHECK (
        CASE kind
            WHEN 'MAXIMUM_CONSECUTIVE_WORKING_DAYS'
                THEN duration_seconds IS NULL AND day_count IS NOT NULL AND day_count >= 1
            ELSE day_count IS NULL AND duration_seconds IS NOT NULL AND duration_seconds > 0
        END
    )
);

CREATE TABLE roster (
    id                                 uuid        PRIMARY KEY,
    organisation_id                    uuid        NOT NULL REFERENCES organisation (id),
    starts_at                          timestamptz NOT NULL,
    ends_at                            timestamptz NOT NULL,
    status                             text        NOT NULL
        CONSTRAINT roster_status_known CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    -- No foreign key to constraint_set: publishing falls back to ConstraintSet.initial when an
    -- organisation has configured nothing, so a roster can legitimately name version 1 with no
    -- row to point at.
    validated_against_constraint_version integer,
    CONSTRAINT roster_ends_after_it_starts CHECK (ends_at > starts_at),
    -- A draft has not been checked against anything; anything else has. Both halves matter: the
    -- version is cleared again on every edit, so a mapper that only wrote it on publish would
    -- leave a stale one behind, and this refuses to store that.
    CONSTRAINT roster_version_iff_not_draft CHECK (
        (status = 'DRAFT') = (validated_against_constraint_version IS NULL)
    )
);

CREATE INDEX roster_by_organisation ON roster (organisation_id);

-- The composite primary key is Roster.requireNoDuplicates, in the schema. No foreign key to shift
-- or employee: an assignment names them by identity, and employee is another context's table.
CREATE TABLE roster_assignment (
    roster_id   uuid NOT NULL REFERENCES roster (id) ON DELETE CASCADE,
    shift_id    uuid NOT NULL,
    employee_id uuid NOT NULL,
    PRIMARY KEY (roster_id, shift_id, employee_id)
);

CREATE TABLE swap_request (
    id               uuid        PRIMARY KEY,
    organisation_id  uuid        NOT NULL REFERENCES organisation (id),
    roster_id        uuid        NOT NULL,
    shift_id         uuid        NOT NULL,
    requester_id     uuid        NOT NULL,
    counterparty_id  uuid        NOT NULL,
    status           text        NOT NULL
        CONSTRAINT swap_request_status_known
            CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    requested_at     timestamptz NOT NULL,
    CONSTRAINT swap_request_not_with_themself CHECK (requester_id <> counterparty_id)
);

-- Oldest first is what the port promises, and the identity breaks a tie so the order is stable.
CREATE INDEX swap_request_by_organisation_oldest_first
    ON swap_request (organisation_id, requested_at, id);

-- notifications ----------------------------------------------------------------------------

-- There is no message, title or name column, and there must never be one. A notification carries
-- identifiers, a kind and a time, so that it cannot quietly become a second copy of the roster
-- with people's names in it (ADR-021).
--
-- occurred_at has no default: the time comes from the caller's clock, not the database's.
CREATE TABLE notification (
    id              uuid        PRIMARY KEY,
    organisation_id uuid        NOT NULL REFERENCES organisation (id),
    recipient_id    uuid        NOT NULL,
    kind            text        NOT NULL
        CONSTRAINT notification_kind_known
            CHECK (kind IN ('SWAP_REQUESTED', 'SWAP_APPROVED', 'SWAP_REJECTED')),
    subject         uuid        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    read            boolean     NOT NULL
);

CREATE INDEX notification_by_inbox_newest_first
    ON notification (organisation_id, recipient_id, occurred_at DESC);
