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

package org.apache.gravitino.spark.connector.hive;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.spark.connector.GravitinoSparkConfig;
import org.apache.gravitino.spark.connector.PropertiesConverter;
import org.apache.gravitino.spark.connector.SparkTransformConverter;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.gravitino.spark.connector.catalog.BaseCatalog;
import org.apache.gravitino.spark.connector.iceberg.IcebergPropertiesConstants;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.spark.SparkCatalog;
import org.apache.kyuubi.spark.connector.hive.HiveTable;
import org.apache.kyuubi.spark.connector.hive.HiveTableCatalog;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.TableCatalog;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

public class GravitinoHiveCatalog extends BaseCatalog {

  // HMS stores the Iceberg marker in table parameters as table_type=ICEBERG.
  // HiveTableConverter copies all HMS parameters into gravitinoTable.properties(), so the key is
  // "table_type" (underscore). This differs from HiveConstants.TABLE_TYPE = "table-type" (hyphen),
  // which maps the HMS tableType field (MANAGED_TABLE/EXTERNAL_TABLE).
  // Reference: HiveTable.TABLE_TYPE_PROP = "table_type", ICEBERG_TABLE_TYPE_VALUE = "ICEBERG"
  private static final String PROP_TABLE_TYPE = "table_type";

  private static final String ICEBERG_TABLE_TYPE = "ICEBERG";

  // Saved during createAndInitSparkCatalog() for lazy Iceberg backing catalog initialization.
  private String savedHmsUri;

  // Lazy-initialized Iceberg SparkCatalog that handles Iceberg tables stored in HMS.
  private volatile TableCatalog icebergBackingCatalog;

  @Override
  protected TableCatalog createAndInitSparkCatalog(
      String name, CaseInsensitiveStringMap options, Map<String, String> properties) {
    Map<String, String> all =
        getPropertiesConverter().toSparkCatalogProperties(options, properties);
    // Save HMS URI for lazy Iceberg backing catalog initialization on first Iceberg table access.
    this.savedHmsUri = all.get(GravitinoSparkConfig.SPARK_HIVE_METASTORE_URI);
    TableCatalog hiveCatalog = new HiveTableCatalog();
    hiveCatalog.initialize(name, new CaseInsensitiveStringMap(all));
    return hiveCatalog;
  }

  @Override
  protected org.apache.spark.sql.connector.catalog.Table createSparkTable(
      Identifier identifier,
      Table gravitinoTable,
      org.apache.spark.sql.connector.catalog.Table sparkTable,
      TableCatalog sparkHiveCatalog,
      PropertiesConverter propertiesConverter,
      SparkTransformConverter sparkTransformConverter,
      SparkTypeConverter sparkTypeConverter) {
    if (isIcebergTable(gravitinoTable)) {
      try {
        return getOrCreateIcebergBackingCatalog().loadTable(identifier);
      } catch (NoSuchTableException e) {
        throw new RuntimeException(
            "Failed to load Iceberg table via Iceberg backing catalog: " + identifier, e);
      }
    }
    return new SparkHiveTable(
        identifier,
        gravitinoTable,
        (HiveTable) sparkTable,
        (HiveTableCatalog) sparkHiveCatalog,
        propertiesConverter,
        sparkTransformConverter,
        sparkTypeConverter);
  }

  @Override
  protected PropertiesConverter getPropertiesConverter() {
    return HivePropertiesConverter.getInstance();
  }

  @Override
  protected SparkTransformConverter getSparkTransformConverter() {
    return new SparkTransformConverter(false);
  }

  @Override
  protected SparkTypeConverter getSparkTypeConverter() {
    return new SparkHiveTypeConverter();
  }

  private boolean isIcebergTable(Table gravitinoTable) {
    Map<String, String> props = gravitinoTable.properties();
    return props != null && ICEBERG_TABLE_TYPE.equalsIgnoreCase(props.get(PROP_TABLE_TYPE));
  }

  private TableCatalog getOrCreateIcebergBackingCatalog() {
    if (icebergBackingCatalog == null) {
      synchronized (this) {
        if (icebergBackingCatalog == null) {
          SparkCatalog catalog = new SparkCatalog();
          Map<String, String> props = new HashMap<>();
          // CatalogUtil.ICEBERG_CATALOG_TYPE = "type" (public Iceberg API)
          // IcebergPropertiesConstants.ICEBERG_CATALOG_BACKEND_HIVE = "hive" (public)
          // CatalogProperties.URI = "uri" (public Iceberg API)
          props.put(
              CatalogUtil.ICEBERG_CATALOG_TYPE,
              IcebergPropertiesConstants.ICEBERG_CATALOG_BACKEND_HIVE);
          if (savedHmsUri != null) {
            props.put(CatalogProperties.URI, savedHmsUri);
          }
          catalog.initialize(name() + "_iceberg_backing", new CaseInsensitiveStringMap(props));
          icebergBackingCatalog = catalog;
        }
      }
    }
    return icebergBackingCatalog;
  }
}
