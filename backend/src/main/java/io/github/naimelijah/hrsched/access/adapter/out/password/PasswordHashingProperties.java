package io.github.naimelijah.hrsched.access.adapter.out.password;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much work hashing a password costs.
 *
 * <p>Configuration rather than a constant, because the right work factor depends on the hardware
 * this runs on and on how much a sign-in is allowed to cost — neither of which a developer can
 * decide once for every deployment. Tests use the cheapest bcrypt permits, for the same reason.
 *
 * @param bcryptStrength bcrypt's cost parameter; each increment doubles the work
 */
@ConfigurationProperties("hrsched.security.password")
public record PasswordHashingProperties(int bcryptStrength) {}
