package net.jodah.failsafe;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.jodah.failsafe.internal.util.Assert;
import net.jodah.failsafe.internal.util.ReentrantCircuit;

/**
 * The future result of an asynchronous Failsafe execution.
 * 
 * @author Jonathan Halterman
 * @param <T> result type
 */
public class FailsafeFuture<T> implements Future<T> {
  private final ReentrantCircuit circuit = new ReentrantCircuit();
  private final ListenerBindings<?, T> listeners;
  private final java.util.concurrent.CompletableFuture<T> completableFuture;
  private ExecutionContext context;

  // Mutable state
  private volatile Future<T> delegate;
  private volatile boolean done;
  private volatile boolean cancelled;
  private volatile T result;
  private volatile Throwable failure;

  FailsafeFuture(ListenerBindings<?, T> listeners) {
    this.listeners = listeners;
    this.completableFuture = null;
    circuit.open();
  }

  FailsafeFuture(java.util.concurrent.CompletableFuture<T> future, ListenerBindings<?, T> listeners) {
    this.completableFuture = future;
    this.listeners = listeners;
    circuit.open();
  }

  @Override
  public synchronized boolean cancel(boolean mayInterruptIfRunning) {
    boolean result = delegate.cancel(mayInterruptIfRunning);
    cancelled = true;
    circuit.close();
    return result;
  }

  @Override
  public T get() throws InterruptedException, ExecutionException {
    circuit.await();
    if (failure != null)
      throw new ExecutionException(failure);
    return result;
  }

  /**
   * @throws NullPointerException if {@code unit} is null
   * @throws IllegalArgumentException if {@code timeout} is < 0
   */
  @Override
  public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
    Assert.isTrue(timeout >= 0, "timeout cannot be negative");
    if (!circuit.await(timeout, Assert.notNull(unit, "unit")))
      throw new TimeoutException();
    if (failure != null)
      throw new ExecutionException(failure);
    return result;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public boolean isDone() {
    return done;
  }

  synchronized void complete(T result, Throwable failure, boolean success) {
    this.result = result;
    this.failure = failure;
    done = true;

    listeners.complete(result, failure, context, success);
    if (completableFuture != null)
      completeFuture();

    circuit.close();
  }

  void inject(ExecutionContext context) {
    this.context = context;
  }

  void setFuture(Future<T> delegate) {
    this.delegate = delegate;
  }

  private void completeFuture() {
    if (failure == null)
      completableFuture.complete(result);
    else
      completableFuture.completeExceptionally(failure);
  }
}
