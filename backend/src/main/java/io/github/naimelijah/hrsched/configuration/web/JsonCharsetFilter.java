package io.github.naimelijah.hrsched.configuration.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Refuses a JSON request body declared in any charset but UTF-8, with a 415, before it is read.
 *
 * <p>RFC 8259 section 8.1 requires JSON exchanged between systems to be UTF-8, and the body limit
 * depends on it. {@code hrsched.web.max-json-document-bytes} is a number of bytes, and Jackson
 * counts bytes only when it reads them itself, which Spring's converter lets it do for a Unicode
 * charset alone. Any other declared charset is decoded first and Jackson counts the characters, so
 * ISO-2022-JP, whose escape sequences decode to nothing, carries a body far over the limit past it,
 * on sign-in, which takes no token (issue #250). A declared UTF-16 or UTF-32 is refused too: the
 * RFC allows neither, and no client of this API sends one.
 *
 * <p>A filter rather than a narrower converter. Spring's Jackson converter reads every charset, and
 * changing that means replacing the converter Boot configures, for every route; this needs no
 * converter of its own and refuses before anything is read. It runs last, after Spring Security's
 * chain, so a route the chain refuses is refused there first, {@code denyAll} included, and this
 * refusal carries the chain's response headers like any other answer.
 *
 * <p>Only a JSON type is judged: {@code application/json} and every {@code application/*+json}, the
 * types the converter reads. A {@code Content-Type} that does not parse is passed on untouched,
 * because Spring already answers it 415 without reading the body, and no other type reaches
 * Jackson.
 *
 * <p>Nothing is logged or counted: a body in the wrong charset is not a refusal of anything the
 * application decided (ADR-018). The detail is fixed and repeats nothing sent.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class JsonCharsetFilter extends OncePerRequestFilter {

  private static final String JSON_SUFFIX = "json";

  private final ObjectMapper json;

  /**
   * @param json writes the problem detail
   */
  public JsonCharsetFilter(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json must not be null");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (declaresJsonInAnotherCharset(request.getContentType())) {
      refuse(response);
      return;
    }
    chain.doFilter(request, response);
  }

  private static boolean declaresJsonInAnotherCharset(String contentType) {
    if (contentType == null) {
      return false;
    }
    MediaType type;
    try {
      type = MediaType.parseMediaType(contentType);
    } catch (InvalidMediaTypeException unparsable) {
      // Not this filter's to answer: Spring refuses it 415 itself, before reading the body.
      return false;
    }
    Charset charset = type.getCharset();
    return isJson(type) && charset != null && !StandardCharsets.UTF_8.equals(charset);
  }

  private static boolean isJson(MediaType type) {
    return MediaType.APPLICATION_JSON.getType().equals(type.getType())
        && (MediaType.APPLICATION_JSON.getSubtype().equals(type.getSubtype())
            || JSON_SUFFIX.equals(type.getSubtypeSuffix()));
  }

  private void refuse(HttpServletResponse response) throws IOException {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "A JSON request body must be encoded in UTF-8.");
    problem.setTitle("Unsupported media type");

    response.setStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(json.writeValueAsString(problem));
  }
}
