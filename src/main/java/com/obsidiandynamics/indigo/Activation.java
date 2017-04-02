package com.obsidiandynamics.indigo;

import static com.obsidiandynamics.indigo.ActivationState.*;
import static com.obsidiandynamics.indigo.FaultType.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import com.obsidiandynamics.indigo.util.*;

public abstract class Activation {
  private final long id;
  
  protected final ActorRef ref;
  
  protected final ActorSystem system;
  
  protected final ActorConfig actorConfig;
  
  private final Actor actor;
  
  protected final Map<UUID, PendingRequest> pending = new HashMap<>();
  
  protected ActivationState state = PASSIVATED;
  
  private Stash stash;
  
  private boolean passivationScheduled;
  
  private long requestCounter = Crypto.machineRandom();
  
  private Object faultReason;
  
  protected Activation(long id, ActorRef ref, ActorSystem system, ActorConfig actorConfig, Actor actor) {
    this.id = id;
    this.ref = ref;
    this.system = system;
    this.actorConfig = actorConfig;
    this.actor = actor;
  }
  
  public abstract boolean enqueue(Message m);
  
  public final ActorRef self() {
    return ref;
  }
  
  public final void passivate() {
    passivationScheduled = true;
  }
  
  public final void fault(Object reason) {
    faultReason = reason;
  }
  
  @FunctionalInterface
  private interface MessageTarget {
    void send(Object body, UUID requestId);
  }
  
  public final class MessageBuilder {
    private final MessageTarget target;
    
    private int copies = 1;
    
    private Object requestBody;
    
    private long timeoutMillis;
    
    private Runnable onTimeout;
    
    private Consumer<Fault> onFault;
    
    MessageBuilder(MessageTarget target) {
      this.target = target;
    }
    
    public MessageBuilder times(int copies) {
      this.copies = copies;
      return this;
    }
    
    public void tell(Object body) {
      for (int i = copies; --i >= 0;) {
        target.send(body, null);
      }
    }
    
    public MessageBuilder ask(Object requestBody) {
      this.requestBody = requestBody;
      return this;
    }
    
    public MessageBuilder ask() {
      return ask(null);
    }
    
    public MessageBuilder await(long timeoutMillis) {
      this.timeoutMillis = timeoutMillis;
      return this;
    }
    
    public MessageBuilder onTimeout(Runnable onTimeout) {
      this.onTimeout = onTimeout;
      return this;
    }
    
    public MessageBuilder onTimeout(Consumer<Fault> onFault) {
      this.onFault = onFault;
      return this;
    }
    
    public void onResponse(Consumer<Message> onResponse) {
      if (timeoutMillis != 0 ^ onTimeout != null) {
        throw new IllegalArgumentException("Only one of the timeout time or handler has been set");
      }
      
      for (int i = copies; --i >= 0;) {
        final UUID requestId = new UUID(id, requestCounter++);
        final PendingRequest req = new PendingRequest(onResponse, onTimeout, onFault);
        pending.put(requestId, req);
        target.send(requestBody, requestId);
        
        if (timeoutMillis != 0) {
          final TimeoutTask timeoutTask = new TimeoutTask(System.nanoTime() + timeoutMillis * 1_000_000l,
                                                          requestId,
                                                          Activation.this,
                                                          req);
          req.setTimeoutTask(timeoutTask);
          system.getTimeoutWatchdog().enqueue(timeoutTask);
        }
      }
    }
    
    public void tell() {
      tell(null);
    }
  }
  
  public final MessageBuilder to(ActorRef to) {
    return new MessageBuilder((body, requestId) -> send(new Message(ref, to, body, requestId, false)));
  }
  
  public final class EgressBuilder<I, O> {
    private final Function<I, O> func;

    EgressBuilder(Function<I, O> func) {
      this.func = func;
    }

