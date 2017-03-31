package com.obsidiandynamics.indigo;

import java.util.concurrent.atomic.*;

import org.junit.runner.*;
import org.junit.runner.notification.*;

public final class AllTestsLoop {
  public static void main(String[] args) {
    final int n = 100;
    
    test(n, ActorConfig.ActivationChoice.NODE_QUEUE);
    test(n, ActorConfig.ActivationChoice.SYNC_QUEUE);
  }
  
  private static void test(int n, ActorConfig.ActivationChoice activationChoice) {
    System.out.format("_\nTesting with %s\n", activationChoice);
    System.setProperty(ActorConfig.Key.ACTIVATION_FACTORY, activationChoice.name());
    
    final int threads = Runtime.getRuntime().availableProcessors() * 4;
    final boolean logFinished = false;
    final boolean logRuns = true;
    
    System.setProperty("indigo.TimeoutTest.timeoutTolerance", String.valueOf(50));
    
    System.out.format("Running %d parallel runs using %d threads\n", n * threads, threads);
    final AtomicLong totalTests = new AtomicLong();
    final long took = TestSupport.took(() -> {
      for (int i = 1; i <= n; i++) {
        ParallelJob.blocking(threads, t -> {
          final Computer computer = new Computer();
          final JUnitCore core = new JUnitCore();
          core.addListener(new RunListener() {
            @Override public void testFinished(Description description) throws Exception {
              if (logFinished) System.out.println("Finished: " + description);
              totalTests.incrementAndGet();
            }
            
            @Override public void testFailure(Failure failure) throws Exception {
              System.err.println("Failed: " + failure);
            }
          });
          core.run(computer, AllTests.class);
        }).run();
        
        if (logRuns) {
          System.out.format("Finished run %,d: %,d active threads, free mem: %,.0f MB\n", 
                            i, Thread.activeCount(), Runtime.getRuntime().freeMemory() / Math.pow(2, 20));
        }
      }
    });
    System.out.format("Complete: %,d tests took %d s, %.1f tests/s\n", 
                      totalTests.get(), took / 1000, totalTests.get() * 1000f / took);
  }
}
