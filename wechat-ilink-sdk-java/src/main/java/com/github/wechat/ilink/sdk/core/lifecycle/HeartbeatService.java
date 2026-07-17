package com.github.wechat.ilink.sdk.core.lifecycle;

import com.github.wechat.ilink.sdk.core.listener.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeartbeatService implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);
  private final ScheduledExecutorService scheduler;
  private final long intervalMs;
  private final HealthChecker healthChecker;
  private final ListenerRegistry registry;
  private ScheduledFuture<?> future;

  public HeartbeatService(
      ScheduledExecutorService scheduler,
      long intervalMs,
      HealthChecker healthChecker,
      ListenerRegistry registry) {
    this.scheduler = scheduler;
    this.intervalMs = intervalMs;
    this.healthChecker = healthChecker;
    this.registry = registry;
  }

  public synchronized void start() {
    if (future != null && !future.isDone()) return;
    future =
        scheduler.scheduleWithFixedDelay(
                // 逐监听器 try/catch 隔离：一个监听器抛异常不得中断其余监听器，也不得把
                // success 通知误报成 failure（此前 success 循环里的异常会落进外层 catch）。
                () -> {
                  try {
                    healthChecker.check();
                  } catch (Throwable e) {
                    for (OnHeartbeatListener l : registry.getHeartbeatListeners()) {
                      try {
                        l.onHeartbeatFailure(e);
                      } catch (RuntimeException ex) {
                        log.error("OnHeartbeatListener threw during onHeartbeatFailure", ex);
                      }
                    }
                    return;
                  }
                  for (OnHeartbeatListener l : registry.getHeartbeatListeners()) {
                    try {
                      l.onHeartbeatSuccess();
                    } catch (RuntimeException ex) {
                      log.error("OnHeartbeatListener threw during onHeartbeatSuccess", ex);
                    }
                  }
                },
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS);
  }

  public synchronized void stop() {
    if (future != null) {
      future.cancel(true);
      future = null;
    }
  }

  public void close() {
    stop();
  }
}
