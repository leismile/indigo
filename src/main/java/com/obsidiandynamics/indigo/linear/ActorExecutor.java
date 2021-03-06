package com.obsidiandynamics.indigo.linear;

import static com.obsidiandynamics.func.Functions.*;

import java.util.*;
import java.util.concurrent.*;

import com.obsidiandynamics.func.*;
import com.obsidiandynamics.indigo.*;

/**
 *  An implementation of {@link LinearExecutor} backed by the <b>Indigo</b> dynamic actor 
 *  system. <p>
 *  
 *  The caller can specify the desired level of parallelism by calling 
 *  {@link ExecutorOptions#setParallelism(int)} before instantiating the 
 *  {@link ActorExecutor}. <p>
 *  
 *  Ordinarily, submitted tasks <em>should</em> implement the {@link LinearTask} interface; 
 *  however, this is not mandatory by default. The {@link ActorExecutor} intentionally permits
 *  unordered tasks, thereby allowing the application to submit mixed workloads without
 *  either needing to explicitly assign a key for those tasks where execution order is 
 *  irrelevant, or forcing the application to employ a separate {@link ExecutorService}
 *  for those tasks. (Behind the scenes, this implementation actually derives a key using
 *  the task's hash code if the task fails to implement {@link LinearTask}, which may or may
 *  not be pseudo-random, depending on whether the task has chosen to override 
 *  {@link Object#hashCode()}.) Additionally, permitting non-linear tasks by default ensures
 *  that {@link ActorExecutor} remains strictly compliant with the broader {@link ExecutorService}
 *  interface semantics, and allows it to effectively substitute any other 
 *  {@link ExecutorService} implementation. <p>
 *  
 *  The default behaviour of allowing non-{@link LinearTask} submissions may be overridden by
 *  calling {@link ExecutorOptions#setAllowNonLinearTasks(boolean)}, in which case they will be
 *  denied with a {@link RejectedExecutionException} upon submission. This <em>should</em> be
 *  done in scenarios where the application wishes to mandate ordered execution and does not 
 *  foresee mixed task types. <p>
 *  
 *  The {@link ActorExecutor} is realised by the <b>Indigo</b> dynamic actor system, which
 *  provides highly efficient lock-free task queueing and non-blocking thread scheduling
 *  mechanics. Indigo is utilised partially; {@link ActorExecutor} does not require
 *  fully-fledged actor system semantics, such as actor-to-actor communication, message 
 *  exchange patterns, stashing, actor life-cycle management, throttling, dead-letter queues, 
 *  and so forth. <p>
 *  
 *  Behind the scenes, tasks are assigned to sporadic actors based on a task's key. Tasks are 
 *  packaged as Indigo messages and queued on the actors' local mailboxes. An ephemeral
 *  actor is created for every unique key; the actor is automatically passivated when its
 *  mailbox is emptied, and may be re-activated if new messages arrive. The actors act as agents, 
 *  executing the payload of the message, moving on to the next message, etc. In spite of the
 *  underlying complexity, the actor semantics are completely hidden from the application; 
 *  the latter perceives {@link ActorExecutor} as a yet another {@link ExecutorService}, 
 *  albeit one that can recognise and administer a partial ordering among submitted tasks.
 */
public final class ActorExecutor extends AbstractExecutorService implements LinearExecutor {
  /** Number of milliseconds to sleep in {@link #awaitTermination(long, TimeUnit)} while waiting
   *  for the shutdown signal. */
  private static final long AWAIT_SHUTTING_DOWN_SLEEP = 10;

  /** Submitted tasks that are still pending execution. */
  private final Set<LinearFutureTask<?>> pendingTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
  
  private int pendingTaskCount;
  
  private final Object stateLock = new Object();
  
  private final ActorSystem actorSystem;
  
  private final AgentListener agentListener;
  
  private final boolean allowNonLinearTasks;
  
  private volatile boolean shuttingDown;
  
  public ActorExecutor(ExecutorOptions options) {
    final ActorSystemConfig actorSystemConfig = new ActorSystemConfig();
    actorSystemConfig.parallelism = options.getParallelism();
    actorSystemConfig.executor = executorParams -> options.getExecutorFactory().apply(executorParams.parallelism);
    actorSystemConfig.defaultActorConfig.bias = options.getActorBias();
    actorSystemConfig.defaultActorConfig.ephemeral = true;
    actorSystemConfig.defaultActorConfig.backlogThrottleCapacity = Long.MAX_VALUE;
    actorSystemConfig.reaperPeriodMillis = 0;
    actorSystemConfig.ingressCount = 1;
    actorSystemConfig.enableTimeoutScheduler = false;
    actorSystemConfig.enableBackgroundScheduler = false;

    final LinearAgent agent = new LinearAgent(this);
    actorSystem = ActorSystem.create(actorSystemConfig)
        .on(LinearAgent.ROLE).cue(() -> agent);
    
    agentListener = options.getAgentListener();
    allowNonLinearTasks = options.isAllowNonLinearTasks();
  }
  
  @Override
  public LinearFutureTask<?> submit(Runnable task) {
    return Classes.cast(super.submit(task));
  }

  @Override
  public <T> LinearFutureTask<T> submit(Runnable task, T result) {
    return Classes.cast(super.submit(task, result));
  }

  @Override
  public <T> LinearFutureTask<T> submit(Callable<T> task) {
    return Classes.cast(super.submit(task));
  }

