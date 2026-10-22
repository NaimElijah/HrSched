-- A draft is generated in the background, and how each generation stands is kept here, so that any
-- instance can answer a Scheduler asking whether theirs has finished (ADR-046).
--
-- A generation is PENDING until it ends, once: SUCCEEDED with its draft, REFUSED with the rules the
-- work it would carry over breaks (#130), or FAILED. LOST is never stored: it is a PENDING row read
-- at or after its lost_at, which the instance that was solving it can no longer move on.
--
-- Nothing deletes a row. How long they are kept is Q23's to decide.

CREATE TABLE roster_generation (
    id              uuid        PRIMARY KEY,
    organisation_id uuid        NOT NULL REFERENCES organisation (id),
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
    requested_at    timestamptz NOT NULL,
    lost_at         timestamptz NOT NULL,
    status          text        NOT NULL
        CONSTRAINT roster_generation_status_known
            CHECK (status IN ('PENDING', 'SUCCEEDED', 'REFUSED', 'FAILED')),
    roster_id       uuid        REFERENCES roster (id),
    finished_at     timestamptz,
    CONSTRAINT roster_generation_ends_after_it_starts CHECK (ends_at > starts_at),
    CONSTRAINT roster_generation_lost_after_asked CHECK (lost_at > requested_at),
    CONSTRAINT roster_generation_ended_once CHECK ((status = 'PENDING') = (finished_at IS NULL)),
    CONSTRAINT roster_generation_names_its_draft CHECK ((status = 'SUCCEEDED') = (roster_id IS NOT NULL))
);

-- The rules a refused generation's carried work breaks, in the order the domain listed them. Names
-- are identities only, as every violation's are (ADR-042).
CREATE TABLE roster_generation_violation (
    generation_id   uuid    NOT NULL REFERENCES roster_generation (id),
    position        integer NOT NULL CHECK (position >= 0),
    employee_id     uuid    NOT NULL,
    constraint_name text    NOT NULL,
    detail          text    NOT NULL,
    PRIMARY KEY (generation_id, position)
);
