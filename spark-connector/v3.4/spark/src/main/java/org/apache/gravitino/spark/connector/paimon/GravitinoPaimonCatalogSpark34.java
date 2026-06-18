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
package org.apache.gravitino.spark.connector.paimon;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Dialects;
import org.apache.gravitino.rel.Representation;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.ViewChange;
import org.apache.gravitino.spark.connector.SparkGravitinoView;
import org.apache.gravitino.spark.connector.SparkTableChangeConverter;
import org.apache.gravitino.spark.connector.SparkTableChangeConverter34;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.gravitino.spark.connector.SparkTypeConverter34;
import org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException;
import org.apache.spark.sql.catalyst.analysis.NoSuchViewException;
import org.apache.spark.sql.catalyst.analysis.ViewAlreadyExistsException;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.View;
import org.apache.spark.sql.connector.catalog.ViewCatalog;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Gravitino Paimon catalog for Spark 3.4, adding view support via {@link ViewCatalog}. */
public class GravitinoPaimonCatalogSpark34 extends GravitinoPaimonCatalog implements ViewCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(GravitinoPaimonCatalogSpark34.class);

  @Override
  protected SparkTypeConverter getSparkTypeConverter() {
    return new SparkTypeConverter34();
  }

  @Override
  protected SparkTableChangeConverter getSparkTableChangeConverter(
      SparkTypeConverter sparkTypeConverter) {
    return new SparkTableChangeConverter34(sparkTypeConverter);
  }

  @Override
  protected List<String> viewDialectFallbackOrder() {
    return Arrays.asList(Dialects.SPARK, Dialects.HIVE, Dialects.QUERY_DIALECT);
  }

  @Override
  public Identifier[] listViews(String... namespace) throws NoSuchNamespaceException {
    String database;
    try {
      database = getDatabase(namespace);
    } catch (IllegalArgumentException e) {
      return new Identifier[0];
    }
    org.apache.gravitino.rel.ViewCatalog viewCatalog;
    try {
      viewCatalog = gravitinoCatalogClient.asViewCatalog();
    } catch (UnsupportedOperationException e) {
      LOG.warn("Catalog '{}' does not support views, returning empty view list", name(), e);
      return new Identifier[0];
    }
    try {
      return Arrays.stream(viewCatalog.listViews(Namespace.of(database)))
          .map(id -> Identifier.of(namespace, id.name()))
          .toArray(Identifier[]::new);
    } catch (UnsupportedOperationException e) {
      LOG.warn("Catalog '{}' listViews not implemented for namespace '{}'", name(), database, e);
      return new Identifier[0];
    } catch (NoSuchSchemaException e) {
      NoSuchNamespaceException ex = new NoSuchNamespaceException(namespace);
      ex.initCause(e);
      throw ex;
    }
  }

  @Override
  public View loadView(Identifier ident) throws NoSuchViewException {
    org.apache.gravitino.rel.ViewCatalog viewCatalog;
    try {
      viewCatalog = gravitinoCatalogClient.asViewCatalog();
    } catch (UnsupportedOperationException e) {
      LOG.warn("Catalog '{}' does not support views", name(), e);
      throw new NoSuchViewException(ident);
    }
    try {
      org.apache.gravitino.rel.View view =
          viewCatalog.loadView(NameIdentifier.of(getDatabase(ident), ident.name()));
      return SparkGravitinoView.create(view, viewDialectFallbackOrder(), sparkTypeConverter);
    } catch (org.apache.gravitino.exceptions.NoSuchViewException | IllegalArgumentException e) {
      throw new NoSuchViewException(ident);
    }
  }

  @Override
  public View createView(
      Identifier ident,
      String sql,
      String currentCatalog,
      String[] currentNamespace,
      StructType schema,
      String[] queryColumnNames,
      String[] columnAliases,
      String[] columnComments,
      Map<String, String> properties)
      throws ViewAlreadyExistsException, NoSuchNamespaceException {
    org.apache.gravitino.rel.ViewCatalog viewCatalog;
    try {
      viewCatalog = gravitinoCatalogClient.asViewCatalog();
    } catch (UnsupportedOperationException e) {
      throw new UnsupportedOperationException("Catalog '" + name() + "' does not support views", e);
    }
    String comment = properties.get(ViewCatalog.PROP_COMMENT);
    String defaultCatalog = currentCatalog != null ? currentCatalog : name();
    String defaultSchema =
        currentNamespace != null && currentNamespace.length > 0
            ? String.join(".", currentNamespace)
            : null;
    try {
      org.apache.gravitino.rel.View view =
          viewCatalog.createView(
              NameIdentifier.of(getDatabase(ident), ident.name()),
              comment,
              buildGravitinoColumns(schema, columnComments),
              buildSqlRepresentation(sql),
              defaultCatalog,
              defaultSchema,
              properties);
      return SparkGravitinoView.create(view, viewDialectFallbackOrder(), sparkTypeConverter);
    } catch (org.apache.gravitino.exceptions.ViewAlreadyExistsException e) {
      throw new ViewAlreadyExistsException(ident);
    } catch (NoSuchSchemaException e) {
      NoSuchNamespaceException ex = new NoSuchNamespaceException(ident.namespace());
      ex.initCause(e);
      throw ex;
    }
  }

  @Override
  public View alterView(
      Identifier ident, org.apache.spark.sql.connector.catalog.ViewChange... changes)
      throws NoSuchViewException {
    org.apache.gravitino.rel.ViewCatalog viewCatalog;
    try {
      viewCatalog = gravitinoCatalogClient.asViewCatalog();
    } catch (UnsupportedOperationException e) {
      LOG.warn("Catalog '{}' does not support views", name(), e);
      throw new NoSuchViewException(ident);
    }
    try {
      org.apache.gravitino.rel.View view =
          viewCatalog.alterView(
              NameIdentifier.of(getDatabase(ident), ident.name()), toGravitinoViewChanges(changes));
      return SparkGravitinoView.create(view, viewDialectFallbackOrder(), sparkTypeConverter);
    } catch (org.apache.gravitino.exceptions.NoSuchViewException e) {
      throw new NoSuchViewException(ident);
    }
  }

  @Override
  public boolean dropView(Identifier ident) {
    try {
      return gravitinoCatalogClient
          .asViewCatalog()
          .dropView(NameIdentifier.of(getDatabase(ident), ident.name()));
    } catch (UnsupportedOperationException e) {
      LOG.warn("Catalog '{}' does not support views", name(), e);
      return false;
    }
  }

  @Override
  public void renameView(Identifier oldIdent, Identifier newIdent)
      throws NoSuchViewException, ViewAlreadyExistsException {
    if (!Arrays.equals(oldIdent.namespace(), newIdent.namespace())) {
      throw new UnsupportedOperationException("Renaming views across namespaces is not supported");
    }
    org.apache.gravitino.rel.ViewCatalog viewCatalog;
    try {
      viewCatalog = gravitinoCatalogClient.asViewCatalog();
    } catch (UnsupportedOperationException e) {
      LOG.warn("Catalog '{}' does not support views", name(), e);
      throw new NoSuchViewException(oldIdent);
    }
    try {
      viewCatalog.alterView(
          NameIdentifier.of(getDatabase(oldIdent), oldIdent.name()),
          ViewChange.rename(newIdent.name()));
    } catch (org.apache.gravitino.exceptions.NoSuchViewException e) {
      throw new NoSuchViewException(oldIdent);
    } catch (org.apache.gravitino.exceptions.ViewAlreadyExistsException e) {
      throw new ViewAlreadyExistsException(newIdent);
    }
  }

  private Column[] buildGravitinoColumns(StructType schema, String[] columnComments) {
    StructField[] fields = schema.fields();
    Column[] columns = new Column[fields.length];
    for (int i = 0; i < fields.length; i++) {
      StructField field = fields[i];
      String comment =
          (columnComments != null && i < columnComments.length) ? columnComments[i] : null;
      columns[i] =
          Column.of(
              field.name(),
              sparkTypeConverter.toGravitinoType(field.dataType()),
              comment,
              field.nullable(),
              false,
              Column.DEFAULT_VALUE_NOT_SET);
    }
    return columns;
  }

  private static Representation[] buildSqlRepresentation(String sql) {
    return new Representation[] {
      SQLRepresentation.builder().withDialect(Dialects.SPARK).withSql(sql).build()
    };
  }

  private static ViewChange[] toGravitinoViewChanges(
      org.apache.spark.sql.connector.catalog.ViewChange[] changes) {
    return Arrays.stream(changes)
        .map(
            change -> {
              if (change instanceof org.apache.spark.sql.connector.catalog.ViewChange.SetProperty) {
                org.apache.spark.sql.connector.catalog.ViewChange.SetProperty p =
                    (org.apache.spark.sql.connector.catalog.ViewChange.SetProperty) change;
                return ViewChange.setProperty(p.property(), p.value());
              } else if (change
                  instanceof org.apache.spark.sql.connector.catalog.ViewChange.RemoveProperty) {
                org.apache.spark.sql.connector.catalog.ViewChange.RemoveProperty p =
                    (org.apache.spark.sql.connector.catalog.ViewChange.RemoveProperty) change;
                return ViewChange.removeProperty(p.property());
              } else {
                throw new UnsupportedOperationException(
                    "Unsupported view change: " + change.getClass().getName());
              }
            })
        .toArray(ViewChange[]::new);
  }
}
