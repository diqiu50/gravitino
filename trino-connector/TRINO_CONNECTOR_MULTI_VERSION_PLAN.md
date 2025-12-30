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

### 目标

在 Apache Gravitino 仓库中，将 `trino-connector` 按 **Trino 版本段**发布多个 connector artifact（插件包），以避免“单 artifact 强行兼容多版本 Trino”带来的二进制不兼容与运行时不确定性。

- **最低支持版本**：Trino 435
- **近期目标版本段**：先补齐 Trino 478（后续可继续扩展新的版本段）
- **测试**：本方案先定义测试矩阵与落点，具体实现与 CI 集成后续再处理

---

### 背景与问题

单一 connector artifact 要跨多个 Trino 版本运行，主要风险来自：

- **二进制兼容性**：connector 用某个 `trino-spi` 编译后，换到其他版本 Trino 上可能触发 `NoSuchMethodError` / `NoClassDefFoundError` 等运行时错误。
- **内部/非稳定 API 依赖**：例如对 `io.trino.server.*`、`io.trino.metadata.*` 等内部类的反射依赖；以及 `io.trino.jdbc.$internal.*` 这类内部 shading 包名依赖。
- **运行时环境差异**：Trino 发行包/插件 classpath 变化会导致某些依赖（如 trino-jdbc）在某些版本上不可用或行为变化。

因此采用“按 Trino 版本段发布多个 artifact”更可控：每个 artifact 仅需保证与对应版本段的 Trino SPI/行为一致。

---

### 总体方案（按版本段多 artifact）

#### 产物（artifact）形态

每个版本段发布一个“Trino 插件目录包”（目录下包含 connector jar + 运行时依赖 jar）。

建议产物命名规则（示例）：

- **Trino 435 段**：`gravitino-trino-connector-trino435/`
- **Trino 478 段**：`gravitino-trino-connector-trino478/`

> 说明：Trino 插件的标准部署方式是把一个目录放到 `TRINO_HOME/plugin/<plugin-name>/`。因此“artifact”可以是一个目录（或打成 tar/zip 目录包），目录名可带版本段。

#### Gradle 模块组织

采用“一个基线模块 + 多个版本段模块”的方式：

- **基线模块（Trino 435 段）**：保留现有 `:trino-connector:trino-connector`，将其依赖与版本校验回归到 Trino 435 段（最低支持）。
- **新增版本段模块（Trino 478 段）**：新增 `:trino-connector:trino-connector-478`（模块名可按约定调整），其：
  - 依赖使用 `trino-spi:478`（以及对应的 `trino-testing/trino-memory` 若需要）
  - 复用大部分源码（见下节“源码复用策略”）
  - 仅在确有差异的地方做覆盖/分支实现

后续增加版本段只需追加模块：`trino-connector-XXX`。

---

### 源码复用策略（避免大面积复制）

优先选择以下两种方式之一：

#### 方案 A（推荐）：共享源码目录 + 少量覆盖类

各版本段模块的 `sourceSets` 指向同一份共享源码目录（例如 `trino-connector/trino-connector/src/main/java`），并允许版本段模块额外添加一个 `src/main/java` 覆盖目录来放差异类（同包同类名覆盖编译输出）。

- **优点**：不引入额外 jar，插件目录结构更简单。
- **缺点**：需要谨慎管理“覆盖类”，避免不小心覆盖过多。

#### 方案 B：抽公共 jar（`trino-connector-common`）+ 版本段薄壳 jar

把大部分逻辑下沉到 `:trino-connector:trino-connector-common`（不依赖 Trino 内部类，只依赖稳定 SPI 或抽象接口），再由各版本段模块提供薄壳实现（绑定具体 Trino SPI/内部适配）。

- **优点**：结构清晰、边界明确，利于长期演进。
- **缺点**：插件目录里会多一个 common jar（通常可接受）。

本阶段优先落地 **方案 A**，等差异点增多再演进到方案 B。

---

### 版本段差异点的处理原则

#### 1) 版本校验逻辑

把版本校验从“全局硬编码范围”调整为“每个版本段模块自洽”：

- 435 段模块：允许范围覆盖 435（以及同段内你们要支持的上限）
- 478 段模块：允许范围覆盖 478（以及同段内你们要支持的上限）

推荐做法：

- 在 `CatalogRegister` 中将 `MIN/MAX_SUPPORT_TRINO_SPI_VERSION` 作为**版本段可配置/可覆盖点**（通过覆盖类或 build-time 常量）
- 对无法支持的版本给出明确错误信息（并保留 `gravitino.trino.skip-version-validation` 作为紧急逃生阀）

#### 2) 禁止依赖 `$internal` 包名

