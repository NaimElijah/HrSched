package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The edge of what the body limit accepts, without starting an application for it.
 *
 * <p>{@code JsonDocumentLimitStartupTest} shows that a limit the server's form limit cannot hold
 * stops a start. This pins the edge itself: the largest value an {@code int} holds is still a limit
 * both Jackson and Tomcat read as one, so it is accepted.
 */
class RequestBodyLimitPropertiesTest {

  @Test
  void GivenTheLargestLimitAnIntHolds_WhenConstructed_ThenItIsAccepted() {
    assertThat(new RequestBodyLimitProperties(Integer.MAX_VALUE).maxJsonDocumentBytes())
        .isEqualTo(Integer.MAX_VALUE);
  }
}