    @SuppressWarnings("unchecked")
    public MessageBuilder using(Executor executor) {
      return new MessageBuilder((body, requestId) -> {
        stashIfTransitioning();
        executor.execute(() -> {
          final O out;
          try {
            out = func.apply((I) body);
          } catch (Throwable t) {
            actorConfig.exceptionHandler.accept(system, t);
            return;
          }
          if (requestId != null) {
            final Message resp = new Message(null, ref, out, requestId, true);
            system.send(resp);
          }
        });
      });
    }
  }
  
  public final <I, O> EgressBuilder<I, O> egress(Function<I, O> func) {
    return new EgressBuilder<>(func);
  }
  
  public final <I> EgressBuilder<I, Void> egress(Consumer<I> consumer) {
    return new EgressBuilder<>(in -> {
      consumer.accept(in);
      return null;
    });
  }
  
  public final <I> EgressBuilder<I, Void> egress(Runnable runnable) {
    return new EgressBuilder<>(in -> {
      if (in != null) throw new IllegalArgumentException("Cannot pass a value to this egress lambda");
      
      runnable.run();
      return null;
    });
  }
  
  public final <O> EgressBuilder<Object, O> egress(Supplier<O> supplier) {
    return new EgressBuilder<>(in -> {
      if (in != null) throw new IllegalArgumentException("Cannot pass a value to this egress lambda");
      
      return supplier.get();
    });
  }
  
  public final MessageBuilder toSelf() {
    return to(ref);
  }
  
  public final MessageBuilder toSenderOf(Message m) {
    return to(m.from());
  }
  
  public final class ReplyBuilder {
    private final Message m;
    
    ReplyBuilder(Message m) { this.m = m; }
    
    public void tell() {
      tell(null);
    }
    
    public void tell(Object responseBody) {
      final boolean responding = m.requestId() != null;
      send(new Message(ref, m.from(), responseBody, m.requestId(), responding));
    }
  }
  
  public final ReplyBuilder reply(Message m) {
    return new ReplyBuilder(m);
  }
  
  public final class ForwardBuilder {
    private final Message m;
    
    ForwardBuilder(Message m) { this.m = m; }
    
    public void to(ActorRef to) {
      send(new Message(m.from(), to, m.body(), m.requestId(), m.isResponse()));
    }
  }
  
  public final ForwardBuilder forward(Message m) {
    return new ForwardBuilder(m);
  }
  
  public final void send(Message message) {
    if (message.requestId() != null) {
      stashIfTransitioning();
    }
    system.send(message);
  }
  
  private void stashIfTransitioning() {
    switch (state) {
      case ACTIVATING:
      case PASSIVATING:
        stash(Functions::alwaysTrue);
        break;
        
      default:
        break;
    }
  }
  
  private void cancelTimeout(PendingRequest req) {
    if (req.getTimeoutTask() != null) {
      system.getTimeoutWatchdog().dequeue(req.getTimeoutTask());
    }
  }
  
  private void clearPending() {
    for (PendingRequest req : pending.values()) {
      req.setComplete(true);
      cancelTimeout(req);
    }
    pending.clear();
  }
  
  private void raiseFault(FaultType type, Message originalMessage) {
    //TODO send to DLQ
    faultReason = null;
  }
  
  private boolean checkAndRaiseFault(FaultType type, Message originalMessage) {
    if (faultReason != null) {
      raiseFault(type, originalMessage);
      return true;
    } else {
      return false;
    }
  }
  
  private boolean ensureActivated(Message message) {
    switch (state) {
      case PASSIVATED:
        state = ACTIVATING;
        try {
          actor.activated(this);
        } catch (Throwable t) {
          fault(t);
          actorConfig.exceptionHandler.accept(system, t);
        }
        
        if (faultReason != null) {
          clearPending();
          raiseFault(ON_ACTIVATION, message);
          state = PASSIVATED;
          return false;
        } else if (pending.isEmpty()) {
          state = ACTIVATED;
          return true;
        } else {
          return true;
        }
        
      default:
        return true;
    }
  }
  
