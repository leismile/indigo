package com.obsidiandynamics.indigo;

import static junit.framework.TestCase.*;

import java.util.*;

import org.junit.*;

public final class TimeoutTest implements TestSupport {
  private static final String DRIVER = "driver";
  private static final String ECHO = "echo";
  private static final String DONE = "done";
  
  private static final int MIN_TIMEOUT = 10;
  private static final int MAX_TIMEOUT = 100;
  private static final int TIMEOUT_TOLERANCE = 10;
  
  private static final long generateRandomTimeout() {
    final int range = MAX_TIMEOUT - MIN_TIMEOUT;
    return MIN_TIMEOUT + (int) (Math.random() * range);
  }

  @Test
  public void test() {
    logTestName();
    
    final int actors = 100;
    final Map<ActorRef, Long> done = new HashMap<>();

    new ActorSystemConfig() {}
    .define()
    .when(DRIVER).lambda(a -> {
      final long startTime = System.currentTimeMillis();
      final long timeout = generateRandomTimeout();
      a.to(ActorRef.of(ECHO)).ask().await(timeout)
      .onTimeout(t -> {
        final long elapsed = System.currentTimeMillis() - startTime;
        final long timeDiff = Math.abs(elapsed - timeout);
        if (LOG) System.out.format("timed out %s, diff=%d, t/o=%d, actual=%d\n", a.self(), timeDiff, timeout, elapsed);
        
        t.to(ActorRef.of(DONE)).tell(timeDiff);
      })
      .onResponse(r -> {
        fail(String.format("Got unexpected response, timeout set to %d", timeout));
      });
    })
    .when(ECHO).lambda(a -> { /* do nothing, stalling the reply */ })
    .when(DONE).lambda(a -> done.put(a.message().from(), a.message().body()))
    .ingress(a -> {
      for (int i = 0; i < actors; i++) {
        a.to(ActorRef.of(DRIVER, i + "")).tell();
      }
    })
    .shutdown();

    assertEquals(actors, done.size());
    long totalDiff = 0;
    for (long diff : done.values()) {
      totalDiff += diff; 
    }
    final double avgDiff = (double) totalDiff / actors;
    if (LOG) LOG_STREAM.format("Average diff: %.1f\n", avgDiff);
    assertTrue(String.format("Average timeout threshold above tolerance: %.1f", avgDiff), 
               avgDiff <= TIMEOUT_TOLERANCE);
  }
}