禁止引用 `io.trino.*.$internal.*`（例如 `io.trino.jdbc.$internal.guava...`），必须改用稳定依赖（如 `com.google.common.*`）。

#### 3) 内部类反射与兼容性

任何对 Trino 内部类/构造器签名的反射（例如 `io.trino.server.PluginClassLoader`、`io.trino.metadata.*`）都应视为“版本段敏感点”：

- **优先**：使用 Trino SPI 暴露能力（`ConnectorContext` 提供的对象/服务）
- **其次**：把反射逻辑集中在少数类中，按版本段模块分别实现或覆盖

---

### 构建与发布（本地/CI）

#### 目标：一条命令能产出多个版本段插件包

建议的 Gradle 任务形态：

- `:trino-connector:trino-connector:copyLibs` 产出 `...-trino435/`
- `:trino-connector:trino-connector-478:copyLibs` 产出 `...-trino478/`
- 顶层聚合任务（可选）：`:trino-connector:assembleTrinoConnectors` 依次构建所有版本段

产物目录建议放在：

- `distribution/gravitino-trino-connector-trino435/`
- `distribution/gravitino-trino-connector-trino478/`

> 当前 `copyLibs` 固定写到 `distribution/${rootProject.name}-trino-connector`，需要改为“带版本段后缀”的目录，避免互相覆盖。

---

### 测试策略（先定义，后续实现）

本方案后续建议的最低测试覆盖：

- **编译**：每个版本段模块都能独立编译通过
- **单测**：每个版本段模块能跑单测（如 `TestGravitinoConnector*`）
- **最小集成验证（后续）**：每个版本段至少启动对应版本 Trino 并验证：
  - 插件加载成功
  - `CREATE CATALOG ... USING gravitino`（动态 catalog）路径可用
  - system tables / stored procedures 基本可用

测试矩阵建议先覆盖：

- Trino 435（最低支持）
- Trino 478（目标新版本）

---

### 迁移步骤（建议按顺序落地）

- **Step 1：基线回归（trino435）**
  - 将 `:trino-connector:trino-connector` 的 `trino-spi` 等依赖统一为 435
  - 修正版本校验常量（允许 435 段）
- **Step 2：新增 trino478 版本段模块**
  - 新增 `:trino-connector:trino-connector-478`
  - 复用共享源码，必要时覆盖少数差异类
  - 依赖使用 `trino-spi:478`
- **Step 3：产物目录与发布命名**
  - 调整 `copyLibs` 输出目录为带版本段后缀
- **Step 4：逐步收敛差异点**
  - 内部反射逻辑按版本段拆分/覆盖
  - 后续再把共用逻辑抽到 common（如需要）

---

## Trino 478 升级实施记录

### 实施方式

**注意**：当前实施采用**直接升级现有模块**的方式，而非创建独立的 `trino-connector-478` 模块。这意味着当前代码库直接支持 Trino 478，后续如需支持多版本段，可参考本方案创建独立模块。

### 版本与依赖升级

#### Trino 版本
- **从**：Trino 435-439
- **到**：Trino 478
- **版本校验常量**：
  ```java
  MIN_SUPPORT_TRINO_SPI_VERSION = 478
  MAX_SUPPORT_TRINO_SPI_VERSION = 478
  ```

#### JDK 版本要求
- **trino-connector 模块编译 JDK**：从 JDK 17 升级到 **JDK 24**
  - Trino 478 使用 JDK 24 编译，connector 必须使用相同 JDK 版本以保证二进制兼容性
  - 配置位置：`build.gradle.kts` 中 trino-connector 模块的 toolchain 配置

#### 依赖更新
```kotlin
// build.gradle.kts
implementation("io.trino:trino-jdbc:478")
compileOnly("io.trino:trino-spi:478")
testImplementation("io.trino:trino-memory:478")
testImplementation("io.trino:trino-testing:478")
```

### API 变更适配清单

#### 1. Connector API 变更

| API 变更 | 文件位置 | 说明 |
|---------|---------|------|
| `setNodeCount()` → `setWorkerCount()` | `TestGravitinoConnector*.java` | QueryRunner 构建方法变更 |
| `getNextPage()` → `getNextSourcePage()` 返回 `SourcePage` | `GravitinoSystemConnector.java` | ConnectorPageSource API 变更 |
| `getSplitBucketFunction()` 新增 `bucketCount` 参数 | `GravitinoNodePartitioningProvider.java` | 方法签名变更 |
| `addColumn()` 新增 `ColumnPosition` 参数 | `GravitinoMockServer.java` | 必须指定列位置 |
| 移除 `getInfo()` 方法 | `GravitinoSplit.java`, `GravitinoSystemConnector.java` | ConnectorSplit 接口变更 |
| 新增 `shutdown()` 方法 | `GravitinoConnector.java`, `GravitinoSystemConnector.java` | Connector 接口新增生命周期方法 |

