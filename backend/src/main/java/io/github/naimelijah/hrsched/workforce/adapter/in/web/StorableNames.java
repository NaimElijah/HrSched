package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import java.nio.charset.StandardCharsets;

/**
 * Refuses a name the store could not hold as sent: an employee's or a qualification's.
 *
 * <p>The limits are the database's and its driver's, not rules about names, and they are checked
 * here so a caller's unstorable name is answered 400 instead of reaching the adapter and failing
 * there as a 500, or being stored changed. Whether a name is blank is the domain's rule, and is
 * left to it.
 *
 * <p>Both the byte bound and the surrogate refusal assume the database stores text as UTF-8, as the
 * PostgreSQL image every environment here runs does. Nothing checks that: under another encoding a
 * name it cannot represent would fail in the store as a 500, and the bound would count the wrong
 * bytes.
 *
 * <p>No message repeats the name sent (ADR-024): a name is who somebody is.
 *
 * <p>This context's own, as {@link StorableSpans} is: contexts do not share adapter code (ADR-008).
 */
final class StorableNames {

  /**
   * The longest name stored, in bytes of UTF-8. Both name columns are indexed with their
   * organisation ({@code employee_by_organisation}, {@code qualification_by_organisation}, V1), and
   * PostgreSQL's btree refuses an index entry over 2,704 bytes, which a name that does not compress
   * reaches at about 2,670. This leaves room under that for the entry's own overhead. It is a limit
   * of the store, chosen to sit safely inside it, and says nothing about how long a name may be.
   */
  static final int MOST_BYTES = 2_000;

  private static final char NUL = '\u0000';

  private StorableNames() {}

  /**
   * @param name a name as sent, never null
   * @return the same name, which the store holds exactly as sent
   * @throws MalformedRequestException if the name holds a NUL, which PostgreSQL's {@code text}
   *     cannot store; holds half a surrogate pair, which UTF-8 cannot encode and pgjdbc would store
   *     as {@code ?}; or is longer than {@link #MOST_BYTES} in UTF-8
   */
  static String requireStorable(String name) {
    if (name.indexOf(NUL) >= 0) {
      throw new MalformedRequestException("name must not contain a NUL character");
    }
    if (!StandardCharsets.UTF_8.newEncoder().canEncode(name)) {
      throw new MalformedRequestException(
          "name must not contain half of a surrogate pair, which cannot be stored");
    }
    if (name.getBytes(StandardCharsets.UTF_8).length > MOST_BYTES) {
      throw new MalformedRequestException(
          "name must be at most %d bytes long in UTF-8, the most that can be stored"
              .formatted(MOST_BYTES));
    }
    return name;
  }
}
