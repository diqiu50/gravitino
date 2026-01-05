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

# Trino Connector Multi-Version Support Plan

## Background

The Gravitino Trino connector previously mainly supported the Trino 435–439 version range, which now lags behind the latest Trino community releases (as of 2024/2025, versions are already at 479+).

As the community and users upgrade, differences between SPI/API/JDK (for example, JDK 17→21→22→24 and frequent SPI signature changes) grow rapidly. A single artifact that tries to be compatible across many major Trino versions introduces serious runtime risks and significantly increases development and testing effort.

Therefore, we adopt a model of "splitting by Trino version ranges, with independent adaptation and release for each module", so that we can continuously cover mainstream Trino ecosystems (including long-term maintenance releases and the latest community versions) while balancing compatibility and engineering maintainability.

## Goals

- **Coverage**: Support Trino **>= 435**, and provide compatibility guarantees by grouping versions into clearly defined "version ranges" (for example, 435–439, 440–455, 470–478).
- **Build**: Support selecting target version ranges via build parameters/tasks, and produce the corresponding connector plugin artifacts.
- **Evolution cost**: Keep the cost of adding a new version range low and controlled by concentrating differences in a small number of override/adapter layers and avoiding large-scale code duplication.
- **Stability**: Keep release, deployment, and regression testing processes clear and easy to maintain.

### Plan Overview

#### Multi-Module Layered Structure

In some Trino version ranges, the SPI is relatively stable. For example, when using trino-spi-435 to 439, the interfaces are largely consistent. Based on these stable SPI ranges, we split the trino-connector into a common module and per-range modules:

- `trino-connector-common`: Hosts stable interfaces and shared logic (only depends on Trino official stable SPI/API), and contains most common logic and common test base classes.
- `trino-connector-435-439/440-455/456-469/470-478/...`: One submodule per version range, each depending on the corresponding Trino SPI/JDK for its target range.

Example directory layout:

```text
trino-connector/
 ├── trino-connector-common/       # Core shared logic and test bases
 ├── trino-connector-435-439/      # 435–439 range (example)
 ├── trino-connector-440-455/      # 440–455 range
 ├── trino-connector-456-469/      # 456–469 range
 └── trino-connector-470-478/      # 470–478 range
```

The number and granularity of version ranges can be adjusted based on actual Trino compatibility needs in the community.

Each version-range module:
- Implements only the differences specific to that range, such as SPI signature changes, added/removed methods, or class loading/reflection-related hacks.
- Adds unit tests for range-specific behavior, while common logic is preferably tested in the common module.
- For ranges where the SPI is relatively stable, the module generally uses the first Trino version in that range as its dependency.

## API Adaptation

Changes are concentrated but not identical across ranges. Common differences include:
- `setNodeCount` -> `setWorkerCount` (QueryRunner, after JDK 21)
- `getNextPage` -> `getNextSourcePage` / `SourcePage` type changes
- `finishInsert` / `finishMerge` new signatures, requiring unwrap of `sourceTableHandles`
- `getSplitBucketFunction` adds a `bucketCount` parameter
- `addColumn` adds a `ColumnPosition` parameter
- `ConnectorSplit.getInfo` SPI removal
- `Connector.shutdown` lifecycle interface changes

**Adaptation principles:**
- Always concentrate version differences in the control layer (for example, adapters, override classes, or bridge classes), and avoid scattering them throughout the codebase.
- If the compatibility layer must rely on reflection or similar hacks, keep its scope as small as possible and avoid placing it on the main execution path.
- Code in each maintained version range must always compile and have passing tests to ensure that new changes do not break existing compatibility.

## Build and Packaging

### JDK Dependencies

The table below shows the recommended JDK and classfile versions for each Trino version range:

| Version Range | JDK Version | Classfile |
|--------------|------------|-----------|
| 435–439      | 17         | 61        |
| 440–446      | 21         | 65        |
| 447–469      | 22         | 66        |
| 470–478      | 24         | 68        |

### Engineering Configuration Points

- Support Gradle-parameterized builds; for example, use `-PtrinoVersions=435-449,470–478` to control which Trino connector ranges are built.
- Ensure that the build script `build.gradle.kts` can set the JDK version accurately for each target range.
- Be aware that toolchain compatibility (for example, ErrorProne, JaCoCo, JUnit) may be affected by the JDK; upgrade these tools as needed to support newer JDKs.
- Use Gradle tasks (for example, `./gradlew :assembleDistribution -PtrinoVersions=435-439,470–478`) to build one or more version-range connector plugins. The generated jars are located under each submodule's `build/libs/` directory.

```text
distribution/gravitino-trino-connector-435-439-1.1.2.tar.gz
distribution/gravitino-trino-connector-470-478-1.1.2.tar.gz
```

To deploy, extract the corresponding archive and copy its contents into Trino's `${TRINO_HOME}/plugin/gravitino/` directory, then restart Trino.

## Testing and CI

### Unit Testing

- Each version range must have unit test coverage; common test logic should live in `trino-connector-common`.
- For every PR and release, run a test matrix that covers all supported ranges and, within each range, at least the first and last Trino versions.

### Integration Testing

- Refactor the existing Docker Compose–based test environment to support automated integration testing across multiple Trino versions.
- For every PR, at minimum test the latest Trino version in the newest supported range.
- For every release, test at least the first and last Trino versions in each supported range.

## Packaging and Release

When publishing releases, there are two options:
1. Publish artifacts only for the latest version range.
2. Publish artifacts for multiple version ranges that are still within the support window.

Publishing only the latest range simplifies user choice and keeps maintenance and testing costs relatively low. However, users on older Trino ranges will not find a ready-made connector and will need to build the older range modules themselves.

Publishing multiple ranges over the last _n_ years (for example, 2 years, since users typically do not upgrade Trino clusters very frequently) covers more use cases but increases maintenance and testing cost.

### Release Strategy Recommendations

- Prefer to cover all still-maintained mainstream version ranges in each release (unless there is a clear decision to drop some ranges in coordination with the Trino community).
- If maintenance resources are limited, prioritize the latest range (for example, the 479 range), keep only long-term LTS ranges fully supported, and leave very old ranges available only as source for users to build themselves.

### User Selection Guide

Users can select the connector package according to the Trino version they run:

| Trino Version Range |
|---------------------|
| 435–439             |
| 440–455             |
| 470–478             |

For Trino versions 475, it is recommended to use the latest version range connector.

If a newer or less common Trino version is not yet explicitly marked as supported, users can choose the closest version range and use the configuration `gravitino.trino.skip-version-validation=true` to temporarily skip version checks for evaluation.
