package com.github.wechat.ilink.sdk.core.executor;

import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import java.util.concurrent.*;

public class ExecutorManager implements AutoCloseable {
  private final ThreadPoolExecutor ioExecutor;
  private final ScheduledThreadPoolExecutor scheduler;
  // Single-thread executor for OnMessageListener dispatch: decouples listener processing from the
  // poll loop (a slow listener no longer stalls the next poll) while preserving message order.
  // See docs/adr/0001-no-reactive-incremental-dispatch-decoupling.md.
  private final ExecutorService dispatchExecutor;

  public ExecutorManager(ILinkConfig config) {
    dispatchExecutor =
        Executors.newSingleThreadExecutor(
            new ThreadFactory() {
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ilink-dispatch");
                t.setDaemon(true);
                return t;
              }
            });
    ioExecutor =
        new ThreadPoolExecutor(
            config.getIoCoreThreads(),
            config.getIoMaxThreads(),
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(config.getQueueCapacity()),
            new ThreadFactory() {
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ilink-io");
                t.setDaemon(true);
                return t;
              }
            },
            new ThreadPoolExecutor.CallerRunsPolicy());
    scheduler =
        new ScheduledThreadPoolExecutor(
            config.getSchedulerThreads(),
            new ThreadFactory() {
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ilink-scheduler");
                t.setDaemon(true);
                return t;
              }
            });
  }

  public ExecutorService ioExecutor() {
    return ioExecutor;
  }

  public ScheduledExecutorService scheduler() {
    return scheduler;
  }

  public ExecutorService dispatchExecutor() {
    return dispatchExecutor;
  }

  public void close() {
    ioExecutor.shutdownNow();
    scheduler.shutdownNow();
    dispatchExecutor.shutdownNow();
  }
}
