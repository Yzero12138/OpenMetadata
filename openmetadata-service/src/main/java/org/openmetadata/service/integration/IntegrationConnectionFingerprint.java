package org.openmetadata.service.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.openmetadata.service.integration.IntegrationConfiguration.JdbcEndpoint;

public final class IntegrationConnectionFingerprint {
  private IntegrationConnectionFingerprint() {}

  public static String of(JdbcEndpoint endpoint) {
    String route =
        endpoint.dialect().name()
            + "|"
            + endpoint.host()
            + "|"
            + endpoint.port()
            + "|"
            + endpoint.database()
            + "|"
            + endpoint.tlsMode()
            + "|"
            + endpoint.dorisHttpPort()
            + "|"
            + String.join(",", endpoint.schemas().stream().sorted().toList())
            + "|"
            + endpoint.jdbcUrl().split("\\?", 2)[0];
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(route.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
