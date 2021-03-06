package com.obsidiandynamics.indigo.experimental;

import java.util.concurrent.*;

import com.obsidiandynamics.indigo.benchmark.*;
import com.obsidiandynamics.indigo.experimental.APActor.*;
import com.obsidiandynamics.indigo.util.*;
import com.obsidiandynamics.threads.*;

public final class APActorBenchmark {
  private static void benchmark() {
    final int threads = Runtime.getRuntime().availableProcessors() * 1;
    final int actors = threads * 1;
    final ForkJoinPool executor = (ForkJoinPool) Executors.newWorkStealingPool(threads);
    final long n = 10_000_000;

    final CountDownLatch latch = new CountDownLatch(actors);
    final long took = TestSupport.took(
      Parallel.blocking(actors, i -> {
        send(countingActor(n, executor, latch), n);
        TestSupport.await(latch);
      })
    );
    
    System.out.format("%,d took %,d s, %,d ops/sec\n",
                      actors * n, took / 1000, actors * n / took * 1000);
    executor.shutdown();
  }
  
  public static void main(String[] args) {
    System.out.println("bench started");
    for (int i = 0; i < 29; i++) {
      BenchmarkSupport.forceGC();
      benchmark();
    }
  }
  
  private static void send(Address address, long messages) {
    final String m = "hi";
    for (long i = 0; i < messages; i++) {
      if (i % 100_000_000 == 0) Thread.yield();
      address.tell(m);
    }
  }
  
  private static class Counter {
    long value;
  }
  
  private static Address countingActor(long messages, ForkJoinPool executor, CountDownLatch latch) {
    return APActor.create(address -> {
      final Counter counter = new Counter();
      counter.value = messages;
      return m -> {
        counter.value--;
        if (counter.value == 0) {
          latch.countDown();
        }
        return APActor.stay;
      };
    }, executor, 1000);
  }
}
