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
package com.datastrato.gravitino.spark.connector.iceberg.extensions;

import org.apache.spark.sql.SparkSessionExtensions;
import scala.Function1;

public class GravitinoIcebergSparkSessionExtensions
    implements Function1<SparkSessionExtensions, Void> {

  @Override
  public Void apply(SparkSessionExtensions extensions) {

    // planner extensions
    extensions.injectPlannerStrategy(IcebergExtendedDataSourceV2Strategy::new);

    // There must be a return value, and Void only supports returning null, not other types.
    return null;
  }
}