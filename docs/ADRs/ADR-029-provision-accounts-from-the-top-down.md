# ADR-029: Provision accounts from the top down, with temporary passwords, and bootstrap the operator from configuration

- **Status:** Accepted
- **Date:** 2026-09-16

## Context

[ADR-011](ADR-011-issue-our-own-access-tokens.md) decides that HrSched holds its own accounts and
signs its own tokens. That leaves a question it does not answer: where the first account comes
from, and who is allowed to create the rest.

Every account has to be created by somebody. An employee cannot create their own, because nothing
would stop anybody who found the URL from creating one and claiming a role; an organisation's first
Admin cannot be created by that organisation, because it does not exist yet. The chain has to start
somewhere outside a request, and the only thing outside a request that a deployment controls is its
own configuration.

There is also no outbound channel. Whether this system ever sends email is Q21 and undecided, so a
flow that depends on an invitation link cannot be built now without deciding it by accident.

Two smaller forces shaped the details:

- A bootstrap runs on **every** start, and a deployment's configuration outlives a restart. Anything
  it writes unconditionally, it writes again next Tuesday.
- The platform operator belongs to no organisation. Every other aggregate root in this schema
  carries an `organisation_id`, and the operator cannot.

## Decision

**We provision accounts from the top down, and the chain starts in configuration.**

- **The operator is bootstrapped from configuration.** `HRSCHED_BOOTSTRAP_OPERATOR_SIGNINNAME` and
  `_PASSWORD` create the platform operator at startup, through `OperatorBootstrap` driving
  `BootstrapOperatorUseCase`. Setting neither creates nothing. Setting exactly one fails startup:
  a half-configured bootstrap would otherwise be indistinguishable from one that meant to create
  nothing, and the operator would find out when they could not sign in.
- **It inserts only if absent, and never overwrites.** The write is a single
  `INSERT … ON CONFLICT (sign_in_name) DO NOTHING`, so asking whether the name is free and taking it
  are one statement and two instances starting together cannot both create it. An account that
  already holds the name is left exactly as it is — in particular its password, which the operator
  may have changed, is never put back to whatever is still in the environment.
- **The configured password is not checked against the password policy.** It is chosen by whoever
  runs the deployment rather than submitted by a user, and it is temporary: the account is created
  with a change required, and the policy applies at that change. Enforcing a policy on it would only
  make a deployment harder to start without making any account safer for longer than one sign-in.
- **An Admin creates the accounts of their own organisation, and onboarding creates the first one.**
  Onboarding an organisation takes that organisation's first Admin with it, so no organisation ever
  exists without somebody able to administer it, and the operator never has to reach inside one.
- **Every account somebody else created must change its password at first sign-in.** The creator
  sets a temporary one and no message is sent, because there is no channel to send one on (Q21).
- **Accounts stay in the `access` context.** [ADR-019](ADR-019-an-application-layer-for-the-access-context.md)
  named three concepts that would justify splitting a separate identity context; one of them has
  arrived, not all three, so its trigger has not fired.
- **The operator's row carries no organisation**, which makes it the one aggregate root besides
  `organisation` itself without an `organisation_id`. A `CHECK` keeps the two shapes of the row from
  blurring, and every tenant-scoped read filters the operator out by that column rather than by a
  guard above it.

## Consequences

**Easier.** There is exactly one way an account comes into being, and it is always somebody who
already had an account — or the deployment itself. Nothing is self-service, so there is no flow that
hands out access to whoever finds it. A tenant's account list can never show the operator, because
the operator has no organisation to be listed under. And the bootstrap is safe to leave configured:
running it again is a no-op.

**Harder, and honestly so.** A password has to travel from whoever created an account to whoever
will use it, out of band, and this system has no opinion about how. That is a real gap and not a
neat one; it is the price of having no outbound channel yet.

A tenant whose only Admin is locked out or has forgotten their password cannot be helped by the
operator, who is deliberately confined to the register of organisations
([ADR-030](ADR-030-confine-the-platform-operator.md)). That is recorded as **Q27** and is not solved
here.

The bootstrap credentials sit in a deployment's environment for as long as they are configured. They
can be removed once the account exists, and nothing reminds anybody to. Whether there may be more
than one operator, and how an operator's credential is rotated, is **Q30**.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| Self-sign-up | Nothing would stop whoever found the endpoint from creating an account, and an account's organisation and role are exactly what its holder must not choose. Revisit if this ever serves a public audience where an unprivileged account means nothing — which is not this product. |
| Email invitations, with a link instead of a temporary password | Needs an outbound channel, and whether this system sends anything is Q21. Choosing it here would decide Q21 as a side effect. **Revisit when Q21 names a channel**, at which point the temporary password should probably be replaced by a single-use link. |
| Seed the operator in a Flyway migration | Puts a credential in the repository, where it is committed, shared, and identical in every deployment. Rejected outright; there is no threshold that makes this acceptable. |
| A CLI command to create the first operator | Keeps the credential out of the running configuration, which is genuinely better, but needs an operator to have shell access to the deployment and a documented procedure to follow. **Revisit when there is an operations runbook** and a way to run one-off commands against a deployment. |
| Overwrite the operator's password on every start | Would make the environment authoritative, so a stolen environment variable stays valid forever and a password the operator chose is silently undone. Rejected. |
| A separate identity bounded context | ADR-019's trigger has not fired: it named three concepts, and one has arrived. Revisit when accounts, sessions and something third genuinely pull apart from the register of organisations. |
