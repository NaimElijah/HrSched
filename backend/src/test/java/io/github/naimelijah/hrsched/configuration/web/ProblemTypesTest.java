package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Specification that one kind of problem has one type URI, wherever it is answered (ADR-057).
 *
 * <p>Each context's web adapter, and {@code configuration.web}, holds the type URIs it answers with
 * in a class named {@code ProblemTypes} of its own, because a context may not reach another's
 * adapter (ADR-008). A kind answered in two places, such as {@code not-found}, is therefore written
 * down twice, and this is what keeps the copies equal: every constant's URI is its name in kebab
 * case under {@code urn:hrsched:problem:}, so two constants of one name cannot hold two URIs.
 *
 * <p>The classes are found by name across the whole application, so a context that adds one is
 * checked without being listed here.
 */
class ProblemTypesTest {

  private static final String PREFIX = "urn:hrsched:problem:";

  private static final String CLASS_NAME = "ProblemTypes";

  /** Every producer of problem details today; a test that found none would pass vacuously. */
  private static final List<String> KNOWN_PACKAGES =
      List.of(
          "io.github.naimelijah.hrsched.access.adapter.in.web",
          "io.github.naimelijah.hrsched.configuration.web",
          "io.github.naimelijah.hrsched.notifications.adapter.in.web",
          "io.github.naimelijah.hrsched.scheduling.adapter.in.web",
          "io.github.naimelijah.hrsched.workforce.adapter.in.web");

  private static List<Class<?>> problemTypes;

  @BeforeAll
  static void findEveryProblemTypesClass() {
    problemTypes = new ArrayList<>();
    for (JavaClass found :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.naimelijah.hrsched")) {
      if (CLASS_NAME.equals(found.getSimpleName())) {
        problemTypes.add(found.reflect());
      }
    }
  }

  @Test
  void GivenTheApplication_WhenItsProblemTypesAreFound_ThenEveryProducerHasOne() {
    assertThat(problemTypes).extracting(Class::getPackageName).containsAll(KNOWN_PACKAGES);
  }

  @Test
  void GivenEveryProblemTypesClass_WhenItsConstantsAreRead_ThenEachUriIsItsNameInKebabCase()
      throws IllegalAccessException {
    int read = 0;
    for (Class<?> types : problemTypes) {
      for (Field constant : types.getDeclaredFields()) {
        if (constant.getType() != URI.class || !Modifier.isStatic(constant.getModifiers())) {
          continue;
        }
        constant.setAccessible(true);
        read++;
        assertThat(constant.get(null))
            .describedAs("%s.%s", types.getName(), constant.getName())
            .hasToString(PREFIX + constant.getName().toLowerCase(Locale.ROOT).replace('_', '-'));
      }
    }
    assertThat(read).describedAs("the constants read").isPositive();
  }
}
