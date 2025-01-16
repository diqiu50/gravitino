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
use clap::{Parser, Subcommand};

#[derive(Parser, Debug)]
#[command(
    name = "gvfs-fuse",
    version = "1.0",
    about = "A FUSE-based file system client"
)]
struct Arguments {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand, Debug)]
enum Commands {
    Mount {
        mount_point: String,

        target: String,

        #[arg(short, long)]
        config: Option<String>,

        #[arg(short, long)]
        debug: u8,

        #[arg(short, long)]
        foreground: bool,
    },
    Unmount {
        #[arg(short, long)]
        force: bool,
    },
}
