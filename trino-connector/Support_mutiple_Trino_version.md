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

## 背景
Gravitino trino-connector 当前主要面向 Trino 435–439 版本段，该版本段距离社区最新版本已相对滞后（Trino 最新版本已到 479）。随着社区用户逐步迁移到更新的 Trino 版本，connector 需要同步提升兼容范围，以满足更多用户在新版本 Trino 上部署与使用的需求。

同时，Trino 发布节奏较快、版本迭代频繁，且 SPI 接口与编译/运行所需的 JDK 版本在不同版本段之间变化明显（例如从 JDK17 演进到 JDK21/22/24）。在这种情况下，依赖单一 artifact “强行跨版本兼容”容易引入二进制不兼容与运行时不确定性，因此需要制定清晰的多版本支持策略，在构建、测试与发布层面按版本段管理兼容性。

## 目标

- **覆盖范围**：支持 Trino **>= 435**，并以“版本段”方式（如 435–439、440、455、478…）提供兼容性保障。
- **构建方式**：支持通过构建参数/任务选择目标版本段，产出对应的 connector 插件包（artifact）。
- **演进成本**：新增一个版本段的支持应当是“低成本、可控变更”（差异点集中在少量覆盖类/适配层，避免大面积复制）。
- **稳定性与体验**：保证发布、部署与回归测试流程清晰可维护。

### 方案

## 模块与源码组织
1. 将trino-connctor拆分成 trino-connecotr-common模块和trino-connecotr-435-439，trino-connector-440-4xx, xxx. 
其中trino-connecotr-common中包含 gravitno-trino-connector各版本公共部分的实现。

- **推荐：方案 A（common + 多薄壳模块）**  
  - 抽 `trino-connector-common` 只依赖稳定 SPI/自定义接口。  
  - 各版本段独立模块（示例：`trino-connector-435/440/455/478`）覆写少量差异类（同包同名覆盖或变体实现）。  
  - 边界清晰，可并行演进，差异点聚焦。
- **备用：方案 B（单模块多版本兼容）**  
  - 通过反射/条件逻辑兼容多版本，产物最少但长期维护复杂，不推荐作为长期方案。

## JDK 与依赖矩阵（按版本段锁定）
- 435–439：JDK 17（classfile 61）
- 440：JDK 21（classfile 65）
- 455：JDK 22（classfile 66）
- 478：JDK 24（classfile 68）
- 对不支持目标 classfile 的工具按模块禁用（如 Error Prone、JaCoCo）。
- 每个模块依赖对应的 `trino-spi/jdbc/testing/memory` 版本；避免 `$internal` 包，使用稳定依赖（如 Guava）。

## 版本校验策略
- `CatalogRegister` 中的 `MIN/MAX_SUPPORT_TRINO_SPI_VERSION` 随版本段设置，并保留 `gravitino.trino.skip-version-validation` 逃生阀。
- 校验逻辑可抽为静态方法便于测试；错误信息需明确说明不支持的版本。

## 主要 API 差异点（需按版本段覆写/适配）
- `setNodeCount` → `setWorkerCount`（QueryRunner）
- `getNextPage` → `getNextSourcePage` 返回 `SourcePage`
- `finishInsert` / `finishMerge` 新签名需要 unwrap `sourceTableHandles`
- `getSplitBucketFunction` 新增 `bucketCount` 参数
- `addColumn` 新增 `ColumnPosition` 参数
- `ConnectorSplit.getInfo` 移除
- `Connector.shutdown` 新增生命周期方法
- 移除对 `io.trino.*.$internal.*` 的依赖，改用稳定 API/依赖

## 构建与打包
- 每个版本段输出独立插件目录，示例：
  - `distribution/gravitino-trino-connector-trino435/`
  - `distribution/gravitino-trino-connector-trino440/`
  - `distribution/gravitino-trino-connector-trino455/`
  - `distribution/gravitino-trino-connector-trino478/`
- 任务建议：`:trino-connector:trino-connector-XXX:copyLibs` 生成对应目录；可选聚合任务 `:trino-connector:assembleTrinoConnectors` 依次构建全部版本段。

## 单元测试策略
- 矩阵：各版本段模块独立运行 `test -PskipITs`；必要时禁用 JaCoCo。
- 规则：`DistributedQueryRunner` 使用 `setWorkerCount(1)`；仅 coordinator 初始化 `CatalogRegister`；对不支持的操作使用 `assertQueryFails`，对支持的操作用 `assertUpdate`；创建资源前先 `drop ... if exists` 避免污染。

## 迁移步骤（建议顺序）
1) **基线回归**：将当前模块回归到最低段（435），校准 SPI 依赖与版本校验常量。  
2) **新增版本段模块**：为 440/455/478 等创建独立模块，复用 common 源码，必要时同包覆盖差异类。  
3) **产物目录与任务**：调整 `copyLibs` 输出目录带版本段后缀，提供聚合任务。  
4) **差异收敛**：反射/内部类调用集中在少数适配层；差异增多时把公共逻辑沉入 `trino-connector-common`。

## 当前分支状态（快速参考）
- 近期分支曾直接在单模块上升级到 440、455；若恢复多版本共存，需按上述方案拆分模块、恢复版本段校验和专属 toolchain。

## 后续建议
- 落地方案 A（common + 多模块）并补齐 435/440/455/478 四段；CI 增加对应 UT 任务。  
- 对齐用户文档，明确各版本段的 JDK/Trino 要求与兼容性警告。  
- 后续增加集成测试矩阵，验证插件在各 Trino 版本段的实际运行。

