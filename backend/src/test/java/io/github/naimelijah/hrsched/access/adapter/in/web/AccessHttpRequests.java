package io.github.naimelijah.hrsched.access.adapter.in.web;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Request scaffolding shared by the access HTTP tests.
 *
 * <p>Its own copy rather than a shared one, for the same reason {@code ActorFromHeaders} is
 * duplicated per context: sharing it would make one context's adapter package depend on another's.
 *
 * <p>Not named {@code *Test}, so surefire does not try to run it.
 */
final class AccessHttpRequests {

  private AccessHttpRequests() {}

  /**
   * The platform operator, who belongs to no organisation and therefore sends no {@code
   * X-Organisation}.
   *
   * @param request the request being built
   * @return the request, claiming to be the platform operator
   */
  static MockHttpServletRequestBuilder asOperator(MockHttpServletRequestBuilder request) {
    return request
        .header("X-User", UUID.randomUUID().toString())
        .header("X-Role", "OPERATOR")
        .contentType(MediaType.APPLICATION_JSON);
  }

  /**
   * @param request the request being built
   * @param organisation the organisation the caller belongs to
   * @param role the role being claimed
   * @return the request, carrying a complete tenant acting identity
   */
  static MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder request, String organisation, String role) {
    return request
        .header("X-Organisation", organisation)
        .header("X-User", UUID.randomUUID().toString())
        .header("X-Role", role)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