#### 2. 内部 API 修复

- **移除 `$internal` 包依赖**：
  - `MySQLPropertyMeta.java`：`io.trino.jdbc.$internal.guava.*` → `com.google.common.*`
  
- **反射方法修复**：
  - `TypeSignatureDeserializer.java`：
    - 添加 `setAccessible(true)` 设置方法可访问
    - 修复硬编码问题：使用实际 `value` 参数而非 `"varchar(255)"`

#### 3. 代码逻辑优化

- **CatalogRegister**：
  - 版本校验逻辑提取为静态方法 `validateTrinoSpiVersion()`，便于测试
  - 移除 `isCoordinator()` 方法，改用 `context.getCurrentNode().isCoordinator()`
  - 改进错误处理和日志格式（使用 `{}` 占位符）

- **GravitinoConnector**：
  - `CatalogConnectorMetadata` 实例化提前到构造函数，避免重复创建
  - 新增 `shutdown()` 方法清理资源

- **序列化优化**：
  - `BlockJsonSerde.java`：增加缓冲区大小（+1024 bytes）用于编码元数据

### 构建配置调整

#### 1. Error Prone 禁用
```kotlin
// build.gradle.kts
options.errorprone.isEnabled.set(project.name != "trino-connector")
```
- **原因**：Error Prone 2.10.0 不兼容 JDK 24 的 javac
- **影响范围**：仅 trino-connector 模块

#### 2. JaCoCo 禁用
```kotlin
// build.gradle.kts
if (project.name == "trino-connector") {
  extensions.configure<JacocoTaskExtension> {
    isEnabled = false
  }
}
```
- **原因**：JaCoCo < 0.8.13 不支持 JDK 24 class files (major 68)
- **影响范围**：仅 trino-connector 模块的单元测试

#### 3. 测试依赖排除
- 排除 JUnit 4 相关依赖（`junit`, `junit-vintage`, `junit-platform`），统一使用 JUnit 5
- 添加 `testImplementation(libs.junit.jupiter.api)` 显式依赖

### 测试框架迁移

#### JUnit 4 → JUnit 5 迁移

| 变更项 | 示例 |
|-------|------|
| 移除 `@BeforeClass/@AfterClass` | `TestGravitinoConfig.java` |
| `Assert.*` → `Assertions.*` | `TestHiveDataTypeConverter.java`, `TestPostgreSQLDataTypeTransformer.java` |
| `Assert.assertThrows` → `Assertions.assertThrows` | 多个测试文件 |
| 添加 `@Execution(SAME_THREAD)` | `TestGravitinoConnector.java` |

#### 测试用例修复

- **TestGravitinoConnector.testAlterTable**：
  - 修复列重命名冲突（避免与已存在的列名冲突）
  - 使用 `assertQueryFails` 验证不支持的操作（如修改列类型、设置表属性）

### 文档更新

- **requirements.md**：更新 Trino 版本要求为 Trino-server-478
- **configuration.md**：更新版本说明和兼容性警告

### 新增测试

- **TestCatalogRegisterTrinoVersionValidation**：
  - 测试支持的版本（478）
  - 测试不支持的版本（435）
  - 测试跳过版本校验配置
  - 测试无效 SPI 版本格式

### 主要挑战与解决方案

#### 1. JDK 24 兼容性
- **问题**：Error Prone 和 JaCoCo 工具不支持 JDK 24
- **解决**：针对 trino-connector 模块禁用这些工具

#### 2. API 方法签名变更
- **问题**：多个 SPI 接口方法签名变更
- **解决**：逐一适配新 API，确保编译和运行时兼容

#### 3. 测试框架迁移
- **问题**：JUnit 4 到 JUnit 5 的迁移
- **解决**：系统性地替换断言和注解，确保测试行为一致

#### 4. 内部 API 依赖
- **问题**：`$internal` 包名依赖不稳定
- **解决**：移除所有 `$internal` 依赖，改用稳定依赖（如 Guava）

### 后续工作建议

1. **多版本段支持**：
   - 如需同时支持 Trino 435 和 478，可创建独立的 `trino-connector-478` 模块
   - 参考本方案文档的"源码复用策略"章节

2. **测试覆盖**：
   - 增加集成测试验证 Trino 478 的实际运行
   - 验证所有 Connector 功能在 Trino 478 上的正确性

3. **文档完善**：
   - 更新用户文档说明 Trino 478 的特定配置要求
   - 记录已知的兼容性问题和限制

4. **CI/CD 集成**：
   - 在 CI 中增加 Trino 478 的测试矩阵
   - 确保每次构建都验证 Trino 478 兼容性


