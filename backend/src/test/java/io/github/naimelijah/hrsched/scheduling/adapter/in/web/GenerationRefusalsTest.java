package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationsStoppedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.TooManyGenerationsException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Every run busy, or the runner stopped, is answered 503 (ADR-046): nothing about the request was
 * wrong, and asking again shortly may succeed. Specified on the handler and on a controller of its
 * own, because the suite's shared context runs generations on the calling thread, so it is never
 * busy, and its runner must never be stopped: every later test would find it so.
 */
class GenerationRefusalsTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final SchedulingExceptionHandler handler = new SchedulingExceptionHandler(meters);

  @Test
  void GivenEveryRunBusy_WhenRefused_ThenItIsAServiceUnavailableAndItIsCounted() {
    ProblemDetail problem = handler.handleTooManyGenerations(new TooManyGenerationsException(2));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    assertThat(problem.getTitle()).isEqualTo("Generation is busy");
    assertThat(problem.getType()).hasToString("urn:hrsched:problem:generation-is-busy");
    assertThat(meters.get("hrsched.generations.busy").counter().count()).isEqualTo(1.0);
  }

  /**
   * The controller and this context's advice, over HTTP, with a planning use case whose runner has
   * stopped. The week asked for is invented; nothing reads it.
   */
  @Test
  void GivenARunnerThatHasStopped_WhenAGenerationIsAskedForOverHttp_ThenItIsServiceUnavailable()
      throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(
                new SchedulingController(null, null, null, stoppedPlanning(), null))
            .setControllerAdvice(handler)
            .setCustomArgumentResolvers(new ActingAsAScheduler())
            .build();

    mockMvc
        .perform(
            post("/api/rosters/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"start\":\"2026-10-05T00:00:00Z\",\"end\":\"2026-10-12T00:00:00Z\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Generation is unavailable"))
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:generation-is-unavailable"));
    assertThat(meters.find("hrsched.generations.busy").counter()).isNull();
  }

  /** Planning whose every generation is refused as a stopped runner refuses it; nothing else. */
  private static PlanRostersUseCase stoppedPlanning() {
    return (PlanRostersUseCase)
        Proxy.newProxyInstance(
            PlanRostersUseCase.class.getClassLoader(),
            new Class<?>[] {PlanRostersUseCase.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("startGeneration")) {
                throw new GenerationsStoppedException(
                    new IllegalStateException("an invented stop"));
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  /** Stands in for the token: the caller is a Scheduler of an invented organisation. */
  private static final class ActingAsAScheduler implements HandlerMethodArgumentResolver {

    private final Actor scheduler =
        new TenantActor(UserId.newId(), OrganisationId.newId(), Role.SCHEDULER);

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer container,
        NativeWebRequest request,
        WebDataBinderFactory binders) {
      return scheduler;
    }
  }
}
