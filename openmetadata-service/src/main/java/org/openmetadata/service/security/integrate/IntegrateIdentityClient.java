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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

public final class IntegrateIdentityClient {
  public static final String PROVIDER = "integrate";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final IntegrateSsoConfig config;
  private final HttpClient http;

  public record Identity(String subject, String name, int expiresIn, String sessionToken) {}

  public static final class InvalidIdentityException extends RuntimeException {
    public InvalidIdentityException() {
      super("Integrate session is inactive or invalid");
    }
  }

  public static final class IdentityUnavailableException extends RuntimeException {
    public IdentityUnavailableException() {
      super("Integrate identity service is unavailable");
    }
  }

  public IntegrateIdentityClient(IntegrateSsoConfig config) {
    this.config = config;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public Identity exchange(String code, String challenge) {
    validateToken(code);
    if (challenge == null || !challenge.matches("[A-Za-z0-9_-]{32,128}")) {
      throw new IllegalArgumentException("Invalid Integrate challenge");
    }
    return request("exchange", Map.of("code", code, "challenge", challenge), true);
  }

  public Identity introspect(String sessionToken) {
    validateToken(sessionToken);
    return request("introspect", Map.of("session_token", sessionToken), false);
  }

  public String username(Identity identity) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (config.issuer() + "\n" + identity.subject()).getBytes(StandardCharsets.UTF_8));
      return "integrate_" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Identity request(String operation, Map<String, String> body, boolean needsToken) {
    try {
      String credentials =
          Base64.getEncoder()
              .encodeToString(
                  (config.clientId() + ":" + config.clientSecret())
                      .getBytes(StandardCharsets.UTF_8));
      HttpRequest request =
          HttpRequest.newBuilder(
                  config.backchannelOrigin().resolve("/api/platform/portal/datahub/" + operation))
              .timeout(Duration.ofSeconds(5))
              .header("Authorization", "Basic " + credentials)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
              .build();
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200 || response.body().length > 8192) {
        throw new IdentityUnavailableException();
      }
      JsonNode identity = JSON.readTree(response.body());
      if (identity == null || !identity.isObject()) {
        throw new InvalidIdentityException();
      }
      JsonNode lifetime = identity.path("expires_in");
      String subject = identity.path("sub").asText("");
      String name = identity.path("name").asText("");
      String token = identity.path("session_token").asText("");
      if (!identity.path("active").isBoolean()
          || !identity.path("active").booleanValue()
          || !config.issuer().toString().equals(identity.path("issuer").asText())
          || !config.clientId().equals(identity.path("audience").asText())
          || !identity.path("sub").isTextual()
          || subject.isBlank()
          || subject.length() > 256
          || !lifetime.isIntegralNumber()
          || !lifetime.canConvertToInt()
          || lifetime.intValue() < 1
          || lifetime.intValue() > 28800
          || name.length() > 256
          || needsToken && !token.matches("[A-Za-z0-9_-]{43}")) {
        throw new InvalidIdentityException();
      }
      return new Identity(subject, name.isBlank() ? subject : name, lifetime.intValue(), token);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IdentityUnavailableException();
    } catch (IOException e) {
      throw new IdentityUnavailableException();
    }
  }

  private static void validateToken(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("Invalid Integrate ticket or session token");
    }
  }
}
