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

import static org.openmetadata.service.security.SecurityUtil.writeJsonResponse;
import static org.openmetadata.service.util.UserUtil.getRoleListFromUser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.openmetadata.schema.auth.JWTAuthMechanism;
import org.openmetadata.schema.auth.ServiceTokenType;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.Include;
import org.openmetadata.service.Entity;
import org.openmetadata.service.auth.JwtResponse;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.UserRepository;
import org.openmetadata.service.security.AuthServeletHandler;
import org.openmetadata.service.security.AuthServeletHandlerRegistry;
import org.openmetadata.service.security.SecurityUtil;
import org.openmetadata.service.security.jwt.JWTTokenGenerator;
import org.openmetadata.service.security.session.SessionService;
import org.openmetadata.service.security.session.UserSession;
import org.openmetadata.service.util.UserUtil;

public final class IntegrateAuthServletHandler implements AuthServeletHandler {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final IntegrateSsoConfig config;
  private final SessionService sessions;
  private final IntegrateIdentityClient identityClient;

  public IntegrateAuthServletHandler(IntegrateSsoConfig config, SessionService sessions) {
    this.config = config;
    this.sessions = sessions;
    this.identityClient = new IntegrateIdentityClient(config);
    sessions.setExternalSessionValidator(this::validateSession);
  }

  public static boolean isEnabled() {
    return AuthServeletHandlerRegistry.getHandler() instanceof IntegrateAuthServletHandler;
  }

  public Map<String, Object> publicConfiguration() {
    return config.publicConfiguration();
  }

  public boolean validateSession(UserSession session) {
    if (!IntegrateIdentityClient.PROVIDER.equals(session.getProvider())) {
      return false;
    }
    if (session.isExpired(System.currentTimeMillis())) {
      return false;
    }
    try {
      var identity = identityClient.introspect(sessions.decryptProviderRefreshToken(session));
      return identityClient.username(identity).equals(session.getUsername());
    } catch (IntegrateIdentityClient.InvalidIdentityException
        | IntegrateIdentityClient.IdentityUnavailableException
        | IllegalArgumentException e) {
      return false;
    }
  }

