package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p>Neither holds if a problem is typed with a URI written where it is built, such as {@code
 * setType(URI.create("urn:hrsched:problem:not-found"))} in a handler: that copy is in no {@code
 * ProblemTypes}, so nothing above reads it. So no other class may carry the prefix at all. The
 * compiled class files are read rather than the source, because the literal of every string a class
 * holds, a concatenation's included, is kept in its constant pool, and javadoc is not.
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

  private static List<JavaClass> everyOtherClass;

  @BeforeAll
  static void findEveryProblemTypesClass() {
    problemTypes = new ArrayList<>();
    everyOtherClass = new ArrayList<>();
    for (JavaClass found :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.naimelijah.hrsched")) {
      if (CLASS_NAME.equals(found.getSimpleName())) {
        problemTypes.add(found.reflect());
      } else {
        everyOtherClass.add(found);
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

  @Test
  void GivenEveryOtherClass_WhenItsClassFileIsRead_ThenItWritesNoProblemTypeOfItsOwn()
      throws IOException {
    byte[] prefix = PREFIX.getBytes(StandardCharsets.US_ASCII);
    List<String> writingOne = new ArrayList<>();
    for (JavaClass candidate : everyOtherClass) {
      if (contains(classFileOf(candidate), prefix)) {
        writingOne.add(candidate.getName());
      }
    }
    assertThat(everyOtherClass).describedAs("the classes read").isNotEmpty();
    assertThat(writingOne)
        .describedAs("classes outside a ProblemTypes holding %s", PREFIX)
        .isEmpty();
  }

  private static byte[] classFileOf(JavaClass candidate) throws IOException {
    Source source =
        candidate
            .getSource()
            .orElseThrow(() -> new AssertionError("no class file for " + candidate.getName()));
    try (InputStream in = source.getUri().toURL().openStream()) {
      return in.readAllBytes();
    }
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    for (int start = 0; start <= haystack.length - needle.length; start++) {
      if (Arrays.equals(haystack, start, start + needle.length, needle, 0, needle.length)) {
        return true;
      }
    }
    return false;
  }
}
