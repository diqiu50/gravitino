<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Trino Connector 多版本支持方案

> 参考 `TRINO_CONNECTOR_MULTI_VERSION_PLAN.md`，聚焦于 **多 Trino 版本兼容** 的实现路径与发布方式，覆盖 JDK、API 适配、单元测试、发布打包等方面。

## 目标
- 同时支持多个 Trino 版本段（例如 435–439、440、455、478）。
- 降低二进制不兼容风险，清晰管理不同版本的 API 差异与编译/测试矩阵。
- 保持发布和使用体验简单。

## 方案对比

### 方案 A：多模块 + common（推荐）
- **思路**：抽取 `trino-connector-common`，只依赖稳定 SPI/自定义接口；每个版本段单独一个模块（如 `trino-connector-435`、`trino-connector-440`、`trino-connector-455`、`trino-connector-478`），仅覆盖差异 API/类。
- **优点**：边界清晰，版本段差异集中在少量覆盖类；可以并行演进多个版本段。
- **缺点**：产物和构建数量增加，但可接受。

### 方案 B：单模块多版本兼容（例如支持 435–478）
- **思路**：单一模块通过反射/条件逻辑兼容多个 SPI 版本段。
- **优点**：产物数量最少。
- **缺点**：长期维护复杂，容易因内部 API 变动导致隐藏风险；回归成本高。**不推荐作为长期方案**，可作为临时过渡。

## JDK 与编译策略
- **按版本段锁定 JDK Toolchain**：  
  - 435–439：JDK 17（classfile 61）  
  - 440：JDK 21（classfile 65）  
  - 455：JDK 22（classfile 66）  
  - 478：JDK 24（classfile 68）  
- **Error Prone/JaCoCo**：对不支持目标 classfile 版本的工具在对应模块禁用（如 455/478）。
- 统一在 `build.gradle.kts` 中按模块名选择 toolchain 与禁用项。

## API 适配原则
- 仅在版本差异的类中做“同包同名覆盖”或 `@Override` 变体：  
  - 典型差异：`setWorkerCount`/`setNodeCount`，`getNextSourcePage`，`finishInsert`/`finishMerge` 新签名，`getInfo` 移除，`ColumnPosition` 参数，`bucketCount` 参数等。
- 禁止依赖 `$internal` 包；若版本段仍使用旧 API，改为 Guava 等稳定依赖。
- 反射/内部类调用集中在少数适配层，按模块覆盖。

## 单元测试策略
- **矩阵**：每个版本段模块独立跑 UT（`-PskipITs`），必要时禁用 JaCoCo。
- 测试用例规则：  
  - 创建资源前先 `drop ... if exists`，避免交叉污染；避免多表互相 drop schema。  
  - 对不支持的操作用 `assertQueryFails` 验证实际错误信息；对支持的操作用 `assertUpdate`。  
  - `DistributedQueryRunner` 使用 `setWorkerCount(1)`。  
  - Worker 侧不初始化 `CatalogRegister`（仅 coordinator）。
- 建议在 CI 增加各版本段的 UT 任务，如：  
  - `:trino-connector:trino-connector-435:test -PskipITs`  
  - `:trino-connector:trino-connector-440:test -PskipITs`  
  - `:trino-connector:trino-connector-455:test -PskipITs`  
  - `:trino-connector:trino-connector-478:test -PskipITs`

## 发布与打包
- 每个版本段输出独立插件目录：  
  - `distribution/gravitino-trino-connector-trino435/`  
  - `distribution/gravitino-trino-connector-trino440/`  
  - `distribution/gravitino-trino-connector-trino455/`  
  - `distribution/gravitino-trino-connector-trino478/`
- Gradle 任务建议：  
  - `:trino-connector:trino-connector-XXX:copyLibs` 生成对应目录  
  - 聚合任务（可选）`:trino-connector:assembleTrinoConnectors` 依次构建所有版本段。
- 版本校验：在各模块的 `CatalogRegister` 中设置对应的 `MIN/MAX_SUPPORT_TRINO_SPI_VERSION`，保留 `gravitino.trino.skip-version-validation` 逃生阀。

## 路线建议
- **优先方案 A**：建立 `common` + 多版本段薄壳模块，逐步把当前单模块的版本差异拆分出去。
- 对现有 435–478 的兼容需求，可先落地 435、440、455、478 四个模块；若确需 435–478 跨段单模块，作为临时方案保留但降低优先级。


