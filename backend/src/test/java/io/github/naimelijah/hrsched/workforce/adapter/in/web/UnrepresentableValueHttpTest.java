package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.asThemself;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Values the system cannot store or represent are the caller's mistake, answered 400 with a problem
 * detail, and nothing of them is stored.
 *
 * <p>The limits are technical, not rules of the business: an instant must lie within what the store
 * holds, a span must not collapse to nothing once stored at the store's precision, a count of
 * minutes must convert to a duration without overflowing, and a name must hold no NUL and fit in an
 * index entry. Before these were checked, each reached the adapter, {@code Duration} or the
 * database unchecked and answered 500 with an ERROR log — a caller's typo reported as our defect.
 * Values just inside each limit are stored as sent, so the limits are pinned from both sides.
 *
 * <p>No refusal repeats the value sent (ADR-024): a span says when somebody cannot work, contracted
 * hours say things about a household, and a name is who somebody is.
 *
 * <p>EVERY INSTANT, FIGURE AND NAME BELOW IS INVENTED. The out-of-range instants are chosen only
 * for lying past a technical limit — what {@code OffsetDateTime} can hold, what PostgreSQL's {@code
 * timestamptz} can, and what pgjdbc writes faithfully — and the ordinary ones only for being
 * ordinary. The long names are letters drawn from a seeded generator, chosen only for their length
 * and for not compressing.
 *
 * <p>Annotated identically to {@code EmployeeHttpTest}, so it shares the suite's Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class UnrepresentableValueHttpTest {

  /**
   * Invented. A valid {@code Instant} — under {@code Instant.MAX} — whose year is past what {@code
   * OffsetDateTime} holds, so storing it at UTC cannot even be expressed.
   */
  private static final String PAST_A_DATE_TIME = "+1000000000-01-01T00:00:00Z";

  /** Invented. A day after {@link #PAST_A_DATE_TIME}, so the span is otherwise well formed. */
  private static final String PAST_A_DATE_TIME_END = "+1000000000-01-02T00:00:00Z";

  /** Invented. Representable in Java, but past the last year PostgreSQL's timestamptz holds. */
  private static final String PAST_THE_STORE = "+300000-01-01T00:00:00Z";

  /** Invented. A day after {@link #PAST_THE_STORE}. */
  private static final String PAST_THE_STORE_END = "+300000-01-02T00:00:00Z";

  /**
   * Invented. Two ends a hundred nanoseconds apart: a span in Java, but the same microsecond once
   * stored, where the table refuses an end that is not after its start.
   */
  private static final String SUB_MICROSECOND_START = "2026-11-16T09:00:00.000000100Z";

  /** Invented. See {@link #SUB_MICROSECOND_START}. */
  private static final String SUB_MICROSECOND_END = "2026-11-16T09:00:00.000000200Z";

  /**
   * Invented. Inside PostgreSQL's range, which begins at 4714-11-24 BC, but before 4713-01-01 BC,
   * where pgjdbc stops writing an instant as itself and writes {@code -infinity} instead.
   */
  private static final String BEFORE_THE_EARLIEST_STORABLE = "-4713-11-24T00:00:00Z";

  /** Invented, though the limit is not: the first instant pgjdbc writes as itself. */
  private static final String EARLIEST_STORABLE = "-4712-01-01T00:00:00Z";

  /** Invented, though the limit is not: the last instant PostgreSQL's timestamptz holds. */
  private static final String LATEST_STORABLE = "+294276-12-31T23:59:59.999999Z";

  /**
   * Invented. Half a microsecond after {@link #LATEST_STORABLE}, which pgjdbc's half-up rounding
   * would carry past the edge.
   */
  private static final String ROUNDING_PAST_THE_LATEST = "+294276-12-31T23:59:59.9999995Z";

  /** Invented. An ordinary instant to end a span whose start is the one under test. */
  private static final String ORDINARY_END = "2026-11-16T10:00:00Z";

  /** Invented. An ordinary instant to start a span whose end is the one under test. */
  private static final String ORDINARY_START = "2026-11-16T08:00:00Z";

  /** Invented. A whole number of minutes for which {@code Duration.ofMinutes} overflows upwards. */
  private static final long OVERFLOWING_MINUTES = Long.MAX_VALUE;

  /**
   * Invented. A whole number of minutes for which {@code Duration.ofMinutes} overflows downwards.
   */
  private static final long UNDERFLOWING_MINUTES = Long.MIN_VALUE;

  /**
   * What would give away an instant sent in this class if a detail repeated it in any rendering:
   * its out-of-range year, or its sub-microsecond digits. Every refusal is checked against all of
   * them, as well as against the instants it was itself sent.
   */
  private static final List<String> DISTINCTIVE_PARTS_SENT =
      List.of("300000", "1000000000", ".0000001", ".0000006", "-4713", "294276", ".9999995");

  /** The leading digits both {@link Long#MAX_VALUE} and {@link Long#MIN_VALUE} share. */
  private static final String DISTINCTIVE_PART_OF_THE_MINUTES = "922337203685477580";

  /**
   * Invented. A name PostgreSQL cannot store at all: {@code text} holds no NUL (SQLSTATE 22021).
   */
  private static final String NAME_HOLDING_A_NUL = "Ada\u0000Lovelace";

  /**
   * Invented. Half of a surrogate pair, which no UTF-8 can encode: pgjdbc would store a {@code ?}
   * in its place, so the name would read back changed.
   */
  private static final String NAME_HOLDING_AN_UNPAIRED_SURROGATE = "Ada\uD800Lovelace";

  /**
   * The two halves of {@link #NAME_HOLDING_A_NUL} and {@link #NAME_HOLDING_AN_UNPAIRED_SURROGATE},
   * which no refusal may repeat either.
   */
  private static final String[] HALVES_OF_THE_UNSTORABLE_NAMES = {"Ada", "Lovelace"};

  /**
   * Invented. U+20BB7, a character outside the Basic Multilingual Plane written in names, which
   * Java holds as a whole surrogate pair: what the unpaired-surrogate check must still let through.
   */
  private static final String NAME_HOLDING_A_SURROGATE_PAIR = "Ada 𠮷";

  /**
   * Invented. Past the 2,704 bytes PostgreSQL's btree holds in one index entry, which the name
   * indexes on both {@code employee} and {@code qualification} would have to store it in.
   */
  private static final int PAST_AN_INDEX_ENTRY_BYTES = 3_000;

  /** Invented. Encoded in two bytes of UTF-8, so a name of them is twice as long in bytes. */
  private static final String TWO_BYTE_LETTER = "é";

  /** Invented. Any fixed seed: it only has to send the same long names on every run. */
  private static final long LONG_NAME_SEED = 254;

  /** How many characters of a long name a refusal is checked not to repeat. */
  private static final int DISTINCTIVE_PREFIX_LENGTH = 16;

  @Autowired private MockMvc mockMvc;

  private String addEmployee(String organisation) throws Exception {
    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                    .content("{\"name\":\"Ada\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private static void isRefusedRepeatingNothingSent(ResultActions response, String... sent)
      throws Exception {
    response
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(jsonPath("$.detail").isNotEmpty());
    for (String value : sent) {
      response.andExpect(jsonPath("$.detail").value(not(containsString(value))));
    }
    for (String part : DISTINCTIVE_PARTS_SENT) {
      response.andExpect(jsonPath("$.detail").value(not(containsString(part))));
    }
  }

  private void declaringOwnUnavailabilityIsRefused(String start, String end) throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);

    isRefusedRepeatingNothingSent(
        mockMvc.perform(
            asThemself(
                    mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, ada)
                .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(start, end))),
        start,
        end);

    mockMvc
        .perform(
            asThemself(
                mockMvc, get("/api/employees/" + ada + "/unavailability"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  private void statingOwnPreferenceIsRefused(String start, String end) throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);

    isRefusedRepeatingNothingSent(
        mockMvc.perform(
            asThemself(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, ada)
                .content(
                    "{\"start\":\"%s\",\"end\":\"%s\",\"strength\":\"PREFERS\"}"
                        .formatted(start, end))),
        start,
        end);

    mockMvc
        .perform(
            asThemself(mockMvc, get("/api/employees/" + ada + "/preferences"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /** Declares the span as the Employee themself, then reads back exactly the instants given. */
  private void declaringOwnUnavailabilityStores(
      String start, String end, String storedStart, String storedEnd) throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);

    mockMvc
        .perform(
            asThemself(
                    mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, ada)
                .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(start, end)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            asThemself(
                mockMvc, get("/api/employees/" + ada + "/unavailability"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].start").value(storedStart))
        .andExpect(jsonPath("$[0].end").value(storedEnd));
  }

  private void settingAContractIsRefusedAndTheOldOneKept(long minutes) throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);
    // Invented: contracted hours are a term of an individual agreement, never a statutory figure.
    long ordinaryMinutes = 1501;
    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":%d}".formatted(ordinaryMinutes)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":%d}".formatted(minutes)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(jsonPath("$.detail").isNotEmpty())
        .andExpect(jsonPath("$.detail").value(not(containsString(String.valueOf(minutes)))))
        .andExpect(
            jsonPath("$.detail").value(not(containsString(DISTINCTIVE_PART_OF_THE_MINUTES))));

    mockMvc
        .perform(as(mockMvc, get("/api/employees/" + ada), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.weeklyContractedMinutes").value(ordinaryMinutes));
  }

  /**
   * Letters drawn from a seeded generator. PostgreSQL compresses an index entry before measuring it
   * against its limit, so a name repeating itself would fit where an ordinary one of the same
   * length does not.
   */
  private static String incompressibleName(int bytes) {
    String letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    Random random = new Random(LONG_NAME_SEED);
    StringBuilder name = new StringBuilder(bytes);
    for (int i = 0; i < bytes; i++) {
      name.append(letters.charAt(random.nextInt(letters.length())));
    }
    return name.toString();
  }

  /**
   * A JSON body naming {@code name}. A NUL and every surrogate are sent as JSON escapes: JSON
   * carries no raw NUL, and the request body is encoded as UTF-8, which would turn a lone surrogate
   * into {@code ?} before the server ever saw it.
   */
  private static String named(String name) {
    StringBuilder escaped = new StringBuilder(name.length());
    for (char c : name.toCharArray()) {
      if (c == '\u0000' || Character.isSurrogate(c)) {
        escaped.append("\\u%04x".formatted((int) c));
      } else {
        escaped.append(c);
      }
    }
    return "{\"name\":\"%s\"}".formatted(escaped);
  }

  private void addingAnEmployeeIsStoredAsSent(String name) throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(as(mockMvc, post("/api/employees"), organisation, "ADMIN").content(named(name)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(as(mockMvc, get("/api/employees"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value(name));
  }

  private void definingAQualificationIsStoredAsSent(String name) throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, post("/api/qualifications"), organisation, "ADMIN").content(named(name)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(as(mockMvc, get("/api/qualifications"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value(name));
  }

  private static String[] whatALongNameMustNotRepeat(String name) {
    return new String[] {name, name.substring(0, DISTINCTIVE_PREFIX_LENGTH)};
  }

  private void addingAnEmployeeIsRefusedUnstored(String name, String... sent) throws Exception {
    String organisation = anOrganisation(mockMvc);

    isRefusedRepeatingNothingSent(
        mockMvc.perform(
            as(mockMvc, post("/api/employees"), organisation, "ADMIN").content(named(name))),
        sent);

    mockMvc
        .perform(as(mockMvc, get("/api/employees"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  private void definingAQualificationIsRefusedUnstored(String name, String... sent)
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    isRefusedRepeatingNothingSent(
        mockMvc.perform(
            as(mockMvc, post("/api/qualifications"), organisation, "ADMIN").content(named(name))),
        sent);

    mockMvc
        .perform(as(mockMvc, get("/api/qualifications"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenAnInstantPastWhatADateTimeHolds_WhenDeclaringOwnUnavailability_ThenRefusedUnstored()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(PAST_A_DATE_TIME, PAST_A_DATE_TIME_END);
  }

  @Test
  void GivenAnInstantPastWhatTheStoreHolds_WhenDeclaringOwnUnavailability_ThenRefusedUnstored()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(PAST_THE_STORE, PAST_THE_STORE_END);
  }

  @Test
  void GivenEndsDifferingBelowAMicrosecond_WhenDeclaringOwnUnavailability_ThenRefusedUnstored()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(SUB_MICROSECOND_START, SUB_MICROSECOND_END);
  }

  @Test
  void GivenAnInstantPastWhatADateTimeHolds_WhenStatingOwnPreference_ThenRefusedUnstored()
      throws Exception {
    statingOwnPreferenceIsRefused(PAST_A_DATE_TIME, PAST_A_DATE_TIME_END);
  }

  @Test
  void GivenAnInstantPastWhatTheStoreHolds_WhenStatingOwnPreference_ThenRefusedUnstored()
      throws Exception {
    statingOwnPreferenceIsRefused(PAST_THE_STORE, PAST_THE_STORE_END);
  }

  @Test
  void GivenEndsDifferingBelowAMicrosecond_WhenStatingOwnPreference_ThenRefusedUnstored()
      throws Exception {
    statingOwnPreferenceIsRefused(SUB_MICROSECOND_START, SUB_MICROSECOND_END);
  }

  // --- the lower bound ------------------------------------------------------------------------

  @Test
  void GivenAStartBeforeTheEarliestStorable_WhenDeclaringOwnUnavailability_ThenRefusedUnstored()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(BEFORE_THE_EARLIEST_STORABLE, ORDINARY_END);
  }

  @Test
  void GivenAStartBeforeTheEarliestStorable_WhenStatingOwnPreference_ThenRefusedUnstored()
      throws Exception {
    statingOwnPreferenceIsRefused(BEFORE_THE_EARLIEST_STORABLE, ORDINARY_END);
  }

  @Test
  void GivenAStartAtExactlyTheEarliestStorable_WhenDeclaringOwnUnavailability_ThenStoredAsSent()
      throws Exception {
    declaringOwnUnavailabilityStores(
        EARLIEST_STORABLE, ORDINARY_END, EARLIEST_STORABLE, ORDINARY_END);
  }

  // --- the upper edge -------------------------------------------------------------------------

  @Test
  void GivenAnEndAtExactlyTheLatestStorable_WhenDeclaringOwnUnavailability_ThenStoredAsSent()
      throws Exception {
    declaringOwnUnavailabilityStores(
        ORDINARY_START, LATEST_STORABLE, ORDINARY_START, LATEST_STORABLE);
  }

  @Test
  void GivenAnEndThatRoundsPastTheLatestStorable_WhenDeclaringOwnUnavailability_ThenRefused()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(ORDINARY_START, ROUNDING_PAST_THE_LATEST);
  }

  // --- the rounding direction: half-up to the microsecond, as pgjdbc stores it ----------------

  /**
   * 0.6 microseconds rounds up to 1 and 1.4 rounds down to 1, so the span is empty once stored. A
   * truncating check would see 0 and 1, and let it through to the table's CHECK.
   */
  @Test
  void GivenEndsRoundingToTheSameMicrosecond_WhenDeclaringOwnUnavailability_ThenRefusedUnstored()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(
        "2026-11-16T09:00:00.0000006Z", "2026-11-16T09:00:00.0000014Z");
  }

  /**
   * 0.4 microseconds rounds down to 0 and 0.6 up to 1, so the span survives storing as one
   * microsecond. A truncating check would see 0 and 0, and refuse a span the store would keep.
   */
  @Test
  void GivenEndsRoundingToAdjacentMicroseconds_WhenDeclaringOwnUnavailability_ThenStoredRounded()
      throws Exception {
    declaringOwnUnavailabilityStores(
        "2026-11-16T09:00:00.0000004Z",
        "2026-11-16T09:00:00.0000006Z",
        "2026-11-16T09:00:00Z",
        "2026-11-16T09:00:00.000001Z");
  }

  // --- contracted minutes ---------------------------------------------------------------------

  /**
   * The contract already held is read back unchanged, which shows the refused one replaced nothing.
   */
  @Test
  void GivenMinutesThatOverflowADuration_WhenAnAdminSetsAContract_ThenRefusedAndTheOldOneKept()
      throws Exception {
    settingAContractIsRefusedAndTheOldOneKept(OVERFLOWING_MINUTES);
  }

  @Test
  void GivenMinutesThatUnderflowADuration_WhenAnAdminSetsAContract_ThenRefusedAndTheOldOneKept()
      throws Exception {
    settingAContractIsRefusedAndTheOldOneKept(UNDERFLOWING_MINUTES);
  }

  /**
   * The precision limit is the store's microsecond and no coarser: a span exactly one microsecond
   * long is ordinary, and is kept to the instant.
   */
  @Test
  void GivenEndsOneMicrosecondApart_WhenDeclaringOwnUnavailability_ThenItIsStoredAsSent()
      throws Exception {
    // Invented: ordinary instants, one microsecond apart.
    String start = "2026-11-17T09:00:00.000001Z";
    String end = "2026-11-17T09:00:00.000002Z";

    declaringOwnUnavailabilityStores(start, end, start, end);
  }

  // --- names ----------------------------------------------------------------------------------

  @Test
  void GivenANameHoldingANul_WhenAnAdminAddsAnEmployee_ThenRefusedUnstored() throws Exception {
    addingAnEmployeeIsRefusedUnstored(NAME_HOLDING_A_NUL, HALVES_OF_THE_UNSTORABLE_NAMES);
  }

  @Test
  void GivenANamePastAnIndexEntry_WhenAnAdminAddsAnEmployee_ThenRefusedUnstored() throws Exception {
    String name = incompressibleName(PAST_AN_INDEX_ENTRY_BYTES);

    addingAnEmployeeIsRefusedUnstored(name, whatALongNameMustNotRepeat(name));
  }

  /** The refusing edge, pinned as exactly as the accepting one. */
  @Test
  void GivenANameOneBytePastTheBound_WhenAnAdminAddsAnEmployee_ThenRefusedUnstored()
      throws Exception {
    String name = incompressibleName(StorableNames.MOST_BYTES + 1);

    addingAnEmployeeIsRefusedUnstored(name, whatALongNameMustNotRepeat(name));
  }

  /** The bound counts bytes, as the index does, not characters. */
  @Test
  void GivenANameWithinTheBoundInLettersButPastItInBytes_WhenAddingAnEmployee_ThenRefused()
      throws Exception {
    String name = TWO_BYTE_LETTER.repeat(StorableNames.MOST_BYTES / 2 + 1);

    addingAnEmployeeIsRefusedUnstored(name, whatALongNameMustNotRepeat(name));
  }

  /** The bound sits safely under the index entry: a name at it is stored, uncompressed. */
  @Test
  void GivenANameExactlyAtTheBound_WhenAnAdminAddsAnEmployee_ThenItIsStoredAsSent()
      throws Exception {
    addingAnEmployeeIsStoredAsSent(incompressibleName(StorableNames.MOST_BYTES));
  }

  @Test
  void GivenANameHoldingANul_WhenAnAdminDefinesAQualification_ThenRefusedUnstored()
      throws Exception {
    definingAQualificationIsRefusedUnstored(NAME_HOLDING_A_NUL, HALVES_OF_THE_UNSTORABLE_NAMES);
  }

  @Test
  void GivenANamePastAnIndexEntry_WhenAnAdminDefinesAQualification_ThenRefusedUnstored()
      throws Exception {
    String name = incompressibleName(PAST_AN_INDEX_ENTRY_BYTES);

    definingAQualificationIsRefusedUnstored(name, whatALongNameMustNotRepeat(name));
  }

  @Test
  void GivenANameOneBytePastTheBound_WhenAnAdminDefinesAQualification_ThenRefusedUnstored()
      throws Exception {
    String name = incompressibleName(StorableNames.MOST_BYTES + 1);

    definingAQualificationIsRefusedUnstored(name, whatALongNameMustNotRepeat(name));
  }

  @Test
  void GivenANameWithinTheBoundInLettersButPastItInBytes_WhenDefiningAQualification_ThenRefused()
      throws Exception {
    String name = TWO_BYTE_LETTER.repeat(StorableNames.MOST_BYTES / 2 + 1);

    definingAQualificationIsRefusedUnstored(name, whatALongNameMustNotRepeat(name));
  }

  @Test
  void GivenANameExactlyAtTheBound_WhenAnAdminDefinesAQualification_ThenItIsStoredAsSent()
      throws Exception {
    definingAQualificationIsStoredAsSent(incompressibleName(StorableNames.MOST_BYTES));
  }

  @Test
  void GivenANameHoldingAnUnpairedSurrogate_WhenAnAdminAddsAnEmployee_ThenRefusedUnstored()
      throws Exception {
    addingAnEmployeeIsRefusedUnstored(
        NAME_HOLDING_AN_UNPAIRED_SURROGATE, HALVES_OF_THE_UNSTORABLE_NAMES);
  }

  @Test
  void GivenANameHoldingAnUnpairedSurrogate_WhenAnAdminDefinesAQualification_ThenRefused()
      throws Exception {
    definingAQualificationIsRefusedUnstored(
        NAME_HOLDING_AN_UNPAIRED_SURROGATE, HALVES_OF_THE_UNSTORABLE_NAMES);
  }

  /** Only half a pair is unencodable: a whole one is a character like any other. */
  @Test
  void GivenANameHoldingAWholeSurrogatePair_WhenAnAdminAddsAnEmployee_ThenItIsStoredAsSent()
      throws Exception {
    addingAnEmployeeIsStoredAsSent(NAME_HOLDING_A_SURROGATE_PAIR);
  }

  @Test
  void GivenANameHoldingAWholeSurrogatePair_WhenDefiningAQualification_ThenItIsStoredAsSent()
      throws Exception {
    definingAQualificationIsStoredAsSent(NAME_HOLDING_A_SURROGATE_PAIR);
  }
}
