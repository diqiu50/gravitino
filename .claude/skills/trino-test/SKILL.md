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

---
name: trino-test
description: Run, debug, and manage Trino integration tests for the Gravitino project.
argument-hint: "[intent] (e.g. run all | run jdbc-mysql | run 00004 | version 446 | env_only | stop | add test | add testset bigquery | debug | gen_output)"
allowed-tools: Bash
disable-model-invocation: false
---

# /trino-test — Trino Integration Test Skill

This skill helps you **run, debug, and manage Trino integration tests** for the **Gravitino** project.

Use this skill whenever the user asks about:
- running Trino integration tests (all, specific testset, catalog, or file)
- testing with a specific Trino version
- starting / stopping a manual test environment (`--env_only` / `--stop`)
- connecting to Trino CLI for interactive testing
- adding a test to an existing testset
- adding a new catalog testset (e.g. BigQuery)
- generating expected output files (`--gen_output`)
- debugging a failing test (reading logs, fixing `.txt` files)

---

## Documentation Reference

Full guide: `.claude/skills/trino-test/trino-test-guide.md`

Trino CLI wrapper (connects to `trino-ci-trino` container): `.claude/skills/trino-test/trino`

---

## Project Root

All commands run from the **Claude working directory** (project root).

---

## Quick Commands

### Run all tests
```bash
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh --auto=all
```

### Run specific test set
```bash
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh \
  --auto=all --test_set=jdbc-mysql
```

### Run specific test file
```bash
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh \
  --auto=all --test_set=jdbc-mysql --tester_id=00004
```

### Run specific catalog within testset
```bash
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh \
  --auto=all --test_set=tpch --catalog=hive
```

### Test specific Trino version
```bash
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh \
  --auto=all \
  --trino_version=<VERSION> \
  --trino_connector_dir=<WORKSPACE>/trino-connector/trino-connector-<VERSION_RANGE>/build/libs
```

### Start environment for manual testing
```bash
# Start (returns to shell when ready)
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh --auto=all --env_only

# Connect Trino CLI
.claude/skills/trino-test/trino

# Stop
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh --stop
```

## Test Structure

```
trino-connector/integration-test/src/test/resources/trino-ci-testset/testsets/
```

Each test consists of:
- `*.sql` — SQL statements to execute
- `*.txt` — Expected output (supports `%` wildcard for flexible matching)
