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
mod cloud_storage_filesystem;
mod config;
mod error;
mod filesystem;
mod filesystem_metadata;
mod fuse_api_handle;
mod fuse_server;
mod gravitino_client;
mod gravitino_compose_filesystem;
mod gravitino_filesystem;
mod log_fuse_api_handle;
mod memory_filesystem;
mod opened_file_manager;
mod storage_filesystem;
mod utils;
use crate::config::Config;
use crate::fuse_server::FuseServer;
use fuse3::Result;
use log::{debug, info};
use std::sync::Arc;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter("info,gvfs_fuse=debug,fuse3=warn")
        .init();

    debug!("Starting gvfs-fuse server...");
    let server = Arc::new(FuseServer::new("gvfs"));
    let clone_server = server.clone();

    let config = Config::default();
    let v = tokio::spawn(async move { clone_server.start(&config).await });

    tokio::signal::ctrl_c().await?;
    info!("Received Ctrl+C, stopping server...");
    server.stop().await?;
    v.await.ok();
    Ok(())
}
