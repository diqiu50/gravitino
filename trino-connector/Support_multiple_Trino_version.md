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

# Trino Connector Multi-Version Support Strategy

## Background

The Gravitino Trino connector previously supported Trino versions 435–439, which has fallen behind the latest community releases (as of 2024/2025, the latest is 479+).

As the community and users upgrade, significant changes in SPI/API/JDK (e.g., JDK 17→21→22→24, frequent SPI method signature changes) make single-artifact cross-version compatibility risky at runtime and significantly increase development and testing overhead.

Therefore, we adopt a "multi-module, version-segmented, independent adaptation and release" approach to continuously cover mainstream Trino ecosystems (including long-term maintenance versions and the latest community releases), balancing compatibility and engineering maintainability.

## Goals

- **Coverage**: Support Trino **>= 435**, with compatibility guarantees provided in "version segments" (e.g., 435–439, 440, 455, 478…).
- **Build approach**: Support selecting target version segments via build parameters/tasks, producing corresponding connector plugin artifacts.
- **Evolution cost**: Adding support for a new version segment should be "low-cost and controlled" (differences concentrated in a small number of override classes/adaptation layers, avoiding large-scale duplication).
- **Stability and experience**: Ensure clear and maintainable release, deployment, and regression testing processes.

## Solution Overview

### Multi-Module Layered Architecture

Split `trino-connector` into:
- `trino-connector-common`: Contains stable interfaces and shared logic (depends only on Trino official stable SPI/API), hosting the majority of common logic and common test base classes.
- `trino-connector-435-439/440-455/456-469/470-478/...`: One submodule per version segment, each depending on the corresponding Trino SPI/JDK for its target segment.

Directory structure example:

```text
trino-connector/
 ├── trino-connector-common/       # Core common logic, test base classes
 ├── trino-connector-435-439/      # 435–439 version segment (example)
 ├── trino-connector-440-455/      # 440–455 version segment
 ├── trino-connector-456-469/      # 456–469 version segment
 └── trino-connector-470-478/      # 470–478 version segment
```

The number of version segments can be adjusted based on actual Trino community compatibility requirements and breaking points.

Each version segment module:
- Implements only segment-specific differences (via same-package same-name override or explicit adapters): e.g., SPI signature change points, added/removed methods, class loading/reflection-related adaptations.
- Adds segment-specific unit tests, with common logic preferably tested in the common layer.

## JDK Dependencies and Build Configuration

Recommended compiler/runtime JDK and classfile version matrix for Trino version segments:

| Version Segment | JDK Version | Classfile |
|----------------|-------------|-----------|
| 435–439        | 17          | 61        |
| 440–446        | 21          | 65        |
| 447–455        | 22          | 66        |
| 456–469        | 22          | 66        |
| 470–478        | 24          | 68        |

Note: Trino started requiring JDK 22 from version 447 onwards.

**Engineering configuration points:**
- Project `build.gradle.kts` (or submodule build files) must precisely lock JDK toolchain for the target segment to avoid cross-contamination.
- Tool compatibility (e.g., ErrorProne, JaCoCo): if classfile support gaps are encountered, disable or adapt separately.
- Avoid referencing Trino non-public/unstable packages; all dependencies should come from official public stable APIs (e.g., Guava).
- Version switching is recommended via Gradle parameterization (e.g., `-PtrinoVersion=440`), with CI scripts supporting matrix builds.

## Key API Differences Requiring Adaptation/Isolation

Changes vary by segment but are typically concentrated. Common differences include:
- `setNodeCount` → `setWorkerCount` (QueryRunner, after JDK 21)
- `getNextPage` → `getNextSourcePage` / `SourcePage` type changes
- `finishInsert` / `finishMerge` new signatures, requiring unwrap of `sourceTableHandles`
- `getSplitBucketFunction` adds `bucketCount` parameter
- `addColumn` adds `ColumnPosition` parameter
- `ConnectorSplit.getInfo` SPI removal
- `Connector.shutdown` lifecycle interface changes

**Adaptation principles:**
- Differences must be concentrated in the control layer (e.g., adapters/override classes/bridge classes), strictly avoiding scattered implementations that lead to maintenance disasters.
- If compatibility layers require reflection-based adaptations, they must be minimal in scope (class isolation), and should not appear in mainstream branches.
- Each segment's unit tests must perform "signature assertions" on these key differences to prevent silent breaks (compile-time tests are recommended).

  > **Signature assertions explanation**: Since different Trino version segments may have different SPI method signatures (e.g., parameter count, types, return types), we need:
  > - **Compile-time checks**: Through compile tests, ensure code compiles correctly. If API signature changes cause compilation failures, issues can be detected immediately.
  > - **Runtime verification**: In unit tests, call these difference methods (e.g., `setWorkerCount`, `getNextSourcePage`, etc.) to verify methods exist and have correct signatures, preventing runtime `NoSuchMethodError` exceptions.
  > - Example: Write a simple test for `QueryRunner.setWorkerCount(1)`. If the method doesn't exist or its signature has changed, the test will fail immediately, rather than discovering the issue at runtime.

## Build/Package/Release Process

When releasing, two approaches can be adopted:
1. Release artifacts for the latest version segment only
2. Release artifacts for all version segments

Releasing only the latest version segment simplifies user choice, maintenance, and testing. However, users requiring older Trino version segments cannot directly download the corresponding Gravitino trino-connector version and must compile the older segment connectors themselves.

Releasing artifacts for all version segments meets more user needs but increases maintenance and testing costs.

### Build and Package
- Build a specific version segment plugin package through Gradle tasks (e.g., `./gradlew :assembleDistribution -Ptrino-versions=435-439`);
- Generated jars are located in the corresponding submodule's `build/libs/` directory.

```text
distribution/gravitino-trino-connector-trino435-439/
```

Simply copy the corresponding directory entirely to Trino's `${TRINO_HOME}/plugin/gravitino/` and restart to take effect.

### Release Strategy Recommendations

- Recommended: Each release supports all mainstream maintained "segment" packages (unless the Trino community explicitly abandons certain segments).
- If maintenance costs are constrained, prioritize the latest (e.g., 479 segment), with older segments maintained for long-term LTS support; very old segments may only provide source code for users to build themselves.
- CI must at minimum matrix-verify UT and end-to-end integration for all current major segments.

### User Selection Guide

Users should select the corresponding plugin package based on their Trino version:

| Trino Version | Recommended Plugin Directory (extract and rename to plugin/gravitino) |
|---------------|------------------------------------------------------------------------|
| 435–439       | gravitino-trino-connector-trino435-439                                 |
| 440–455       | gravitino-trino-connector-trino440-455                                 |
| 470–478       | gravitino-trino-connector-trino470-478                                 |

For Trino versions 478 and above, it is recommended to select the latest segment plugin package.

If encountering new/niche versions not officially supported, users can select a similar version and configure the parameter `gravitino.trino.skip-version-validation=true` to temporarily skip version validation for testing and verification purposes.

## Testing and CI

### Unit Testing

- Each segment must have difference adaptation UT (e.g., SPI breakpoint/api breakpoint UT, version adaptation exception UT); common logic is tested in `trino-connector-common`.
- Gradle subproject parameterization can be used to run all segment test cases (see practices like `-PskipITs`).
- Every PR/Release must matrix-test all supported segments.

### Integration/End-to-End Testing

- Recommended: Refactor existing docker compose test environments to implement multi-version Trino integration automated testing.
- Integration tests focus on verifying data writes/queries/metadata, ensuring connectors of different versions can complete full execution.
