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
import org.apache.gravitino.Audit;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Dialects;
import org.apache.gravitino.rel.Representation;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.View;
import org.apache.gravitino.rel.types.Types;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SparkGravitinoView}. */
public class TestSparkGravitinoView {

  private static final String VIEW_NAME = "test_view";
  private static final String SPARK_SQL = "SELECT id, name FROM t";
  private static final String HIVE_SQL = "SELECT id, name FROM t /* hive */";

  private Column[] twoColumns;
  private SparkTypeConverter converter;

  @BeforeEach
  void setUp() {
    twoColumns =
        new Column[] {
          Column.of("id", Types.IntegerType.get(), "id comment"),
          Column.of("name", Types.StringType.get(), null)
        };
    converter = new SparkTypeConverter34();
  }

  // ── SparkGravitinoView field mapping ──────────────────────────────────────

  @Test
  void testName() {
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, null, null, null);
    Assertions.assertEquals(VIEW_NAME, view.name());
  }

  @Test
  void testQuery() {
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, null, null, null);
    Assertions.assertEquals(SPARK_SQL, view.query());
  }

  @Test
  void testSchema() {
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, null, null, null);
    StructType schema = view.schema();
    Assertions.assertEquals(2, schema.length());
    StructField idField = schema.apply("id");
    Assertions.assertEquals(DataTypes.IntegerType, idField.dataType());
    Assertions.assertTrue(idField.nullable());
    StructField nameField = schema.apply("name");
    Assertions.assertEquals(DataTypes.StringType, nameField.dataType());
  }

  @Test
  void testColumnAliasesMatchColumnNames() {
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, null, null, null);
    Assertions.assertArrayEquals(new String[] {"id", "name"}, view.columnAliases());
    Assertions.assertArrayEquals(view.queryColumnNames(), view.columnAliases());
  }

  @Test
  void testColumnComments() {
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, null, null, null);
    String[] comments = view.columnComments();
    Assertions.assertEquals("id comment", comments[0]);
    Assertions.assertEquals("", comments[1]); // null comment → empty string
  }

  @Test
  void testProperties() {
    Map<String, String> props = Collections.singletonMap("key", "value");
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, null, null, props);
    Assertions.assertEquals("value", view.properties().get("key"));
  }

  @Test
  void testNullPropertiesReturnsEmptyMap() {
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, null, null, null);
    Assertions.assertEquals(Collections.emptyMap(), view.properties());
  }

  @Test
  void testCurrentCatalog() {
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, "my_catalog", null, null);
    Assertions.assertEquals("my_catalog", view.currentCatalog());
  }

  @Test
  void testNullCurrentCatalogReturnsEmptyString() {
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, null, null, null);
    Assertions.assertEquals("", view.currentCatalog());
  }

  @Test
  void testCurrentNamespace() {
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, null, "my_schema", null);
    Assertions.assertArrayEquals(new String[] {"my_schema"}, view.currentNamespace());
  }

  @Test
  void testNullCurrentNamespaceReturnsEmptyArray() {
    SparkGravitinoView view = newView(twoColumns, SPARK_SQL, null, null, null);
    Assertions.assertArrayEquals(new String[0], view.currentNamespace());
  }

  // ── SparkGravitinoView.create() dialect selection ─────────────────────────

  @Test
  void testCreatePicksSparkDialectFirst() {
    View gravitinoView =
        stubView(
            twoColumns,
            new Representation[] {rep(Dialects.SPARK, SPARK_SQL), rep(Dialects.HIVE, HIVE_SQL)},
            null,
            null,
            null);
    SparkGravitinoView view =
        SparkGravitinoView.create(
            gravitinoView, Arrays.asList(Dialects.SPARK, Dialects.HIVE), converter);
    Assertions.assertEquals(SPARK_SQL, view.query());
  }

  @Test
  void testCreateFallsBackToHiveDialect() {
    // Iceberg/Hive fallback order: [SPARK, HIVE]
    View gravitinoView =
        stubView(
            twoColumns, new Representation[] {rep(Dialects.HIVE, SPARK_SQL)}, null, null, null);
    SparkGravitinoView view =
        SparkGravitinoView.create(
            gravitinoView, Arrays.asList(Dialects.SPARK, Dialects.HIVE), converter);
    Assertions.assertEquals(SPARK_SQL, view.query());
  }

  @Test
  void testCreateFallsBackToQueryDialect() {
    // Paimon fallback order: [SPARK, HIVE, QUERY_DIALECT]
    View gravitinoView =
        stubView(
            twoColumns,
            new Representation[] {rep(Dialects.QUERY_DIALECT, SPARK_SQL)},
            null,
            null,
            null);
    SparkGravitinoView view =
        SparkGravitinoView.create(
            gravitinoView,
            Arrays.asList(Dialects.SPARK, Dialects.HIVE, Dialects.QUERY_DIALECT),
            converter);
    Assertions.assertEquals(SPARK_SQL, view.query());
  }

  @Test
  void testCreateThrowsWhenNoMatchingDialect() {
    View gravitinoView =
        stubView(
            twoColumns, new Representation[] {rep(Dialects.FLINK, "SELECT 1")}, null, null, null);
    List<String> order = Arrays.asList(Dialects.SPARK, Dialects.HIVE);
    IllegalStateException ex =
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> SparkGravitinoView.create(gravitinoView, order, converter));
    Assertions.assertTrue(
        ex.getMessage().contains(VIEW_NAME) && ex.getMessage().contains(order.toString()),
        "Message should contain view name and dialect list: " + ex.getMessage());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private SparkGravitinoView newView(
      Column[] columns,
      String sql,
      String defaultCatalog,
      String defaultSchema,
      Map<String, String> properties) {
    return new SparkGravitinoView(
        stubView(
            columns,
            new Representation[] {rep(Dialects.SPARK, sql)},
            defaultCatalog,
            defaultSchema,
            properties),
        sql,
        converter);
  }

  private static SQLRepresentation rep(String dialect, String sql) {
    return SQLRepresentation.builder().withDialect(dialect).withSql(sql).build();
  }

  private static View stubView(
      Column[] columns,
      Representation[] representations,
      String defaultCatalog,
      String defaultSchema,
      Map<String, String> properties) {
    return new View() {
      @Override
      public String name() {
        return VIEW_NAME;
      }

      @Override
      public Column[] columns() {
        return columns;
      }

      @Override
      public Representation[] representations() {
        return representations;
      }

      @Override
      public String defaultCatalog() {
        return defaultCatalog;
      }

      @Override
      public String defaultSchema() {
        return defaultSchema;
      }

      @Override
      public Map<String, String> properties() {
        return properties;
      }

      @Override
      public Audit auditInfo() {
        return null;
      }
    };
  }
}
