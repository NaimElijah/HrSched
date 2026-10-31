package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Specification of the unmapped-path refusal Spring raises only when its static resources are
 * switched off, which no test's context does.
 *
 * <p>With {@code spring.web.resources.add-mappings=false}, a common hardening for a service that
 * serves no files, a path no controller maps raises {@code NoHandlerFoundException} rather than the
 * {@code NoResourceFoundException} {@code FrameworkRefusalHttpTest} reaches over HTTP. Unhandled,
 * it would answer with a status and no body again. Driven through the method Spring would pick for
 * it from this advice, so a type the advice does not name fails on that lookup. The path is
 * invented.
 */
class RequestRoutingExceptionHandlerTest {

  /** Invented: a path no controller maps. */
  private static final String INVENTED_PATH = "/api/invented-unmapped-path";

  @Test
  void GivenNoHandlerForThePath_WhenSpringRoutesTheRefusal_ThenItIsTheNotFoundProblem()
      throws Exception {
    NoHandlerFoundException refusal =
        new NoHandlerFoundException("GET", INVENTED_PATH, new HttpHeaders());
    Method routed =
        new ExceptionHandlerMethodResolver(RequestRoutingExceptionHandler.class)
            .resolveMethodByThrowable(refusal);
    assertThat(routed).describedAs("the handler Spring routes the refusal to").isNotNull();

    ResponseEntity<?> answer =
        (ResponseEntity<?>) routed.invoke(new RequestRoutingExceptionHandler(), refusal);

    assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(answer.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail problem = (ProblemDetail) answer.getBody();
    assertThat(problem).isNotNull();
    assertThat(problem.getType()).hasToString("urn:hrsched:problem:not-found");
    assertThat(problem.getTitle()).isEqualTo("Not found");
    assertThat(problem.getDetail()).isEqualTo("No such resource.").doesNotContain(INVENTED_PATH);
  }
}
