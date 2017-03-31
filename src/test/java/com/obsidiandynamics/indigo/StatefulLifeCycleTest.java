package com.obsidiandynamics.indigo;

import static junit.framework.TestCase.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.*;

public final class StatefulLifeCycleTest implements TestSupport {
  private static final int SCALE = 1;
  
  private static final String TARGET = "target";
  private static final String EXTERNAL = "external";
  
  private static class MockDB {
    private final Map<ActorRef, IntegerState> states = new ConcurrentHashMap<>();
    
    IntegerState get(ActorRef ref) {
      if (! states.containsKey(ref)) {
        final IntegerState state = new IntegerState();
        states.put(ref, state);
        return state;
      } else {
        return states.get(ref);
      }
    }
    
    void put(ActorRef ref, IntegerState state) {
      states.put(ref, state);
    }
  }

  @Test
  public void testSyncUnbiased() {
    test(false, 1_000 * SCALE, 1);
  }

  @Test
  public void testSyncBiased() {
    test(false, 1_000 * SCALE, 10);
  }

  @Test
  public void testAsyncUnbiased() {
    test(true, 1_000 * SCALE, 1);
  }

  @Test
  public void testAsyncBiased() {
    test(true, 1_000 * SCALE, 10);
  }

  private void test(boolean async, int n, int actorBias) {
    logTestName();
    
    final AtomicBoolean activating = new AtomicBoolean();
    final AtomicBoolean activated = new AtomicBoolean();
    final AtomicBoolean passivating = new AtomicBoolean();
    final AtomicBoolean passivated = new AtomicBoolean(true);
    
    final AtomicInteger activationCount = new AtomicInteger();
    final AtomicInteger actCount = new AtomicInteger();
    final AtomicInteger passivationCount = new AtomicInteger();
    
    final MockDB db = new MockDB();
    final Executor external = r -> new Thread(r, EXTERNAL).start();
    
    new TestActorSystemConfig() {{
      parallelism = Runtime.getRuntime().availableProcessors();
      defaultActorConfig = new ActorConfig() {{
        bias = actorBias;
        backlogThrottleCapacity = 10;
      }};
    }}
    .define()
    .when(TARGET)
    .use(StatefulLambdaActor
         .<IntegerState>builder()
         .activated(a -> {
           log("activating\n");
           assertFalse(activating.get());
           assertFalse(activated.get());
           assertFalse(passivating.get());
           assertTrue(passivated.get());
           activating.set(true);
           passivated.set(false);
           
           if (async) {
             final IntegerState s = new IntegerState();
             a.egress(() -> db.get(a.self()))
             .using(external)
             .ask()
             .onResponse(r -> {
               final IntegerState saved = r.body();
               log("activated %s\n", saved);
               assertTrue(activating.get());
               assertFalse(activated.get());
               assertFalse(passivating.get());
               assertFalse(passivated.get());
               activating.set(false);
               activated.set(true);
               passivated.set(false);
               activationCount.incrementAndGet();
               s.value = saved.value;
             });
             return s;
           } else {
             final IntegerState saved = db.get(a.self());
             log("activated %s\n", saved);
             assertTrue(activating.get());
             assertFalse(activated.get());
             assertFalse(passivating.get());
             assertFalse(passivated.get());
             activating.set(false);
             activated.set(true);
             passivated.set(false);
             activationCount.incrementAndGet();
             return saved;
           }
         })
         .act((a, m, s) -> {
           log("act\n");
           assertFalse(activating.get());
           assertTrue(activated.get());
           assertFalse(passivating.get());
           assertFalse(passivated.get());
           actCount.incrementAndGet();
           
           final int body = m.body();
           assertEquals(s.value + 1, body);
           s.value = body;

           a.passivate();
         })
         .passivated((a, s) -> {
           log("passivating %s\n", s);
           assertFalse(activating.get());
           assertTrue(activated.get());
           assertFalse(passivating.get());
           assertFalse(passivated.get());
           activated.set(false);
           passivating.set(true);
           
           if (async) {
             a.egress(() -> db.put(a.self(), s))
             .using(external)
             .ask()
             .onResponse(r -> {
               log("passivated\n");
               assertFalse(activating.get());
               assertFalse(activated.get());
               assertTrue(passivating.get());
               assertFalse(passivated.get());
               passivating.set(false);
               passivated.set(true);
               passivationCount.incrementAndGet();
             });
           } else {
             log("passivated\n");
             assertFalse(activating.get());
             assertFalse(activated.get());
             assertTrue(passivating.get());
             assertFalse(passivated.get());
             passivating.set(false);
             passivated.set(true);
             passivationCount.incrementAndGet();
             db.put(a.self(), s);
           }
         }))
    .ingress().act(a -> {
      for (int i = 1; i <= n; i++) {
        a.to(ActorRef.of(TARGET)).tell(i);
      }
    })
    .shutdown();

    assertEquals(n, db.get(ActorRef.of(TARGET)).value);

    assertFalse(activating.get());
    assertFalse(activated.get());
    assertFalse(passivating.get());
    assertTrue(passivated.get());
    
    assertTrue(activationCount.get() == passivationCount.get());
    assertTrue(activationCount.get() >= 1);
    assertTrue(activationCount.get() <= n);
    assertEquals(n, actCount.get());
    assertTrue(passivationCount.get() >= 1);
    
    log("passivations: %d\n", passivationCount.get());
  }
}
