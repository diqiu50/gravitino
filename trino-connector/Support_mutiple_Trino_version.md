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

```text
trino-connector/
 ├── trino-connector-common/        # 公共代码与接口
 ├── trino-connector-435-439/           # 435–439 版本段
 ├── trino-connector-440-455/           # 440 版本段
 ├── trino-connector-456-469/           # 456 版本段
 └── trino-connector-470-478/           # 470 版本段
```
这里假设有四个版本段需要支持，实际可根据需要增减。

`trino-connector-common` 只依赖稳定SPI/自定义接口, 以及Gravitino Trino connector的核心实现，以及测试的公共部分
`各版本段独立模块` 写少量差异类（同包同名覆盖或变体实现）和单元测试，并依赖 `trino-connector-common`。

## JDK 与 classfile 版本
Trino 各版本段对应的 JDK 与 classfile 版本如下：
- 435–439：JDK 17（classfile 61）
- 440：JDK 21（classfile 65）
- 455：JDK 22（classfile 66）
- 478：JDK 24（classfile 68）

当前Grravitino支持的工程的JDK版本仅支持JDK17，Trino connector的多版本支持需要升级到JDK,21, 22, 24, 在Trino connector模块的build.gradle中
toolchain 中根据用户参数中制定的Trino connector版本， 设置对应的JDK 版本,和用的工具版本，确保编译产物符合目标 Trino 版本段的运行时要求。

## 主要 API 差异点（需按版本段覆写/适配）
- `setNodeCount` → `setWorkerCount`（QueryRunner）
- `getNextPage` → `getNextSourcePage` 返回 `SourcePage`
- `finishInsert` / `finishMerge` 新签名需要 unwrap `sourceTableHandles`
- `getSplitBucketFunction` 新增 `bucketCount` 参数
- `addColumn` 新增 `ColumnPosition` 参数
- `ConnectorSplit.getInfo` 移除
- `Connector.shutdown` 新增生命周期方法

## 打包/发布
每个版本段输出独立插件目录，示例：
```text
distribution/gravitino-trino-connector-trino435-439
distribution/gravitino-trino-connector-trino440-455
distribution/gravitino-trino-connector-trino456-469
distribution/gravitino-trino-connector-trino470-478
```
在relase发布时，可以采用2个方案
1. 发布最新版本段的artifact
2. 发布所有版本段的artifact
发布最新版本段的artifact可以简化用户选择，维护和测试比较简单，但用户如果需要使用老版本段的Trino就无法直接下载到对应的Gravitino trino-connector版本。
需要用户自己编译老版本段的trino-connector。
发布所有版本段的artifact可以满足更多用户需求，但增加了维护和测试成本。

## 测试
- **单元测试**：各版本段模块包含独立的单元测试，覆盖差异类与适配逻辑。公共逻辑在 `trino-connector-common` 中测试。
- **集成测试**：
集成测试不依赖trino版本，增加集成测试的docker compose中增加trino version的配置项，通过脚本测试不同版本段的兼容性。
