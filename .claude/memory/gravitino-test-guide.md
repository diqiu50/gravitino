# Gravitino 本地测试环境指南

## Gravitino Server

- 地址：`http://localhost:8090`
- 代码目录：`/home/ubuntu/git/gravitino`
- 启动/重启：`distribution/package/bin/gravitino.sh restart`
- 打包：`./build.sh dist`（打包后需重启才生效）
- 配置文件：`distribution/package/conf/gravitino-env.sh`
- 日志目录：`distribution/package/logs/gravitino-server.log`

## Docker 容器

| 容器名 | 内网 IP | 服务 |
|--------|---------|------|
| gt01 | 172.17.0.2 | Hive Metastore（9083）、HDFS（9000）、MySQL（3306）、Zookeeper（2181） |

> 容器端口均未发布到宿主机，需通过内网 IP 访问。

### Hive 版本切换

镜像 `apache/gravitino-ci:hive-0.1.20` 内置 Hive2 和 Hive3，通过环境变量切换：

```bash
# 启动 Hive 2（默认）
docker run -d --name gt01 apache/gravitino-ci:hive-0.1.20

# 启动 Hive 3
docker run -d --name gt01 -e HIVE_RUNTIME_VERSION=hive3 apache/gravitino-ci:hive-0.1.20
```

切换步骤：
```bash
docker rm -f gt01
docker run -d --name gt01 [-e HIVE_RUNTIME_VERSION=hive3] apache/gravitino-ci:hive-0.1.20
# 等待约 30-60 秒，直到 "Hive started successfully." 出现
docker logs gt01 2>&1 | tail -5
```

- Hive3 下 Gravitino 使用 HiveShimV3 直连，日志出现：`Connected to Hive Metastore using Hive version HIVE3`
- Hive2 下（或降级时）日志出现：`Connected to Hive Metastore using Hive version HIVE2`

## 关键环境变量（gravitino-env.sh）

```bash
export HADOOP_USER_NAME=root   # HDFS /user/hive/warehouse 目录 owner 为 root
```

## API 请求头

```
Accept: application/vnd.gravitino.v1+json
Content-Type: application/json
```

## 验证脚本

| 脚本 | 说明 |
|------|------|
| `/home/ubuntu/workspace/test_spt/run_hive.sh` | Hive catalog 端到端验证（metalake → catalog → schema → table） |
| `/home/ubuntu/workspace/test_spt/run_s3.sh` | S3 fileset catalog 验证 |

### Hive 验证脚本使用

```bash
# 清理已有数据（如需重跑）
curl -s -X DELETE "http://localhost:8090/api/metalakes/test?force=true" \
  -H "Accept: application/vnd.gravitino.v1+json"

# 运行验证
bash /home/ubuntu/workspace/test_spt/run_hive.sh
```

期望每步返回 `"code": 0`。

### Hive Catalog 参数

- Metastore URI：`thrift://172.17.0.2:9083`
- provider：`hive`，type：`RELATIONAL`
