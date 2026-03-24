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

# Trino Integration Test Guide

> SQL-file-driven integration tests: run `.sql` files against real Trino + Gravitino containers and compare output against `.txt` files.

## Quick Reference

```bash
# Run all tests
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh --auto=all

# Run one testset
... --auto=all --test_set=jdbc-mysql

# Run one test file
... --auto=all --test_set=jdbc-mysql --tester_id=00004

# Start environment for manual testing (returns to shell when ready)
... --auto=all --env_only

# Stop background environment
... --stop
```

---

## Part 1: Workflows

### 1.1 Run Tests

```bash
SCRIPT=./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh

# All tests
$SCRIPT --auto=all

# Specific testset
$SCRIPT --auto=all --test_set=jdbc-mysql

# Specific catalog within testset
$SCRIPT --auto=all --test_set=tpch --catalog=mysql

# Specific test file
$SCRIPT --auto=all --test_set=jdbc-mysql --tester_id=00004

# Ignore failures and continue
$SCRIPT --auto=all --ignore_failed

# Specific Trino version
$SCRIPT --auto=all --trino_version=452 \
  --trino_connector_dir=/path/to/trino-connector-452/build/libs

# Connect to existing services (no auto-start)
$SCRIPT --auto=none \
  --gravitino_uri=http://10.3.21.12:8090 \
  --trino_uri=http://10.3.21.12:8080 \
  --mysql_uri=jdbc:mysql://10.3.21.12:3306 \
  --test_set=jdbc-mysql
```

### 1.2 Manual Testing (env_only)

Start the full environment without running any tests. **Always use `--auto=all`.**

```bash
# 1. Start (returns to shell once ready)
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh \
  --auto=all --env_only

# 2. Connect to Trino CLI
.claude/skills/trino-test/trino

# 3. Stop when done
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh --stop
```

Expected output after startup:
```
Starting environment, please wait...
=======================================================
Environment is ready for manual testing.
  Gravitino URI : http://127.0.0.1:8090
  Trino URI     : http://127.0.0.1:8080
Connect to Trino CLI:
  .claude/skills/trino-test/trino
=======================================================
Environment is running in background (PID: 12345)
Logs  : .../integration-test-common/build/trino-test-env.log
Stop  : ... --stop
$
```

### 1.3 Add a Test to an Existing Testset

```bash
# 1. Create SQL file
vi src/test/resources/trino-ci-testset/testsets/jdbc-mysql/00010_new_test.sql

# 2. Generate expected output
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh \
  --auto=all --test_set=jdbc-mysql --tester_id=00010 --gen_output

# 3. Copy generated .txt to src (gen_output writes to build/, not src/)
cp trino-connector/integration-test/build/resources/test/trino-ci-testset/testsets/jdbc-mysql/00010_new_test.txt \
   trino-connector/integration-test/src/test/resources/trino-ci-testset/testsets/jdbc-mysql/00010_new_test.txt

# 4. Review and adjust with % wildcards, then verify
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh \
  --auto=all --test_set=jdbc-mysql --tester_id=00010
```

### 1.4 Add a New Catalog Testset

```bash
TESTSETS=trino-connector/integration-test/src/test/resources/trino-ci-testset/testsets

# 1. Create directory
mkdir -p $TESTSETS/<catalog-type>/

# 2. Create catalog_<name>_prepare.sql
cat > $TESTSETS/<catalog-type>/catalog_<name>_prepare.sql << 'EOF'
call gravitino.system.create_catalog(
    'gt_<name>',
    '<provider>',
    map(array['key1'], array['${param1}'])
);
EOF

# 3. Create catalog_<name>_cleanup.sql
cat > $TESTSETS/<catalog-type>/catalog_<name>_cleanup.sql << 'EOF'
DROP SCHEMA IF EXISTS gt_<name>.gt_db1;
call gravitino.system.drop_catalog('gt_<name>');
EOF

# 4. Write numbered test files (00000_*.sql + 00000_*.txt pairs)
#    Use --env_only to capture real output for .txt files:
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh \
  --auto=all --env_only --params=param1,value1

docker exec trino-ci-trino trino --output-format CSV_UNQUOTED <<'EOF'
CREATE SCHEMA gt_<name>.gt_db1;
EOF
# → write the output to 00000_create_schema.txt

# 5. Run to verify
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh \
  --auto=all --test_set=<catalog-type> --params=param1,value1
```

### 1.5 Debug a Failing Test

```bash
# 1. Run the failing test alone
./trino-connector/integration-test/trino-test-tools/trino_integration_test.sh \
  --auto=all --test_set=jdbc-mysql --tester_id=00004

# 2. Read failure output — shows Sql / Expect / Actual
cat integration-test-common/build/integration-test-common-integration-test.log

# 3. Fix: update .txt with % wildcard for variable parts, then re-run
```

Log locations:
```bash
# Test execution log
integration-test-common/build/integration-test-common-integration-test.log

# Container logs
integration-test-common/build/trino-ci-container-log/trino.log
integration-test-common/build/trino-ci-container-log/hive/
integration-test-common/build/trino-ci-container-log/hdfs/

# env_only background process log
integration-test-common/build/trino-test-env.log
```

---

## Part 2: Reference

### 2.1 Tool Architecture

