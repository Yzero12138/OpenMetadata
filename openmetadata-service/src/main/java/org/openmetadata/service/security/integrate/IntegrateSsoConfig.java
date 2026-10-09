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

import java.net.URI;
import java.util.Map;

public record IntegrateSsoConfig(
    boolean enabled,
    URI issuer,
    URI targetOrigin,
    String clientId,
    String clientSecret,
    String emailDomain,
    String portalPath,
    URI backchannelOrigin) {

  public static IntegrateSsoConfig fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  public static IntegrateSsoConfig fromEnvironment(Map<String, String> environment) {
    boolean enabled = flag(environment, "INTEGRATE_SSO_ENABLED");
    if (!enabled) {
      return new IntegrateSsoConfig(
          false, null, null, "", "", "integrate.invalid", "/s/portal", null);
    }
    boolean allowHttp = flag(environment, "INTEGRATE_SSO_ALLOW_HTTP_TEST");
    URI issuer = origin(required(environment, "INTEGRATE_SSO_ISSUER"), allowHttp);
    URI target = origin(required(environment, "INTEGRATE_SSO_TARGET_ORIGIN"), allowHttp);
    URI backchannel =
        origin(
            environment.getOrDefault("INTEGRATE_SSO_BACKCHANNEL_ORIGIN", issuer.toString()),
            allowHttp);
    String clientId = required(environment, "INTEGRATE_SSO_CLIENT_ID");
    String secret = required(environment, "INTEGRATE_SSO_CLIENT_SECRET");
    if (!clientId.matches("[A-Za-z0-9._-]{1,128}")
        || secret.length() < 32
        || secret.length() > 2048) {
      throw new IllegalArgumentException("Integrate client ID or secret is invalid");
    }
    String domain = environment.getOrDefault("INTEGRATE_SSO_EMAIL_DOMAIN", "integrate.invalid");
    if (!domain.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")) {
      throw new IllegalArgumentException("Invalid internal identity domain");
    }
    String path = environment.getOrDefault("INTEGRATE_SSO_PORTAL_PATH", "/s/portal");
    if (!path.startsWith("/")
        || path.startsWith("//")
        || path.contains("\\")
        || URI.create(path).getRawQuery() != null
        || URI.create(path).getRawFragment() != null) {
      throw new IllegalArgumentException(
          "Portal path must be an absolute path on the Integrate origin");
    }
    return new IntegrateSsoConfig(
        true, issuer, target, clientId, secret, domain, path, backchannel);
  }

  public Map<String, Object> publicConfiguration() {
    return Map.of(
        "enabled",
        enabled,
        "issuer",
        enabled ? issuer.toString() : "",
        "portalUrl",
        enabled ? issuer.resolve(portalPath).toString() : "");
  }

  public boolean acceptsOrigin(String requestOrigin) {
    return enabled && targetOrigin.toString().equals(requestOrigin);
  }

  private static URI origin(String value, boolean allowHttp) {
    URI uri = URI.create(value);
    if (!("https".equals(uri.getScheme()) || allowHttp && "http".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getRawPath() == null
        || !uri.getRawPath().isEmpty()) {
      throw new IllegalArgumentException(
          "Integrate SSO requires an exact HTTPS origin without a path");
    }
    return uri;
  }

  private static String required(Map<String, String> environment, String name) {
    String value = environment.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing " + name);
    }
    return value;
  }

  private static boolean flag(Map<String, String> environment, String name) {
    String value = environment.getOrDefault(name, "false");
    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
      throw new IllegalArgumentException("Invalid boolean " + name);
    }
    return Boolean.parseBoolean(value);
  }

  @Override
  public String toString() {
    return "IntegrateSsoConfig[enabled=" + enabled + ", issuer=" + issuer + "]";
  }
}
