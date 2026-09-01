package io.github.naimelijah.hrsched.configuration.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request's trace id on the response, so a failure can be quoted by the id its log lines
 * already carry.
 *
 * <p>Every log line a request produces carries {@code trace_id}, because Micrometer Tracing writes
 * it into the MDC and the ECS format publishes the MDC. Lines emitted outside a request - startup,
 * shutdown - carry none, because there is no span for them to belong to. Either way it is worth
 * nothing to the person actually looking at the error, who has a browser and no log access. This
 * closes that gap: the identifier shown on screen is the identifier to search for.
 *
 * <p>The header is set before the chain runs, because a response that has begun streaming can no
 * longer take one.
 *
 * <p>When no span is active the header is simply absent. Inventing an id here would be worse than
 * omitting it: it would name a trace that appears in no log line, and send whoever quoted it
 * looking for something that was never written.
 *
 * <p>It reads no request header and knows nothing about who is calling, so unlike {@code
 * ActorFromHeaders} it survives the arrival of authentication untouched.
 *
 * <p>Ordered explicitly, and the number is load-bearing in both directions. It must run
 * <em>after</em> Boot's observation filter, which opens the span this reads and which {@code
 * WebMvcObservationAutoConfiguration} registers at {@code HIGHEST_PRECEDENCE + 1}: ordered any
 * earlier, {@code currentSpan()} is null and the header silently disappears — which is exactly what
 * happened when this was first ordered, and what {@code TraceIdHttpTest} caught. It must run
 * <em>before</em> {@code KnownOrganisationFilter}, so a request refused there still carries the id
 * the person who was refused has to quote.
 */
@Component
@Order(TraceIdResponseFilter.ORDER)
public class TraceIdResponseFilter extends OncePerRequestFilter {

  /** Immediately after the observation filter that opens the span. See the class comment. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

  /**
   * Chosen over W3C {@code traceresponse}, which is still a draft. Switching is one constant and
   * one frontend edit; see ADR-016.
   */
  public static final String TRACE_ID_HEADER = "X-Trace-Id";

  private final Tracer tracer;

  /**
   * @param tracer the tracer whose current span identifies this request
   */
  public TraceIdResponseFilter(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Span current = tracer.currentSpan();
    if (current != null) {
      response.setHeader(TRACE_ID_HEADER, current.context().traceId());
    }
    chain.doFilter(request, response);
  }
}
