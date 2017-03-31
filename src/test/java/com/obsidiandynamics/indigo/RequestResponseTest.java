package com.obsidiandynamics.indigo;

import static junit.framework.TestCase.*;

import java.util.*;

import org.junit.*;

public final class RequestResponseTest implements TestSupport {
  private static final String DRIVER = "driver";
  private static final String ADDER = "adder";
  private static final String DONE_RUNS = "done_runs";

  @Test
  public void test() {
    logTestName();
    
    final int actors = 5;
    final int runs = 10;
    final Set<ActorRef> doneRuns = new HashSet<>();

    new TestActorSystemConfig() {}
    .define()
    .when(DRIVER).lambdaAsync(IntegerState::blank, (a, m, s) -> {
      a.to(ActorRef.of(ADDER)).ask(s.value).onResponse(r -> {
        final int res = r.body();
        if (res == runs) {
          a.to(ActorRef.of(DONE_RUNS)).tell();
        } else {
          assertEquals(s.value + 1, res);
          s.value = res;
          a.toSelf().tell();
        }
      });
    })
    .when(ADDER).lambda((a, m) -> a.reply(m).tell(m.<Integer>body() + 1))
    .when(DONE_RUNS).lambda(refCollector(doneRuns))
    .ingress().times(actors).act((a, i) -> a.to(ActorRef.of(DRIVER, String.valueOf(i))).tell())
    .shutdown();

    assertEquals(actors, doneRuns.size());
  }
}