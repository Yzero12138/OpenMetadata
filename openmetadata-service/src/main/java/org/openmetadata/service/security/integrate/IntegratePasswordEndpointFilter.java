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

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Set;

@Priority(Priorities.AUTHENTICATION - 1)
public final class IntegratePasswordEndpointFilter implements ContainerRequestFilter {
  private static final Set<String> PASSWORD_ENDPOINTS =
      Set.of(
          "v1/users/login",
          "v1/users/refresh",
          "v1/users/signup",
          "v1/users/registrationConfirmation",
          "v1/users/resendRegistrationToken",
          "v1/users/generatePasswordResetLink",
          "v1/users/password/reset",
          "v1/users/changePassword");

  @Override
  public void filter(ContainerRequestContext request) {
    if (IntegrateAuthServletHandler.isEnabled()
        && PASSWORD_ENDPOINTS.stream()
            .anyMatch(path -> path.equalsIgnoreCase(request.getUriInfo().getPath()))) {
      request.abortWith(
          Response.status(Response.Status.FORBIDDEN)
              .header("Cache-Control", "no-store")
              .entity(Map.of("error", "integrate_portal_required"))
              .build());
    }
  }
}
