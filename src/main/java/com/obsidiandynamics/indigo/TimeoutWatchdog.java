package com.obsidiandynamics.indigo;

import java.util.*;
import java.util.concurrent.*;

final class TimeoutWatchdog extends Thread {
  /** Maximum sleep time. If the expiry time is longer, the sleep will be performed in a loop.
   *  This is also the default time that the timer sleeps for if it has no timeout tasks. */
  private static final long MAX_SLEEP_NANOS = 1_000_000_000l;
  
  /** Minimum sleep time. Below this threshold sleeping isn't worthwhile. */
  private static final long MIN_SLEEP_NANOS = 1_000_000l;
  
  /** Compensation for overhead of scheduling a timeout message with the dispatcher. */
  private static final long ADJ_NANOS = 500_000l;
  
  private final ActorSystem system;
  
  private final SortedSet<TimeoutTask> timeouts = new ConcurrentSkipListSet<>();
  
  private final Object sleepLock = new Object();
  
  private volatile long nextWake;
  
  private volatile boolean running = true;
  
  private volatile boolean forceTimeout;
  
  TimeoutWatchdog(ActorSystem system) {
    super("TimeoutWatchdog");
    this.system = system;
    setDaemon(true);
  }
  
  void terminate() {
    running = false;
    synchronized (sleepLock) {
      sleepLock.notify();
    }
    try {
      join();
    } catch (InterruptedException e) {}
  }  
  
  @Override
  public void run() {
    while (running) {
      synchronized (sleepLock) {
        if (! timeouts.isEmpty()) {
          delay(timeouts.first().getExpiresAt());
        } else {
          delay(System.nanoTime() + MAX_SLEEP_NANOS);
        }
      }

      cycle();
    }
  }
  
  void enqueue(TimeoutTask task) {
    timeouts.add(task);
    if (task.getExpiresAt() < nextWake) {
      synchronized (sleepLock) {
        if (task.getExpiresAt() < nextWake) {
          nextWake = task.getExpiresAt();
          sleepLock.notify();
        }
      }
    }
  }
  
  void dequeue(TimeoutTask task) {
    timeouts.remove(task);
  }
  
  private void delay(long until) {
    synchronized (sleepLock) {
      nextWake = until;
      while (running && ! forceTimeout) {
        final long timeDiff = Math.min(MAX_SLEEP_NANOS, nextWake - System.nanoTime() - ADJ_NANOS);
        try {
          if (timeDiff >= MIN_SLEEP_NANOS) {
            final long millis = timeDiff / 1_000_000l;
            final int nanos = (int) (timeDiff - millis * 1_000_000l);
            sleepLock.wait(millis, nanos);
          } else {
            break;
          }
        } catch (InterruptedException e) {}
      }
    }
  }
  
  void forceTimeout() {
    forceTimeout = true;
    synchronized (sleepLock) {
      sleepLock.notify();
    }
  }
  
  private void cycle() {
    if (! timeouts.isEmpty()) {
      try {
        final TimeoutTask first = timeouts.first();
        if (forceTimeout || System.nanoTime() >= first.getExpiresAt() - ADJ_NANOS) {
          timeouts.remove(first);
          if (! first.getRequest().isComplete()) {
            system.send(new Message(null, first.getActivation().self(), new TimeoutSignal(), first.getRequestId(), true));
          }
        }
      } catch (NoSuchElementException e) {} // in case the task was dequeued in the meantime
    }
  }
}
