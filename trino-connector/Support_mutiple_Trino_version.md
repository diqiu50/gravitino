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
Gravitino Trino-connector 过去主要支持 Trino 435–439 版本段，距离最新社区 Trino 已经滞后（截至 2024/2025 年已至 479+）。

随着社区和用户逐步升级，新旧 SPI/API/JDK 的剧烈变化（如 JDK17→21→22→24，SPI 方法签名频繁改动），
单 artifact 跨大版本兼容会导致严重运行时风险、开发/测试负担急剧增加。

因此，我们采用“分 Trino 版本段多模块独立适配和发布”的模式，以持续涵盖主流（含长期维护版和社区最新）的 Trino 生态，兼顾兼容性和工程可维护性。

## 目标

- **覆盖范围**：支持 Trino **>= 435**，并以“版本段”方式（如 435–439、440、455、478…）提供兼容性保障。
- **构建方式**：支持通过构建参数/任务选择目标版本段，产出对应的 connector 插件包（artifact）。
- **演进成本**：新增一个版本段的支持应当是“低成本、可控变更”（差异点集中在少量覆盖类/适配层，避免大面积复制）。
- **稳定性与体验**：保证发布、部署与回归测试流程清晰可维护。

### 方案概述

#### 多模块分层结构

将 `trino-connector` 拆分为：
- `trino-connector-common`：承载稳定接口、共享逻辑（只依赖 Trino 官方稳定 SPI/API），存放绝大多数通用逻辑、通用测试基类；
- `trino-connector-435-439/440-455/456-469/470-478/...`：每个版本段一个子模块，依赖自己目标段对应的 Trino SPI/JDK。

目录结构示例：

```text
trino-connector/
 ├── trino-connector-common/       # 核心与共性逻辑、测试基类
 ├── trino-connector-435-439/      # 435–439 版本段（举例）
 ├── trino-connector-440-455/      # 440–455 版本段
 ├── trino-connector-456-469/      # 456–469 版本段
 └── trino-connector-470-478/      # 470–478 版本段
```

版本段多少可根据 Trino 社区实际断点和兼容需求增减。

每个版本段模块：
- 只实现（同包同名方式或者显式适配器）本段特有的差异：如 SPI 签名变动点、新删方法、类加载/反射相关 hack；
- 测试上补充本段特有的 UT，有共性逻辑优先沉入 common 层测试。

## JDK 依赖与构建配置

Trino 各版本段推荐编译器/运行 JDK 及 classfile 版本矩阵：

| 版本段     | JDK版本 | Classfile |
|---------|-------|-----------|
| 435–439 | 17    | 61        |
| 440–446 | 21    | 65        |
| 447–469 | 22    | 66        |
| 470–478 | 24    | 68        |

注意：Trino 从版本 447 开始要求使用 JDK 22。

**工程配置要点：**
- 项目 `build.gradle.kts`（或各子模块 build 文件）须精确为 target 段锁定 JDK toolchain，避免交叉；
- 工具兼容（如 ErrorProne、JaCoCo）如遇 classfile 支持断档，需单独禁用或适配。
- 版本切换建议用 gradle 参数化（如 `-PtrinoVersion=440`），CI 脚本支持矩阵构建。

## 需重点适配/隔离的 API 差异点举例

各段变化集中但不唯一，常见差异点归纳：
- `setNodeCount` → `setWorkerCount`（QueryRunner，JDK21以后）
- `getNextPage` → `getNextSourcePage` / `SourcePage` 类型变化
- `finishInsert` / `finishMerge` 新签名，需要 unwrap `sourceTableHandles`
- `getSplitBucketFunction` 新增 `bucketCount` 参数
- `addColumn` 新增 `ColumnPosition` 参数
- `ConnectorSplit.getInfo` SPI 移除
- `Connector.shutdown` 生命周期接口变化


**适配原则：**
- 差异点务必集中于控制层（如 适配器/覆写类/桥接类），坚决避免四处散落导致维护灾难。
- 若兼容层需用反射 hack，须最小范围（类隔离），主流分支杜绝出现。
- 每个版本段的代码都必须能编译，测试通过，避免改动破坏兼容性

## 构建/打包/发布流程

Relase发布时，可以采用2个方案
1. 发布最新版本段的artifact
2. 发布所有版本段的artifact

发布最新版本段的artifact可以简化用户选择，维护和测试比较简单，但用户如果需要使用老版本段的Trino就无法直接下载到对应的
Gravitino trino-connector版本。 需要用户自己编译老版本段的trino-connector。
发布所有版本段的artifact可以满足更多用户需求，但增加了维护和测试成本。


### 构建与打包
- 通过 gradle 任务（如 `./gradlew :assembleDistribution -Ptrino-versios=435-439`）单独构建某个版本段插件包；
产生的 jar 位于对应子模块的 `build/libs/` 目录下。

```text
distribution/gravitino-trino-connector-trino435-439/
```

最终只需将对应目录整体拷贝到 Trino 的 `${TRINO_HOME}/plugin/gravitino/`，重启即可生效。

### 发布策略建议
- 推荐每个 release 支持所有主流在维护“段”包（除非与 Trino 主社区明确随时放弃某些段）
- 如维护成本受限，可优先主推最新（如 479 段），老段梳理长期 LTS 持续支持；超长期可只留源码供用户自行构建
- CI 必须至少矩阵验证当前所有主要段的 UT、端到端集成等

### 用户选择指引

用户根据trino版本选择对应的插件包：
| Trino 版本 | 推荐插件目录（解压重命名为plugin/gravitino）|
| ---------- | ----------------------------------------- |
| gravitino-trino-connector-trino435-439 |
| gravitino-trino-connector-trino440-455 |
| gravitino-trino-connector-trino470-478 |

如trino-478版本以上，建议选择最新段插件包。
如遇新/冷门版本未正式支持，可用选择相近的版本，并配置参数 `gravitino.trino.skip-version-validation=true`临时跳过版本校验，以供测试验证。

## 测试与 CI

### 单元测试
- 各段必须有差异适配 UT（如各 SPI 断点/api断点 UT、版本适配异常 UT），通用逻辑在 `trino-connector-common` 测试。
- 可采用 gradle 子项目参数化运行所有段用例（见 `-PskipITs` 等实践）；
- 每次 PR/Release 必须矩阵测试所有受支持段。

### 集成/端到端测试
- 推荐改造现有docker compose测试环境, 实现多版本 Trino 集成自动测试
- 集成测试重点验证数据写入/查询/元数据联动，不同版本的 connector 能否完整生命周期跑通。
