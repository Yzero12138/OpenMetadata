package org.openmetadata.service.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;
import org.openmetadata.service.integration.IntegrationModels.ConnectionDefinition;

class DorisHttpHealthTest {
  private HttpServer server;
  private ExecutorService executor;
  private JdbcEndpoint endpoint;
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body =
      new AtomicReference<>(
          "{\"msg\":\"success\",\"code\":0,\"data\":{\"online_backend_num\":1,\"total_backend_num\":1}}");
  private final AtomicInteger redirected = new AtomicInteger();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private volatile boolean slowBody;

  @BeforeEach
  void prepare() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/api/health",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Location", "/redirected");
          exchange.sendResponseHeaders(status.get(), bytes.length);
          try (exchange) {
            if (slowBody) {
              exchange.getResponseBody().write(bytes[0]);
              exchange.getResponseBody().flush();
              try {
                Thread.sleep(6000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            } else {
              exchange.getResponseBody().write(bytes);
            }
          }
        });
    server.createContext(
        "/redirected",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    ConnectionDefinition definition = new ConnectionDefinition();
    definition.id = "doris-health-fixture";
    definition.databaseType = "Doris";
    definition.role = "TARGET";
    definition.host = "127.0.0.1";
    definition.port = 9030;
    definition.dorisHttpPort = server.getAddress().getPort();
    definition.database = "warehouse";
    definition.schemas = List.of("warehouse");
    definition.tlsMode = "DISABLED";
    definition.username = "synthetic_loader";
    endpoint = new JdbcEndpoint(definition, "must-never-be-sent-over-health-http");
  }

  @AfterEach
  void close() {
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  @Test
  void acceptsOnlySuccessfulFeHealthWithoutSendingCredentials() {
    assertTrue(DorisHttpHealth.healthy(endpoint));
    assertNull(authorization.get());
    status.set(503);
    assertFalse(DorisHttpHealth.healthy(endpoint));
    status.set(200);
    for (String invalid :
        List.of(
            "{\"msg\":\"error\",\"code\":1}",
            "{\"status\":\"OK\"}",
            "not-json",
            " ".repeat(4097))) {
      body.set(invalid);
      assertFalse(DorisHttpHealth.healthy(endpoint));
    }
  }

  @Test
  void neverFollowsRedirects() {
    status.set(307);
    assertFalse(DorisHttpHealth.healthy(endpoint));
    assertTrue(redirected.get() == 0);
  }

  @Test
  void boundsTheWholeResponseEvenWhenHeadersArriveBeforeASlowBody() {
    slowBody = true;
    assertTimeoutPreemptively(
        Duration.ofSeconds(5), () -> assertFalse(DorisHttpHealth.healthy(endpoint)));
  }
}
