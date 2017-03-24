package com.obsidiandynamics.indigo.activation;

import java.util.*;

import com.obsidiandynamics.indigo.*;
import com.obsidiandynamics.indigo.util.*;

public final class SyncQueueActivation extends Activation {
  private static final long PASSIVATION_AWAIT_DELAY = 10;
  
  private final Queue<Message> backlog = new ArrayDeque<>(1);
  
  private boolean on;
  
  private boolean passivationScheduled;
  
  private volatile boolean passivationComplete;
  
  public SyncQueueActivation(long id, ActorRef ref, ActorSystem system, ActorConfig actorConfig, Actor actor) {
    super(id, ref, system, actorConfig, actor);
  }
  
  @Override
  public boolean _enqueue(Message m) {
    for (;;) {
      final boolean noBacklog;
      final boolean noPending;
      final boolean awaitPassivation;
      final boolean throttleBacklog;
      synchronized (backlog) {
        noBacklog = ! on && backlog.isEmpty();
        noPending = pending.isEmpty();
        
        awaitPassivation = noBacklog && noPending && passivationScheduled;
        if (! awaitPassivation) {
          throttleBacklog = shouldThrottle();
          if (! throttleBacklog) {
            backlog.add(m);
          }
        } else {
          throttleBacklog = false;
        }
      }
      
      if (throttleBacklog) {
        Threads.throttle(this::shouldThrottle, actorConfig.backlogThrottleTries, actorConfig.backlogThrottleMillis);
        continue;
      }
    
      while (awaitPassivation) {
        if (passivationComplete) {
          return false;
        } else {
          Threads.sleep(PASSIVATION_AWAIT_DELAY);
        }
      }
      
      if (noBacklog && noPending) {
        system._incBusyActors();
      }
      
      if (noBacklog) {
        system._dispatch(this::run);
      }
      
      return true;
    }
  }
  
  private void run() {
    final Message[] messages;
    synchronized (backlog) {
      if (on) throw new IllegalStateException("Actor " + ref + " was already entered");

      messages = new Message[Math.min(actorConfig.bias, backlog.size())];
      for (int i = 0; i < messages.length; i++) {
        messages[i] = backlog.remove();
      }
      on = true;
    }

    ensureActivated();

    for (int i = 0; i < messages.length; i++) {
      processMessage(messages[i]);
    }

    final boolean noBacklog;
    final boolean noPending;
    synchronized (backlog) {
      if (! on) throw new IllegalStateException("Actor " + ref + " was already cleared");

      on = false;
      noBacklog = backlog.isEmpty();
      noPending = pending.isEmpty();
    }

    if (noBacklog) {
      if (noPending) {
        if (passivationScheduled) {
          actor.passivated(this);
          system._passivate(ref);
          passivationComplete = true;
        }
        system._decBusyActors();
      }
    } else {
      system._dispatch(this::run);
    }
  }
  
  private boolean shouldThrottle() {
    return backlog.size() >= actorConfig.backlogThrottleCapacity;
  }
  
  @Override
  public void passivate() {
    passivationScheduled = true;
  }
}
