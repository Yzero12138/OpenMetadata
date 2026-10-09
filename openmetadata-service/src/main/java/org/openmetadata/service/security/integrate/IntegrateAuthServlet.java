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

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.security.AuthServeletHandlerRegistry;
import org.openmetadata.service.security.SecurityUtil;

public final class IntegrateAuthServlet extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    if (!"/config".equals(request.getPathInfo())) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    var handler = AuthServeletHandlerRegistry.getHandler(request.getServletContext());
    var config =
        handler instanceof IntegrateAuthServletHandler integrate
            ? integrate.publicConfiguration()
            : IntegrateSsoConfig.fromEnvironment(Map.of()).publicConfiguration();
    SecurityUtil.writeJsonResponse(response, JsonUtils.pojoToJson(config));
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    var handler = AuthServeletHandlerRegistry.getHandler(request.getServletContext());
    if (handler instanceof IntegrateAuthServletHandler integrate
        && "/exchange".equals(request.getPathInfo())) {
      integrate.handleExchange(request, response);
    } else {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
  }
}
