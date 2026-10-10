/*
 * Copyright 2026 Collate.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package org.openmetadata.integration.seatunnel;

import org.apache.seatunnel.connectors.seatunnel.jdbc.config.JdbcConnectionConfig;
import org.apache.seatunnel.connectors.seatunnel.jdbc.internal.dialect.JdbcDialect;
import org.apache.seatunnel.connectors.seatunnel.jdbc.internal.dialect.oracle.OracleDialect;
import org.apache.seatunnel.connectors.seatunnel.jdbc.internal.dialect.oracle.OracleDialectFactory;

/** Uses direct JDBC query metadata because the 3.0.0 Oracle catalog rejects TNS descriptors. */
public final class HospitalOracleDialectFactory extends OracleDialectFactory {
  @Override
  public String dialectFactoryName() {
    return "HospitalOracle";
  }

  @Override
  public boolean acceptsURL(String url) {
    return false;
  }

  @Override
  public JdbcDialect create() {
    return new DirectOracleDialect("", false);
  }

  @Override
  public JdbcDialect create(String compatibleMode, String fieldIde, JdbcConnectionConfig config) {
    return new DirectOracleDialect(fieldIde, config != null && config.isHandleBlobAsString());
  }

  private static final class DirectOracleDialect extends OracleDialect {
    private DirectOracleDialect(String fieldIde, boolean handleBlobAsString) {
      super(fieldIde, handleBlobAsString);
    }

    @Override
    public String dialectName() {
      return "HospitalOracle";
    }
  }
}
