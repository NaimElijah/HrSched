-- Each organisation weights the objectives that make one legal roster better than another (Q13).
-- See ADR-010.
--
-- One row per organisation, replaced whole on every revision, and never versioned: nothing is
-- judged against a weight, so there is no past revision a roster has to keep true. A column per
-- objective, each null when the organisation does not weigh it. As in V1, no weight appears in
-- this file: the checks refuse what the domain refuses (R40), and nothing defaults one.

CREATE TABLE objective_weights (
    organisation_id uuid PRIMARY KEY REFERENCES organisation (id),
    fairness integer CHECK (fairness >= 1),
    preferences integer CHECK (preferences >= 1),
    disruption integer CHECK (disruption >= 1)
);
