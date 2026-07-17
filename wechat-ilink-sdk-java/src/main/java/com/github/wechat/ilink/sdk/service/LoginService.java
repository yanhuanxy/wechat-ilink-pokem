package com.github.wechat.ilink.sdk.service;

import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.exception.ConnectFailedException;
import com.github.wechat.ilink.sdk.core.http.HttpClientFacade;
import com.github.wechat.ilink.sdk.core.login.*;
import com.github.wechat.ilink.sdk.core.serializer.Serializer;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class LoginService implements AutoCloseable {
  private static final String BASE_URL = "https://ilinkai.weixin.qq.com";
  private static final String GET_QRCODE_URL = BASE_URL + "/ilink/bot/get_bot_qrcode?bot_type=3";
  private static final String GET_QRCODE_STATUS_URL = BASE_URL + "/ilink/bot/get_qrcode_status";
  private final ILinkConfig config;
  private final Serializer serializer;
  private final HttpClientFacade httpClientFacade;
  private final ExecutorService pollingExecutor;
  private final AtomicReference<CompletableFuture<LoginContext>> currentLoginFuture =
      new AtomicReference<CompletableFuture<LoginContext>>();
  // CompletableFuture.cancel(true) 不会中断 supplyAsync 里正在执行的任务，轮询循环必须靠
  // 这个显式取消标志退出；每次 startLoginPolling 持有一个新标志。
  private final AtomicReference<AtomicBoolean> currentCancelFlag =
      new AtomicReference<AtomicBoolean>();

  public LoginService(
      ILinkConfig config,
      Serializer serializer,
      HttpClientFacade httpClientFacade,
      ExecutorService pollingExecutor) {
    this.config = config;
    this.serializer = serializer;
    this.httpClientFacade = httpClientFacade;
    this.pollingExecutor = pollingExecutor;
  }

  public QRCodeResponse getQRCode() throws IOException {
    Map<String, String> headers = new HashMap<String, String>();
    if (config.getRouteTag() != null && !config.getRouteTag().trim().isEmpty())
    headers.put("SKRouteTag", config.getRouteTag());
    String json = httpClientFacade.get(GET_QRCODE_URL, headers);
    java.util.Map map = serializer.deserialize(json, java.util.Map.class);
    return new QRCodeResponse((String) map.get("qrcode"), (String) map.get("qrcode_img_content"));
  }

  public CompletableFuture<LoginContext> startLoginPolling(
      final String qrcode,
      final LoginStatus loginStatus,
      final AtomicReference<LoginContext> loginContextRef) {
    cancelCurrentLogin();
    loginStatus.reset();
    loginStatus.toWaiting();
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    CompletableFuture<LoginContext> future =
        CompletableFuture.supplyAsync(
                () -> {
                  long deadline = System.currentTimeMillis() + config.getLoginTimeoutMs();
                  while (!cancelled.get() && !Thread.currentThread().isInterrupted()) {
                    if (System.currentTimeMillis() > deadline) {
                      loginStatus.toError("login timeout");
                      throw new ConnectFailedException("login timeout");
                    }
                    try {
                      Map<String, String> headers = new HashMap<String, String>();
                      headers.put("iLink-App-ClientVersion", "1");
                      if (config.getRouteTag() != null && !config.getRouteTag().trim().isEmpty())
                        headers.put("SKRouteTag", config.getRouteTag());
                      String json =
                          httpClientFacade.get(GET_QRCODE_STATUS_URL + "?qrcode=" + qrcode, headers);
                      Map m = serializer.deserialize(json, Map.class);
                      LoginStatusResponse r = new LoginStatusResponse((String) m.get("status"));
                      r.setBotToken((String) m.get("bot_token"));
                      r.setBotId((String) m.get("ilink_bot_id"));
                      r.setUserId((String) m.get("ilink_user_id"));
                      r.setBaseUrl((String) m.get("baseurl"));
                      if (r.isWaiting()) {
                        sleepPollInterval();
                        continue;
                      }
                      if (r.isScanned()) {
                        loginStatus.toScanned();
                        sleepPollInterval();
                        continue;
                      }
                      if (r.isExpired()) {
                        loginStatus.toExpired();
                        throw new ConnectFailedException("qrcode expired");
                      }
                      if (r.isConfirmed()) {
                        LoginContext ctx =
                            new LoginContext(
                                r.getBotToken(), r.getUserId(), r.getBotId(), r.getBaseUrl());
                        loginContextRef.set(ctx);
                        loginStatus.toLoggedIn();
                        return ctx;
                      }
                    } catch (IOException e) {
                      throw new ConnectFailedException("login polling failed", e);
                    }
                  }
                  throw new ConnectFailedException("login cancelled");
                },
            pollingExecutor);
    currentCancelFlag.set(cancelled);
    currentLoginFuture.set(future);
    return future;
  }

  public void cancelCurrentLogin() {
    AtomicBoolean flag = currentCancelFlag.getAndSet(null);
    if (flag != null) flag.set(true);
    CompletableFuture<LoginContext> old = currentLoginFuture.getAndSet(null);
    if (old != null && !old.isDone()) old.cancel(true);
  }

  /** 两次 get_qrcode_status 之间的间隔：接口即时返回，无间隔会打成热循环。被中断即保留中断位返回，由循环条件退出。 */
  private void sleepPollInterval() {
    long ms = config.getLoginPollIntervalMs();
    if (ms <= 0) return;
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public void close() {
    cancelCurrentLogin();
  }
}
