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

package org.apache.gravitino.listener.api.event;

import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;
import org.apache.gravitino.listener.api.info.CatalogInfo;

/** Represents an event triggered upon the successful loading of a catalog. */
@DeveloperApi
public final class LoadCatalogEvent extends CatalogEvent {
  private final CatalogInfo loadedCatalogInfo;

  /**
   * Constructs an instance of {@code LoadCatalogEvent}.
   *
   * @param user The username of the individual who initiated the catalog loading.
   * @param identifier The unique identifier of the catalog that was loaded.
   * @param loadedCatalogInfo The state of the catalog post-loading.
   */
  public LoadCatalogEvent(String user, NameIdentifier identifier, CatalogInfo loadedCatalogInfo) {
    super(user, identifier);
    this.loadedCatalogInfo = loadedCatalogInfo;
  }

  /**
   * Retrieves the state of the catalog as it was made available to the user after successful
   * loading.
   *
   * @return A {@link CatalogInfo} instance encapsulating the details of the catalog as loaded.
   */
  public CatalogInfo loadedCatalogInfo() {
    return loadedCatalogInfo;
  }

  /**
   * Returns the type of operation.
   *
   * @return the operation type.
   */
  @Override
  public OperationType operationType() {
    return OperationType.LOAD_CATALOG;
  }
}
