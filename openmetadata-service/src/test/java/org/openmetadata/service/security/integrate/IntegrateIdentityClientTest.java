/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.security.integrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmetadata.service.security.AuthServeletHandlerRegistry;
import org.openmetadata.service.security.NoopAuthServeletHandler;
import org.openmetadata.service.util.UserUtil;

class IntegrateIdentityClientTest {
  private HttpServer server;
  private String issuer;
  private IntegrateIdentityClient client;
  private final AtomicReference<String> reply = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> requestBody = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/api/platform/portal/datahub/",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    client = new IntegrateIdentityClient(config(Map.of()));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private IntegrateSsoConfig config(Map<String, String> overrides) {
    Map<String, String> values =
        new HashMap<>(
            Map.of(
                "INTEGRATE_SSO_ENABLED", "true",
                "INTEGRATE_SSO_ISSUER", issuer,
                "INTEGRATE_SSO_TARGET_ORIGIN", "http://localhost:3000",
                "INTEGRATE_SSO_CLIENT_ID", "hospital-metadata",
                "INTEGRATE_SSO_CLIENT_SECRET", "s".repeat(40),
                "INTEGRATE_SSO_ALLOW_HTTP_TEST", "true"));
    values.putAll(overrides);
    return IntegrateSsoConfig.fromEnvironment(values);
  }

  private String activeReply(String extra) {
    return "{\"active\":true,\"issuer\":\""
        + issuer
        + "\",\"audience\":\"hospital-metadata\",\"sub\":\"1087\","
        + "\"name\":\"检验科测试用户\",\"expires_in\":120"
        + extra
        + "}";
  }

  @Test
  void exchangesBoundTicketUsingServerCredentials() {
    reply.set(activeReply(",\"session_token\":\"" + "T".repeat(43) + "\""));
    var grant = client.exchange("C".repeat(43), "H".repeat(43));
    assertEquals("1087", grant.subject());
    assertEquals(120, grant.expiresIn());
    assertEquals(
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    ("hospital-metadata:" + "s".repeat(40)).getBytes(StandardCharsets.UTF_8)),
        authorization.get());
    assertTrue(requestBody.get().contains("\"challenge\":\"" + "H".repeat(43)));
  }

  @Test
  void rejectsInactiveAndRevokedSourceSessions() {
    reply.set("{\"active\":false}");
    assertThrows(
        IntegrateIdentityClient.InvalidIdentityException.class,
        () -> client.introspect("T".repeat(43)));
  }

  @Test
  void rejectsWrongAudienceAndMissingSessionToken() {
    reply.set(activeReply("").replace("hospital-metadata", "another-client"));
    assertThrows(
        IntegrateIdentityClient.InvalidIdentityException.class,
        () -> client.exchange("C".repeat(43), "H".repeat(43)));
    reply.set(activeReply(""));
    assertThrows(
        IntegrateIdentityClient.InvalidIdentityException.class,
        () -> client.exchange("C".repeat(43), "H".repeat(43)));
  }

  @Test
  void rejectsMalformedTicketsBeforeNetworkCall() {
    assertThrows(IllegalArgumentException.class, () -> client.exchange("short", "H".repeat(43)));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.exchange("C".repeat(43), "https://attacker.example"));
  }

  @Test
  void validatesHttpsOriginsAndNeverPublishesCredentials() {
    assertThrows(
        IllegalArgumentException.class,
        () -> config(Map.of("INTEGRATE_SSO_ALLOW_HTTP_TEST", "false")));
    assertThrows(
        IllegalArgumentException.class,
        () -> config(Map.of("INTEGRATE_SSO_ISSUER", issuer + "/portal")));
    assertThrows(
        IllegalArgumentException.class,
        () -> config(Map.of("INTEGRATE_SSO_TARGET_ORIGIN", "http://localhost:3000/")));
    assertEquals(issuer + "/s/portal", config(Map.of()).publicConfiguration().get("portalUrl"));
    assertEquals(3, config(Map.of()).publicConfiguration().size());
  }

  @Test
  void rejectsUnboundedOrNonNumericLifetime() {
    for (String lifetime : new String[] {"0", "28801", "\"120\""}) {
      reply.set(activeReply("").replace("\"expires_in\":120", "\"expires_in\":" + lifetime));
      assertThrows(
          IntegrateIdentityClient.InvalidIdentityException.class,
          () -> client.introspect("T".repeat(43)));
    }
  }

  @Test
  void usesAnInternalBackchannelWhileVerifyingThePublicIssuer() {
    String publicIssuer = "https://integrate.example.hospital";
    reply.set(
        activeReply(",\"session_token\":\"" + "T".repeat(43) + "\"").replace(issuer, publicIssuer));
    var configuration =
        config(
            Map.of(
                "INTEGRATE_SSO_ISSUER", publicIssuer,
                "INTEGRATE_SSO_BACKCHANNEL_ORIGIN", issuer));
    var grant = new IntegrateIdentityClient(configuration).exchange("C".repeat(43), "H".repeat(43));
    assertEquals("1087", grant.subject());
    assertEquals(publicIssuer, configuration.publicConfiguration().get("issuer"));
    assertEquals(3, configuration.publicConfiguration().size());
  }

  @Test
  void rejectsHttpBackchannelsUnlessExplicitlyEnabledForTesting() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config(
                Map.of(
                    "INTEGRATE_SSO_ISSUER", "https://integrate.example.hospital",
                    "INTEGRATE_SSO_TARGET_ORIGIN", "https://metadata.example.hospital",
                    "INTEGRATE_SSO_BACKCHANNEL_ORIGIN", issuer,
                    "INTEGRATE_SSO_ALLOW_HTTP_TEST", "false")));
  }

  @Test
  void mappedUserEmailPassesTheNativeUserProfileConstraint() {
    var previousHandler = AuthServeletHandlerRegistry.getHandler();
    var configuredSso = config(Map.of());
    try (var environment = mockStatic(IntegrateSsoConfig.class, CALLS_REAL_METHODS);
        var validators = Validation.buildDefaultValidatorFactory()) {
      environment.when(IntegrateSsoConfig::fromEnvironment).thenReturn(configuredSso);
      AuthServeletHandlerRegistry.setHandler(NoopAuthServeletHandler.getInstance());
      var identity = new IntegrateIdentityClient.Identity("1087", "检验科测试用户", 120, "");
      String username = client.username(identity);
      var user = UserUtil.user(username, "integrate.invalid", "admin");

      var violations = validators.getValidator().validateProperty(user, "email");
      assertTrue(violations.isEmpty(), violations.toString());
      assertEquals(username, user.getName());
      assertEquals(64, user.getEmail().split("@")[0].length());
    } finally {
      AuthServeletHandlerRegistry.setHandler(previousHandler);
    }
  }

  @Test
  void keepsTheCompleteStableDigestInTheUsernameAndInternalEmail() {
    var identity = new IntegrateIdentityClient.Identity("1087", "检验科测试用户", 120, "");
    String username = client.username(identity);
    assertTrue(username.matches("integrate_[0-9a-f]{64}"));
    assertEquals(
        username.substring("integrate_".length()) + "@integrate.invalid",
        IntegrateIdentityClient.email(username, "integrate.invalid"));
    assertEquals(
        username, client.username(new IntegrateIdentityClient.Identity("1087", "更改显示名", 10, "")));
    assertTrue(
        !username.equals(
            client.username(new IntegrateIdentityClient.Identity("1088", "检验科测试用户", 120, ""))));
  }

  @Test
  void leavesNativeAccountEmailsUnchangedOutsideIntegrateMode() {
    var previousHandler = AuthServeletHandlerRegistry.getHandler();
    var disabledSso = IntegrateSsoConfig.fromEnvironment(Map.of());
    try (var environment = mockStatic(IntegrateSsoConfig.class, CALLS_REAL_METHODS)) {
      environment.when(IntegrateSsoConfig::fromEnvironment).thenReturn(disabledSso);
      AuthServeletHandlerRegistry.setHandler(NoopAuthServeletHandler.getInstance());
      String name = "integrate_" + "a".repeat(64);
      assertEquals(
          name + "@example.hospital", UserUtil.user(name, "example.hospital", "admin").getEmail());
      assertEquals(
          "alice@example.hospital", UserUtil.user("alice", "example.hospital", "admin").getEmail());
    } finally {
      AuthServeletHandlerRegistry.setHandler(previousHandler);
    }
  }
}
