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

  /** The top-level package that wires every context into Spring. */
  private static final String COMPOSITION_ROOT = "configuration";

  /**
   * Top-level packages that are not bounded contexts: the composition root, the architecture tests
   * themselves, and the shared kernel every context is allowed to depend on.
   *
   * <p>Not being a context exempts the composition root from the cross-context rule only. What it
   * may see inside a context is a rule of its own, which exempts its wiring and nothing else.
   */
  private static final Set<String> NOT_A_CONTEXT =
      Set.of(COMPOSITION_ROOT, "architecture", "shared");

  /** The packages every context is made of, and nothing else (ADR-003, ADR-006). */
  private static final Set<String> LAYERS = Set.of("domain", "application", "adapter");

  /** The only part of a context other contexts may reference: its driving ports. */
  private static final String PUBLISHED = ".application.port.in.";

  /** The only part of a context that may reference another: its driven adapters (ADR-008). */
  private static final String DRIVEN_ADAPTERS = ".adapter.out.";

  private final String rootPrefix;

  /**
   * @param root the package whose direct subpackages are the contexts, without a trailing dot
   */
  ContextPackages(String root) {
    this.rootPrefix = root + ".";
  }

  /**
   * @return an ArchUnit slice pattern capturing each top-level package under the root, contexts and
   *     the packages that are not one alike
   */
  String topLevelPackages() {
    return rootPrefix + "(*)..";
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
   * @return whether it sits in the composition root, or a package beneath it
   */
  boolean isInTheCompositionRoot(JavaClass clazz) {
    return (clazz.getPackageName() + ".").startsWith(rootPrefix + COMPOSITION_ROOT + ".");
  }

  /**
   * @param clazz any class
   * @return whether it sits in one of the layers of the context it belongs to; false for a class in
   *     no context
   */
  boolean isInALayer(JavaClass clazz) {
    return contextOf(clazz)
        .map(
            context -> {
              String below =
                  (clazz.getPackageName() + ".").substring((rootPrefix + context + ".").length());
              int dot = below.indexOf('.');
              return dot > 0 && LAYERS.contains(below.substring(0, dot));
            })
        .orElse(false);
  }

  /**
   * @param clazz any class
   * @return whether it sits in a context's published driving-port package
   */
  boolean isPublished(JavaClass clazz) {
    return (clazz.getPackageName() + ".").contains(PUBLISHED);
  }

  /**
   * @param clazz any class
   * @return whether it sits in the driven-adapter package of the context it belongs to
   */
  boolean isDrivenAdapter(JavaClass clazz) {
    return contextOf(clazz)
        .map(
            context ->
                (clazz.getPackageName() + ".").startsWith(rootPrefix + context + DRIVEN_ADAPTERS))
        .orElse(false);
  }
}
