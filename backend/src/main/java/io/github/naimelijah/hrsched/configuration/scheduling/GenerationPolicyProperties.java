package io.github.naimelijah.hrsched.configuration.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a generation may stay pending before it is reported lost (ADR-046).
 *
 * <p>Lives in {@code configuration} rather than in the scheduling adapters because it is what the
 * composition root turns into the domain's {@code GenerationPolicy} before handing it to the use
 * case, as {@code SecurityPolicyProperties} is for the access policies. It has no default in code:
 * it must exceed how long a solve may run, and that is configuration too.
 *
 * @param lostAfter how long after it is asked for a generation still pending counts as lost
 */
@ConfigurationProperties("hrsched.generation")
public record GenerationPolicyProperties(Duration lostAfter) {}
