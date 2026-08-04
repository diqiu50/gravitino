# Trino Connector 对接 Starburst 编译与配置指南

## 一、编译 Connector（针对 Starburst SPI）

### 1. 准备 Starburst 的 SPI jar

从已下载的 Starburst 企业版目录里找到 `trino-spi` jar（裸 jar，不在任何公共 Maven 仓库）：

```bash
find /home/ubuntu/Software/starburst-enterprise-478-e-x86_64/lib -iname "*trino-spi*"
# → io.trino_trino-spi-478-e.0.33.jar
```

拷到仓库根目录一个不提交的本地目录（`*.jar` 已被全局 `.gitignore` 覆盖）：

```bash
mkdir -p starburst-libs
cp /home/ubuntu/Software/starburst-enterprise-478-e-x86_64/lib/io.trino_trino-spi-478-e.0.33.jar starburst-libs/
```

### 2. 临时改 `trino-connector-473-478/build.gradle.kts`（只本地改，不提交）

```kotlin
compileOnly(libs.airlift.resolver)
compileOnly(files("../../starburst-libs/io.trino_trino-spi-478-e.0.33.jar"))
compileOnly("io.airlift:slice:2.4")   // 裸 jar 没有 POM，要手动补传递依赖
```

### 3. 用 JDK 24 构建

Gradle 本身仍要跑在 JDK17 上（根 `build.gradle.kts` 强制要求），模块编译用 JDK24 toolchain（自动选取，无需手动切 `JAVA_HOME` 到 24）：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # 启动 Gradle 用 17
./gradlew :trino-connector:trino-connector-473-478:assembleTrinoConnector -x test
```

（JDK24 toolchain 已缓存在 `~/.gradle/jdks/eclipse_adoptium-24-amd64-linux/jdk-24.0.2+12`，Gradle 会自动用它编译这个模块）

产物：`distribution/gravitino-trino-connector-473-478/`（插件目录）+ 同名 `.tar.gz`。

> 针对普通 OSS Trino 478 构建则不用改 `build.gradle.kts`，直接跑上面同一条 gradle 命令即可（`trinoVersion` 默认就是 478）。

### 4. 已知代码兼容性问题

Starburst 478-e 的 `ConnectorFactory` 比 OSS Trino 多一个必须实现的抽象方法 `getSecuritySensitivePropertyNames`，已在 `fix-connector-factory-security-property-names` 分支里正式修复（无 `@Override`，和 `GravitinoDynamicFilter#getPreferredDynamicFilterTimeout` 同一套跨版本兼容写法）。

## 二、配置 Starburst

### 1. License（必须，放在固定相对路径）

```bash
cp "<下载的 .license 文件>" $STARBURST_HOME/etc/starburstdata.license
```

路径是硬编码的 `etc/starburstdata.license`（反编译 `com/starburstdata/presto/license/JSONLicenseProvider.class` 得到的，`LICENSE_PATH = Paths.get("etc/starburstdata.license")`）。

### 2. `etc/` 下最小单节点配置

```
etc/node.properties      # node.environment / node.id / node.data-dir
etc/jvm.config             # 常规 JVM 参数
etc/config.properties:
  coordinator=true
  node-scheduler.include-coordinator=true
  http-server.http.port=8081
  catalog.management=dynamic     # Gravitino 连接器强制要求 dynamic，static 会直接抛
                                  # "Gravitino connector works only at catalog.management = dynamic mode"
  discovery.uri=http://127.0.0.1:8081
etc/log.properties         # io.trino=INFO / org.apache.gravitino=DEBUG
```

### 3. Insights 模块必须有外部 DB

Starburst 企业版自带功能，跟 Gravitino 无关，但不配就起不来（`StarburstStorageConfig` 校验 `insights.jdbc.url` 非空）：

```
insights.jdbc.url=jdbc:mysql://127.0.0.1:<port>/insights?sessionVariables=sql_mode=ANSI
insights.jdbc.authentication-type=BASIC     # 枚举值是 BASIC/KERBEROS/AWS_IAM，不是 PASSWORD
insights.jdbc.user=root                     # 注意是 user 不是 username
insights.jdbc.password=<pwd>
```

本地图省事直接起了个 `mysql:8.0` docker 容器充当这个 DB：

```bash
docker run -d --name starburst-insights-mysql -p 13306:3306 \
  -e MYSQL_ROOT_PASSWORD=starburst -e MYSQL_DATABASE=insights \
  mysql:8.0 --sql-mode=ANSI
```

### 4. 部署 Gravitino 连接器插件

```bash
mkdir -p $STARBURST_HOME/plugin/gravitino
cp distribution/gravitino-trino-connector-473-478/*.jar $STARBURST_HOME/plugin/gravitino/
```

注意去重：`assembleTrinoConnector` 的 Copy 任务不清旧文件，多次构建后 `distribution/` 目录下会残留旧版本号（如 `1.3.0-SNAPSHOT`）的 jar，要手动删掉，避免同一 artifact 两个版本混进 classpath 导致类冲突。

### 5. 启动 + 建 catalog

```bash
export JAVA_HOME=$STARBURST_HOME/jvm
$STARBURST_HOME/bin/launcher run
```

因为 `catalog.management=dynamic`，**不会**自动读 `etc/catalog/*.properties`，必须用 SQL 动态建 catalog：

```sql
CREATE CATALOG gravitino USING gravitino WITH (
  "gravitino.uri" = 'http://localhost:8090',
  "gravitino.metalake" = 'test'
);
```

之后 `SHOW CATALOGS` / `SHOW SCHEMAS FROM gravitino` 等即可正常查询。

## 三、验证结论摘要

- 针对真实 Starburst 478-e.0.33 SPI 构建成功（含上述兼容性修复）。
- 插件在真实 Starburst coordinator 中加载成功，无类加载/链接错误。
- `CREATE CATALOG` + `SHOW CATALOGS` + `SHOW SCHEMAS FROM gravitino` 端到端跑通，无 Gravitino 相关 ERROR/WARN。
