package io.github.naimelijah.hrsched.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Optional;
import java.util.Set;

/**
 * Knows how a package name maps onto a bounded context, for the architecture rules.
 *
 * <p>Extracted so the cross-context rule reads as one sentence rather than a wall of string
 * matching, and so the notion of "published" lives in exactly one place.
 */
final class ContextPackages {

  private static final String ROOT = "io.github.naimelijah.hrsched.";

  /**
   * Top-level packages that are not bounded contexts: the composition root, the architecture tests
   * themselves, and the shared kernel every context is allowed to depend on.
   */
  private static final Set<String> NOT_A_CONTEXT =
      Set.of("configuration", "architecture", "shared");

  /** The only part of a context other contexts may reference: its driving ports. */
  private static final String PUBLISHED = ".application.port.in.";

  private ContextPackages() {}

  /**
   * @param clazz any class
   * @return the bounded context it belongs to, or empty if it is not in one
   */
  static Optional<String> contextOf(JavaClass clazz) {
    String name = clazz.getPackageName();
    if (!name.startsWith(ROOT)) {
      return Optional.empty();
    }
    String rest = name.substring(ROOT.length());
    int dot = rest.indexOf('.');
    String head = dot < 0 ? rest : rest.substring(0, dot);
    return head.isEmpty() || NOT_A_CONTEXT.contains(head) ? Optional.empty() : Optional.of(head);
  }

  /**
   * @param clazz any class
   * @return whether it sits in a context's published driving-port package
   */
  static boolean isPublished(JavaClass clazz) {
    return (clazz.getPackageName() + ".").contains(PUBLISHED);
  }
}