  public void handleExchange(HttpServletRequest request, HttpServletResponse response) {
    respond(
        request,
        response,
        () -> {
          if (!config.acceptsOrigin(request.getHeader("Origin"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
          }
          if (request.getContentType() == null
              || !request.getContentType().startsWith("application/json")) {
            throw new IllegalArgumentException("JSON request required");
          }
          byte[] bytes = request.getInputStream().readNBytes(8193);
          if (bytes.length > 8192) {
            throw new IllegalArgumentException("Request too large");
          }
          JsonNode ticket = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
          if (ticket == null || !ticket.isObject()) {
            throw new IllegalArgumentException("Invalid ticket");
          }
          var identity =
              identityClient.exchange(
                  ticket.path("code").asText(), ticket.path("challenge").asText());
          User user = resolveUser(identity);
          sessions.revokeSession(request, response);
          UserSession session =
              sessions.createActiveSession(
                  request,
                  response,
                  IntegrateIdentityClient.PROVIDER,
                  user,
                  null,
                  identity.sessionToken(),
                  identity.expiresIn());
          writeJsonResponse(response, JSON.writeValueAsString(tokenResponse(user, session)));
        });
  }

  private synchronized User resolveUser(IntegrateIdentityClient.Identity identity) {
    String username = identityClient.username(identity);
    UserRepository users = (UserRepository) Entity.getEntityRepository(Entity.USER);
    User user;
    try {
      user = findUser(users, username);
    } catch (EntityNotFoundException e) {
      User candidate =
          UserUtil.user(username, config.emailDomain(), Entity.ADMIN_USER_NAME)
              .withEmail(IntegrateIdentityClient.email(username, config.emailDomain()))
              .withDisplayName(identity.name())
              .withIsAdmin(false)
              .withIsEmailVerified(true);
      try {
        // Create only: another pod may have provisioned the employee in parallel.
        // A login must never overwrite roles or an administrator's local decisions.
        user = users.create(null, candidate);
      } catch (RuntimeException creationFailure) {
        try {
          user = findUser(users, username);
        } catch (EntityNotFoundException notCreated) {
          throw creationFailure;
        }
      }
    }
    return normalizeUserEmail(users, username, user);
  }

  private User normalizeUserEmail(UserRepository users, String username, User user) {
    if (!username.equals(user.getName())
        || Boolean.TRUE.equals(user.getDeleted())
        || Boolean.TRUE.equals(user.getIsBot())
        || !username.matches("integrate_[0-9a-f]{64}")) {
      throw new IntegrateIdentityClient.InvalidIdentityException();
    }
    String expectedEmail = IntegrateIdentityClient.email(username, config.emailDomain());
    if (expectedEmail.equals(user.getEmail())) {
      return user;
    }
    String legacyEmail = username + "@" + config.emailDomain();
    if (!legacyEmail.equals(user.getEmail())) {
      throw new IntegrateIdentityClient.InvalidIdentityException();
    }
    var patch =
        Json.createPatchBuilder()
            .test("/email", legacyEmail)
            .replace("/email", expectedEmail)
            .build();
    try {
      users.patch(null, user.getId(), Entity.ADMIN_USER_NAME, patch);
    } catch (RuntimeException migrationFailure) {
      User refreshed = findUser(users, username);
      if (!expectedEmail.equals(refreshed.getEmail())) {
        throw migrationFailure;
      }
    }
    user = findUser(users, username);
    if (Boolean.TRUE.equals(user.getDeleted())
        || Boolean.TRUE.equals(user.getIsBot())
        || !expectedEmail.equals(user.getEmail())) {
      throw new IntegrateIdentityClient.InvalidIdentityException();
    }
    return user;
  }

  private User findUser(UserRepository users, String username) {
    return users.getByName(
        null,
        username,
        users.getFieldsWithUserAuth("id,name,email,roles,isAdmin"),
        Include.ALL,
        false);
  }

  @Override
  public void handleLogin(HttpServletRequest request, HttpServletResponse response) {
    error(response, HttpServletResponse.SC_FORBIDDEN, "integrate_portal_required");
  }

  @Override
  public void handleCallback(HttpServletRequest request, HttpServletResponse response) {
    error(response, HttpServletResponse.SC_NOT_FOUND, "integrate_portal_required");
  }

  @Override
  public void handleLogout(HttpServletRequest request, HttpServletResponse response) {
    respond(
        request,
        response,
        () -> {
          requireOrigin(request);
          sessions.revokeSession(request, response);
          writeJsonResponse(response, "{\"success\":true}");
        });
  }

  @Override
  public void handleRefresh(HttpServletRequest request, HttpServletResponse response) {
    respond(
        request,
        response,
        () -> {
          requireOrigin(request);
          UserSession session =
              sessions
                  .getActiveSession(request, response)
                  .orElseThrow(IntegrateIdentityClient.InvalidIdentityException::new);
          if (!validateSession(session)) {
            sessions.revokeSession(request, response);
            throw new IntegrateIdentityClient.InvalidIdentityException();
          }
          UserRepository users = (UserRepository) Entity.getEntityRepository(Entity.USER);
          User user =
              users.get(
                  null,
                  UUID.fromString(session.getUserId()),
                  users.getFieldsWithUserAuth("id,name,email,roles,isAdmin"));
          user = normalizeUserEmail(users, session.getUsername(), user);
          writeJsonResponse(response, JSON.writeValueAsString(tokenResponse(user, session)));
        });
  }

  private JwtResponse tokenResponse(User user, UserSession session) {
    long remaining = Math.max(1, (session.getExpiresAt() - System.currentTimeMillis()) / 1000);
    long lifetime =
        Math.min(remaining, SecurityUtil.getLoginConfiguration().getJwtTokenExpiryTime());
    JWTAuthMechanism token =
        JWTTokenGenerator.getInstance()
            .generateJWTTokenForSession(
                user.getName(),
                getRoleListFromUser(user),
                Boolean.TRUE.equals(user.getIsAdmin()),
                user.getEmail(),
                lifetime,
                ServiceTokenType.OM_USER,
                session.getId());
    JwtResponse response = new JwtResponse();
    response.setAccessToken(token.getJWTToken());
    response.setExpiryDuration(token.getJWTTokenExpiresAt());
    response.setTokenType("Bearer");
    return response;
  }

  private void requireOrigin(HttpServletRequest request) {
    if (!"POST".equals(request.getMethod()) || !config.acceptsOrigin(request.getHeader("Origin"))) {
      throw new IllegalArgumentException("Same-origin POST required");
    }
  }

  @FunctionalInterface
  private interface Action {
    void run() throws IOException;
  }

  private void respond(HttpServletRequest request, HttpServletResponse response, Action action) {
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    try {
      action.run();
    } catch (IntegrateIdentityClient.InvalidIdentityException | EntityNotFoundException e) {
      error(response, HttpServletResponse.SC_UNAUTHORIZED, "integrate_session_inactive");
    } catch (IntegrateIdentityClient.IdentityUnavailableException e) {
      error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "integrate_identity_unavailable");
    } catch (IllegalArgumentException | IOException e) {
      error(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_integrate_request");
    } catch (Exception e) {
      error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "integrate_login_unavailable");
    }
  }

  private void error(HttpServletResponse response, int status, String code) {
    try {
      response.setHeader("Cache-Control", "no-store");
      response.setStatus(status);
      writeJsonResponse(response, JSON.writeValueAsString(Map.of("error", code)));
    } catch (IOException e) {
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }
}
