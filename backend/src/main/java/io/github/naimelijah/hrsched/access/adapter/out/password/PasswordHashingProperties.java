package io.github.naimelijah.hrsched.access.adapter.out.password;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much work hashing a password costs.
 *
 * <p>Configuration rather than a constant, because the right work factor depends on the hardware
 * this runs on and on how much a sign-in is allowed to cost — neither of which a developer can
 * decide once for every deployment. Tests use the cheapest bcrypt permits, for the same reason.
 *
 * <p>How <em>long</em> a password must be is deliberately not here. That is a policy, and it lives
 * with the other policies in {@code configuration.security}, where the composition root can read it
 * — this record is an adapter's, and configuration may not name one.
 *
 * @param bcryptStrength bcrypt's cost parameter; each increment doubles the work
 */
@ConfigurationProperties("hrsched.security.password")
public record PasswordHashingProperties(int bcryptStrength) {}
