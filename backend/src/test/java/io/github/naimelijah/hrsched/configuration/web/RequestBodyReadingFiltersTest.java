package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import jakarta.servlet.Filter;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.filter.FormContentFilter;
import org.springframework.web.filter.HiddenHttpMethodFilter;

/**
 * Specification that no servlet filter reads a request body before the security chain has decided
 * on the request (issue #250).
 *
 * <p>Boot registers its form filters far ahead of the chain. {@link FormContentFilter} reads a form
 * body sent with PUT, PATCH or DELETE whole, with no counterpart to the JSON document limit, so
 * whoever sent it, signed in or not, could make the server hold all of it before any rule ran. No
 * route takes form content, so neither filter has anything to do here, and the JSON limit, the
 * charset filter and multipart being off are only a bound on a body if nothing reads one earlier.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class RequestBodyReadingFiltersTest {

  @Autowired private ApplicationContext context;

  @Test
  void GivenTheApplication_WhenItsServletFiltersAreListed_ThenNoneReadsAFormBody() {
    Collection<Filter> filters = context.getBeansOfType(Filter.class).values();

    assertThat(filters)
        .describedAs("servlet filters that read a form body ahead of the security chain")
        .noneMatch(FormContentFilter.class::isInstance)
        .noneMatch(HiddenHttpMethodFilter.class::isInstance);
  }
}