```
trino-connector/integration-test/
├── src/test/
│   ├── java/.../integration/test/
│   │   ├── TrinoQueryITBase.java    # Container/server startup & teardown
│   │   ├── TrinoQueryIT.java        # SQL execution & result matching
│   │   ├── TrinoQueryRunner.java    # Trino JDBC client wrapper
│   │   └── TrinoQueryTestTool.java  # CLI entry point
│   └── resources/trino-ci-testset/
│       ├── testsets/                # Standard testsets
│       │   ├── jdbc-mysql/
│       │   ├── jdbc-postgresql/
│       │   ├── jdbc-bigquery/
│       │   ├── lakehouse-iceberg/
│       │   ├── hive/
│       │   ├── tpch/
│       │   └── tpcds/
│       └── trino-cascading-testsets/  # Federated query testsets
└── trino-test-tools/
    ├── trino_integration_test.sh    # Main launcher (recommended)
    └── run_test_with_versions.sh    # Multi-version launcher
```

### 2.2 Script Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `--auto` | Service startup mode: `all` / `gravitino` / `none` | `all` |
| `--env_only` | Start env in background for manual testing, use `--stop` to shutdown | - |
| `--stop` | Stop a background `--env_only` environment | - |
| `--test_set` | Testset directory name | - |
| `--catalog` | Catalog name within testset | - |
| `--tester_id` | Test file number prefix (e.g. `00004`) | - |
| `--gen_output` | Generate `.txt` expected output files | false |
| `--ignore_failed` | Continue on test failure | false |
| `--params` | Variable substitution: `key1,v1;key2,v2` | - |
| `--trino_version` | Trino version to use | 435 |
| `--trino_worker_num` | Number of independent Trino workers | 0 |
| `--trino_connector_dir` | Path to Gravitino connector JARs | `trino-connector/build/libs` |
| `--test_host` | Host for all services (overrides individual URIs) | 127.0.0.1 |
| `--gravitino_uri` | Gravitino server URL (used when `--auto=none`) | - |
| `--trino_uri` | Trino URL (used when `--auto=none`) | - |
| `--mysql_uri` / `--postgresql_uri` / `--hive_uri` / `--hdfs_uri` | Service URIs | - |
| `--test_sets_dir` | Custom testsets directory | classpath default |
| `--help` | Print help | - |

Built-in substitution variables (auto-populated from service URIs):
`${mysql_uri}`, `${hive_uri}`, `${hdfs_uri}`, `${postgresql_uri}`, `${trino_uri}`, `${gravitino_uri}`

### 2.3 Testset File Structure

```
testsets/<catalog-type>/
├── catalog_<name>_prepare.sql   # Creates catalog (runs before all testers)
├── catalog_<name>_cleanup.sql   # Drops catalog (runs after all testers)
├── 00000_create_table.sql       # Tester SQL
├── 00000_create_table.txt       # Expected output (paired with .sql)
├── 00001_select_table.sql
├── 00001_select_table.txt
└── ignored/                     # Files here are skipped
```

Execution order per catalog:
1. `catalog_<name>_prepare.sql`
2. `00000_*.sql` → compare with `00000_*.txt`
3. `00001_*.sql` → compare with `00001_*.txt` … (sorted numerically)
4. `catalog_<name>_cleanup.sql`

### 2.4 Expected Output Format

Each SQL statement's result is separated by a **blank line**:

```
CREATE SCHEMA

CREATE TABLE

"1","Alice"
"2","Bob"

DROP TABLE
```

**Matching patterns:**

| Pattern | Usage | Example |
|---------|-------|---------|
| Exact | Literal match | `CREATE TABLE` |
| `%` wildcard | Matches any characters | `location = 'hdfs://%:9000/...'` |
| Quoted multi-line | Wrap in `"..."` for multi-line output | `"Trino version: %`<br>`%TableScan[%]`<br>`"` |
| `<QUERY_FAILED> msg` | Assert query fails with message | `<QUERY_FAILED> Table "t" must be qualified` |
| `<BLANK_LINE>` | Assert query returns no rows | `<BLANK_LINE>` |

Add `-- <RETRY_WITH_NOT_EXISTS>` as first line of a `.sql` file to retry on eventual-consistency errors.

### 2.5 catalog_prepare.sql Pattern

```sql
call gravitino.system.create_catalog(
    'gt_<name>',
    '<catalog-provider>',
    map(
        array['key1', 'key2'],
        array['${param1}', '${param2}']
    )
);
```

Use `${param_name}` for values passed via `--params`. Catalog name convention: `gt_<type>`.

### 2.6 Best Practices

- **Test independence**: each `.sql` file creates and drops its own schemas/tables.
- **Flexible matching**: use `%` for version strings, IPs, timestamps — but keep enough specifics to verify behavior.
- **Minimal data**: 2-3 rows is enough; avoid large inserts.
- **5-digit numbering**: `00000_`, `00001_` … for deterministic ordering.

### 2.7 Common Issues

| Issue | Cause | Fix |
|-------|-------|-----|
| Output mismatch | Trino version format change | Use `%` wildcards in `.txt` |
| `--gen_output` files lost | Written to `build/`, not `src/` | Copy to `src/test/resources/` |
| Container startup failure | Port conflict or stale state | Run `integration-test-common/docker-script/shutdown.sh`, check `lsof -i :8080` |
| `--auto=all` fails immediately | Distribution not built | Run `./gradlew compileDistribution` first |