  @Override
  public void shutdown() {
    shuttingDown = true;
    
    if (isTerminated()) {
      dispose();
    }
  }

  @Override
  public List<Runnable> shutdownNow() {
    return Classes.cast(shutdownNow(true));
  }
  
  @Override
  public List<LinearFutureTask<?>> shutdownNow(boolean mayInterruptIfRunning) {
    final List<LinearFutureTask<?>> pendingTaskList = Collections.unmodifiableList(new ArrayList<>(pendingTasks));
    shutdown();
    for (LinearFutureTask<?> pendingTask : pendingTaskList) {
      pendingTask.cancel(mayInterruptIfRunning);
    }
    return pendingTaskList;
  }

  @Override
  public boolean isShutdown() {
    return shuttingDown;
  }

  @Override
  public boolean isTerminated() {
    synchronized (stateLock) {
      return shuttingDown && pendingTaskCount == 0;
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
    
    // first wait for the executor to be told to shut down (it's possible that this method may have
    // been called before a call to shutdown() or shutdownNow(), so we may need to wait)
    for (;;) {
      if (shuttingDown) {
        break;
      } else {
        final long timeRemaining = deadline - System.currentTimeMillis();
        if (timeRemaining > 0) {
          Thread.sleep(Math.min(timeRemaining, AWAIT_SHUTTING_DOWN_SLEEP));
        } else {
          return false;
        }
      }
    }
    
    // wait for any pending task to complete by blocking directly the task's get() method
    for (LinearFutureTask<?> pendingTask : pendingTasks) {
      final long timeRemaining = deadline - System.currentTimeMillis();
      try {
        pendingTask.awaitDone(timeRemaining, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        return false;
      }
    }
    
    // now that the tasks have all been completed, we just need to wait for the actor system to drain
    // (ensuring that any remaining passivation events get fired), which should be near-instantaneous
    actorSystem.drain(0);
    return true;
  }
  
  private void checkAllowNonLinearTasks() {
    mustBeTrue(allowNonLinearTasks, withMessage("Non-linear tasks are not allowed", RejectedExecutionException::new));
  }

  @Override
  public void execute(Runnable command) {
    if (command instanceof LinearFutureTask) {
      linearise((LinearFutureTask<?>) command);
    } else if (command instanceof LinearTask) {
      final LinearTask linearTask = (LinearTask) command;
      linearise(new LinearFutureTask<>(this, new FutureTask<>(command, null), linearTask.getKey()));
    } else {
      checkAllowNonLinearTasks();
      final String key = String.valueOf(command.hashCode());
      linearise(new LinearFutureTask<>(this, new FutureTask<>(command, null), key));
    }
  }
  
  @Override
  protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
    if (runnable instanceof LinearTask) {
      final LinearTask linearTask = (LinearTask) runnable;
      return new LinearFutureTask<>(this, new FutureTask<>(runnable, value), linearTask.getKey());
    } else {
      checkAllowNonLinearTasks();
      final String key = String.valueOf(runnable.hashCode());
      return new LinearFutureTask<>(this, new FutureTask<>(runnable, value), key);
    }
  }

  @Override
  protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
    if (callable instanceof LinearTask) {
      final LinearTask linearTask = (LinearTask) callable;
      return new LinearFutureTask<>(this, new FutureTask<>(callable), linearTask.getKey());
    } else {
      checkAllowNonLinearTasks();
      final String key = String.valueOf(callable.hashCode());
      return new LinearFutureTask<>(this, new FutureTask<>(callable), key);
    }
  }
  
  private void linearise(LinearFutureTask<?> task) {
    pendingTasks.add(task);
    
    final boolean enqueued;
    synchronized (stateLock) {
      enqueued = ! shuttingDown;
      if (enqueued) {
        pendingTaskCount++;
      }
    }
    
    if (enqueued) {
      final ActorRef actorRef = ActorRef.of(LinearAgent.ROLE, task.getKey());
      actorSystem.tell(actorRef, task);
    } else {
      pendingTasks.remove(task);
      task.cancel(false);
    }
  }
  
  /**
   *  Marks a given task as completed. <p>
   *  
   *  Because this method may be called from within the actor system (just after executing the task),
   *  it <em>must not block</em>. (It may also be called by application threads to cancel tasks.)
   *  
   *  @param task The task to complete.
   */
  void markCompleted(LinearFutureTask<?> task) {
    final boolean removed = pendingTasks.remove(task);
    mustBeTrue(removed, IllegalStateException::new);
    
    final boolean terminated;
    synchronized (stateLock) {
      final int currentPendingTaskCount = pendingTaskCount;
      pendingTaskCount = currentPendingTaskCount - 1;
      terminated = shuttingDown && currentPendingTaskCount == 1;
    }
    
    if (terminated) {
      dispose();
    }
  }
  
  @Override
  public int getPendingTasks() {
    synchronized (stateLock) {
      return pendingTaskCount;
    }
  }
  
  /**
   *  Disposes of the actor system. <p>
   *  
   *  This method may be called from within the actor system (by an agent, having executed the last task)
   *  and so it <em>must not block</em>.
   */
  private void dispose() {
    actorSystem.shutdownSilently(false);
  }
  
  ActorSystem getActorSystem() {
    return actorSystem;
  }
  
  void notifyAgentActivated(String key) {
    agentListener.agentActivated(key);
  }
  
  void notifyAgentPassivated(String key) {
    agentListener.agentPassivated(key);
  }
}