  protected final void passivateIfScheduled() {
    if (passivationScheduled && state == ACTIVATED && pending.isEmpty() && (stash == null || stash.messages.isEmpty())) {
      passivationScheduled = false;
      state = PASSIVATING;
      try {
        actor.passivated(this);
      } catch (Throwable t) {
        fault(t);
        actorConfig.exceptionHandler.accept(system, t);
      }
      
      if (faultReason != null) {
        clearPending();
        raiseFault(ON_PASSIVATION, null);
        state = ACTIVATED;
      } else if (pending.isEmpty()) {
        state = PASSIVATED;
      }
    }
  }
  
  protected final void processMessage(Message message) {
    if (message.isResponse()) {
      processSolicited(message);
    } else {
      if (! ensureActivated(message)) {
        return;
      }
      processUnsolicited(message);
    }
    
    if (stash != null && stash.unstashing) {
      try {
        if (! stash.messages.isEmpty()) {
          if (! ensureActivated(stash.messages.get(0))) {
            stash.messages.remove(0);
            return;
          }
          
          while (stash.unstashing && ! stash.messages.isEmpty()) {
            final Message stashed = stash.messages.remove(0);
            processUnsolicited(stashed);
          }
        }
      } finally {
        if (stash.messages.isEmpty()) {
          stash = null;
        }
      }
    }
  }
  
  private void processSolicited(Message message) {
    final PendingRequest req = pending.remove(message.requestId());
    final Object body = message.body();
    boolean fault = false;
    if (body instanceof Signal) {
      if (body instanceof Timeout) {
        if (req != null && ! req.isComplete()) {
          req.setComplete(true);
          try {
            req.getOnTimeout().run();
          } catch (Throwable t) {
            fault(t);
            actorConfig.exceptionHandler.accept(system, t);
          } finally {
            fault = checkAndRaiseFault(ON_TIMEOUT, message);
          }
        }
      } else if (body instanceof Fault) {
        if (req != null && ! req.isComplete()) {
          cancelTimeout(req);
          req.setComplete(true);
          if (req.getOnFault() != null) {
            try {
              req.getOnFault().accept((Fault) body);
            } catch (Throwable t) {
              fault(t);
              actorConfig.exceptionHandler.accept(system, t);
            } finally {
              fault = checkAndRaiseFault(ON_FAULT, message);
            }
          }
        }
      } else {
        throw new UnsupportedOperationException("Unsupported signal of type " + body.getClass().getName());
      }
    } else if (req != null) {
      cancelTimeout(req);
      req.setComplete(true);
      try {
        req.getOnResponse().accept(message);
      } catch (Throwable t) {
        fault(t);
        actorConfig.exceptionHandler.accept(system, t);
      } finally {
        fault = checkAndRaiseFault(ON_RESPONSE, message);
      }
    }
    
    if (fault) {
      switch (state) {
        case ACTIVATING:
          clearPending();
          unstash();
          if (stash != null && ! stash.messages.isEmpty()) {
            stash.messages.remove(0);
          }
          state = PASSIVATED;
          break;
          
        case PASSIVATING:
          clearPending();
          unstash();
          state = ACTIVATED;
          break;
          
        default:
          break;
      }      
    } else if (pending.isEmpty()) {
      switch (state) {
        case ACTIVATING:
          unstash();
          state = ACTIVATED;
          break;
          
        case PASSIVATING:
          unstash();
          state = PASSIVATED;
          break;
          
        default:
          break;
      }
    }
  }
  
  private void processUnsolicited(Message message) {
    if (stash != null && ! stash.unstashing && stash.filter.test(message)) {
      stash.messages.add(message);
    } else {
      try {
        actor.act(this, message);
      } catch (Throwable t) {
        fault(t);
        actorConfig.exceptionHandler.accept(system, t);
      } finally {
        checkAndRaiseFault(ON_ACT, message);
      }
    }
  }
  
  public final void stash(Predicate<Message> filter) {
    if (stash != null) {
      stash.unstashing = false;
    } else {
      stash = new Stash();
    }
    stash.filter = filter;
  }
  
  public final void unstash() {
    if (stash == null) return;
    stash.unstashing = true;
  }
  
  @Override
  public final String toString() {
    return "Activation [ref=" + ref + "]";
  }
}
