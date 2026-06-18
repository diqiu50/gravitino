/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.flink.connector.integration.test.iceberg;

import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.gravitino.Configs;
import org.apache.gravitino.auth.AuthenticatorType;
import org.apache.gravitino.flink.connector.store.GravitinoCatalogStoreFactoryOptions;
import org.apache.gravitino.integration.test.util.JwksMockServerHelper;
import org.apache.gravitino.integration.test.util.OAuthMockDataProvider;
import org.apache.gravitino.server.authentication.OAuthConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;

/**
 * Reproduces the OAuth2 scenario for a JDBC-backend Iceberg catalog: the Gravitino server has
 * OAuth2 authentication enabled and the Flink catalog store authenticates via OAuth2. Exercises
 * whether the JDBC credential is still vended to the Flink-side native Iceberg JdbcCatalog under
 * OAuth2.
 */
@Tag("gravitino-docker-test")
public abstract class FlinkIcebergJdbcCatalogOAuth2IT extends FlinkIcebergJdbcCatalogIT {

  private static final String SERVICE_AUDIENCE = "service1";
  private static final String KEY_ID = "test-kid";
  private static final String OAUTH2_CREDENTIAL = "test-client:test-secret";
  private static final String OAUTH2_SCOPE = "openid";

  private static JwksMockServerHelper mockServerHelper;

  private static final String CATALOG_STORE_PREFIX = "table.catalog-store.gravitino.";

  @Override
  protected void initAuthEnv() throws Exception {
    mockServerHelper = JwksMockServerHelper.create(KEY_ID);
    String validToken =
        mockServerHelper.mintToken(
            "gravitino", SERVICE_AUDIENCE, Instant.now().plusSeconds(1_000_000));
    mockServerHelper.setTokenSupplier(() -> validToken);

    Map<String, String> configs = Maps.newHashMap();
    configs.put(Configs.AUTHENTICATORS.getKey(), AuthenticatorType.OAUTH.name().toLowerCase());
    configs.put(OAuthConfig.SERVICE_AUDIENCE.getKey(), SERVICE_AUDIENCE);
    configs.put(
        OAuthConfig.TOKEN_VALIDATOR_CLASS.getKey(),
        "org.apache.gravitino.server.authentication.JwksTokenValidator");
    configs.put(OAuthConfig.JWKS_URI.getKey(), mockServerHelper.jwksUri());
    configs.put(OAuthConfig.PRINCIPAL_FIELDS.getKey(), "sub");
    configs.put(OAuthConfig.ALLOW_SKEW_SECONDS.getKey(), "6");
    registerCustomConfigs(configs);

    OAuthMockDataProvider.getInstance().setTokenData(validToken.getBytes(StandardCharsets.UTF_8));
  }

  @AfterAll
  public void closeMockServer() throws IOException {
    if (mockServerHelper != null) {
      mockServerHelper.close();
    }
  }

  /** Builds the Flink catalog store with OAuth2 authentication to the Gravitino server. */
  @Override
  protected void initFlinkEnv() {
    final Configuration configuration = new Configuration();
    configuration.setString(
        "table.catalog-store.kind", GravitinoCatalogStoreFactoryOptions.GRAVITINO);
    configuration.setString(CATALOG_STORE_PREFIX + "gravitino.metalake", GRAVITINO_METALAKE);
    configuration.setString(CATALOG_STORE_PREFIX + "gravitino.uri", gravitinoUri);
    configuration.setString(
        CATALOG_STORE_PREFIX + GravitinoCatalogStoreFactoryOptions.AUTH_TYPE,
        GravitinoCatalogStoreFactoryOptions.OAUTH2);
    configuration.setString(
        CATALOG_STORE_PREFIX + GravitinoCatalogStoreFactoryOptions.OAUTH2_SERVER_URI,
        mockServerHelper.baseUri());
    configuration.setString(
        CATALOG_STORE_PREFIX + GravitinoCatalogStoreFactoryOptions.OAUTH2_TOKEN_PATH, "token");
    configuration.setString(
        CATALOG_STORE_PREFIX + GravitinoCatalogStoreFactoryOptions.OAUTH2_CREDENTIAL,
        OAUTH2_CREDENTIAL);
    configuration.setString(
        CATALOG_STORE_PREFIX + GravitinoCatalogStoreFactoryOptions.OAUTH2_SCOPE, OAUTH2_SCOPE);
    EnvironmentSettings.Builder builder =
        EnvironmentSettings.newInstance().withConfiguration(configuration);
    tableEnv = TableEnvironment.create(builder.inBatchMode().build());
  }
}
