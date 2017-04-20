package com.obsidiandynamics.indigo;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public interface TestSupport {
  static final boolean LOG = false;
  static final PrintStream LOG_STREAM = System.out;
  
  default void logTestName() {
    log("Testing %s\n", getClass().getSimpleName());
  }
  
  default void log(String format, Object ... args) {
    if (LOG) LOG_STREAM.printf(format, args);
  }
  
  default BiConsumer<Activation, Message> refCollector(Set<ActorRef> set) {
    return (a, m) -> {
      log("Finished %s\n", m.from());
      set.add(m.from());
    };
  }
  
  default Consumer<Activation> tell(String role) {
    return a -> a.to(ActorRef.of(role)).tell();
  }
  
  static long took(Runnable r) {
    final long started = System.nanoTime();
    r.run();
    final long took = System.nanoTime() - started;
    return took / 1_000_000l;
  }
  
  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }  
  
  static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  static void await(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (BrokenBarrierException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  static int countFaults(FaultType type, Queue<Fault> deadLetterQueue) {
    int count = 0;
    for (Fault fault : deadLetterQueue) {
      if (fault.getType() == type) {
        count++;
      }
    }
    return count;
  }
}
