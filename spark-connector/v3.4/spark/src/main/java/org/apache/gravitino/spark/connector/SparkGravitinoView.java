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
package org.apache.gravitino.spark.connector;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.spark.sql.connector.catalog.View;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

/**
 * A Spark {@link View} backed by a Gravitino {@link org.apache.gravitino.rel.View}. Used to expose
 * Gravitino-managed views to Spark for read-only access.
 */
public class SparkGravitinoView implements View {

  private final org.apache.gravitino.rel.View gravitinoView;
  private final String query;
  private final SparkTypeConverter sparkTypeConverter;

  public SparkGravitinoView(
      org.apache.gravitino.rel.View gravitinoView,
      String query,
      SparkTypeConverter sparkTypeConverter) {
    this.gravitinoView = gravitinoView;
    this.query = query;
    this.sparkTypeConverter = sparkTypeConverter;
  }

  /**
   * Creates a {@link SparkGravitinoView} by selecting the best-matching SQL from the given dialect
   * fallback order.
   *
   * @param view the Gravitino view
   * @param dialectOrder ordered list of dialect identifiers to try
   * @param converter type converter for this Spark version
   * @return a SparkGravitinoView backed by the selected SQL
   * @throws RuntimeException if no representation matches any dialect in the fallback order
   */
  public static SparkGravitinoView create(
      org.apache.gravitino.rel.View view, List<String> dialectOrder, SparkTypeConverter converter) {
    String sql =
        dialectOrder.stream()
            .map(d -> view.sqlFor(d).map(SQLRepresentation::sql))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        String.format(
                            "View '%s' has no SQL representation for dialects %s",
                            view.name(), dialectOrder)));
    return new SparkGravitinoView(view, sql, converter);
  }

  @Override
  public String name() {
    return gravitinoView.name();
  }

  @Override
  public String query() {
    return query;
  }

  @Override
  public String currentCatalog() {
    String catalog = gravitinoView.defaultCatalog();
    return catalog != null ? catalog : "";
  }

  @Override
  public String[] currentNamespace() {
    String schema = gravitinoView.defaultSchema();
    return schema != null ? new String[] {schema} : new String[0];
  }

  @Override
  public StructType schema() {
    StructField[] fields =
        Arrays.stream(gravitinoView.columns())
            .map(
                col ->
                    DataTypes.createStructField(
                        col.name(), sparkTypeConverter.toSparkType(col.dataType()), col.nullable()))
            .toArray(StructField[]::new);
    return DataTypes.createStructType(fields);
  }

  @Override
  public String[] queryColumnNames() {
    return Arrays.stream(gravitinoView.columns()).map(Column::name).toArray(String[]::new);
  }

  @Override
  public String[] columnAliases() {
    return queryColumnNames();
  }

  @Override
  public String[] columnComments() {
    return Arrays.stream(gravitinoView.columns())
        .map(col -> col.comment() != null ? col.comment() : "")
        .toArray(String[]::new);
  }

  @Override
  public Map<String, String> properties() {
    Map<String, String> props = gravitinoView.properties();
    return props != null ? props : Collections.emptyMap();
  }
}
