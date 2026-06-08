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
import org.apache.gravitino.rel.Dialects;
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
import org.apache.spark.sql.connector.catalog.ViewChange;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gravitino Paimon catalog for Spark 3.4, adding read-only view support via {@link ViewCatalog}.
 */
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
    String database = getDatabase(namespace);
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
    } catch (org.apache.gravitino.exceptions.NoSuchViewException e) {
      throw new NoSuchViewException(ident);
    }
  }

  @Override
  public View createView(
      Identifier ident,
      String comment,
      String currentCatalog,
      String[] currentNamespace,
      StructType schema,
      String[] queryColumnNames,
      String[] columnAliases,
      String[] columnComments,
      Map<String, String> properties)
      throws ViewAlreadyExistsException, NoSuchNamespaceException {
    throw new UnsupportedOperationException(
        "Gravitino Spark connector does not support creating views");
  }

  @Override
  public View alterView(Identifier ident, ViewChange... changes) throws NoSuchViewException {
    throw new UnsupportedOperationException(
        "Gravitino Spark connector does not support altering views");
  }

  @Override
  public boolean dropView(Identifier ident) {
    throw new UnsupportedOperationException(
        "Gravitino Spark connector does not support dropping views");
  }

  @Override
  public void renameView(Identifier oldIdent, Identifier newIdent)
      throws NoSuchViewException, ViewAlreadyExistsException {
    throw new UnsupportedOperationException(
        "Gravitino Spark connector does not support renaming views");
  }
}
