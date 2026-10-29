package io.github.naimelijah.hrsched.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Optional;
import java.util.Set;

/**
 * Knows how a package name maps onto a bounded context, for the architecture rules.
 *
 * <p>Extracted so the cross-context rule reads as one sentence rather than a wall of string
 * matching, and so the notion of "published" lives in exactly one place.
 *
 * <p>The root is a parameter so that {@code HexagonalArchitectureRulesTest} can evaluate the very
 * same rules against fixture contexts kept under a test-only package, rather than against copies of
 * the rules that could drift from the ones the build enforces.
 */
final class ContextPackages {

  /** HrSched's own packages: every rule the build enforces is built from this. */
  static final ContextPackages HRSCHED = new ContextPackages("io.github.naimelijah.hrsched");

  /**
   * Top-level packages that are not bounded contexts: the composition root, the architecture tests
   * themselves, and the shared kernel every context is allowed to depend on.
   */
  private static final Set<String> NOT_A_CONTEXT =
      Set.of("configuration", "architecture", "shared");

  /** The only part of a context other contexts may reference: its driving ports. */
  private static final String PUBLISHED = ".application.port.in.";

  private final String rootPrefix;

  /**
   * @param root the package whose direct subpackages are the contexts, without a trailing dot
   */
  ContextPackages(String root) {
    this.rootPrefix = root + ".";
  }

  /**
   * @param clazz any class
   * @return the bounded context it belongs to, or empty if it is not in one
   */
  Optional<String> contextOf(JavaClass clazz) {
    String name = clazz.getPackageName();
    if (!name.startsWith(rootPrefix)) {
      return Optional.empty();
    }
    String rest = name.substring(rootPrefix.length());
    int dot = rest.indexOf('.');
    String head = dot < 0 ? rest : rest.substring(0, dot);
    return head.isEmpty() || NOT_A_CONTEXT.contains(head) ? Optional.empty() : Optional.of(head);
  }

  /**
   * @param clazz any class
   * @return whether it sits in a context's published driving-port package
   */
  boolean isPublished(JavaClass clazz) {
    return (clazz.getPackageName() + ".").contains(PUBLISHED);
  }
}
