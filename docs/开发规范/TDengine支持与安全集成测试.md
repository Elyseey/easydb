# TDengine 支持范围与安全集成测试

EasyDB v1.4 的 TDengine MVP 使用官方 WebSocket JDBC 驱动。默认的 `test`、`check` 和桌面构建不会连接任何 TDengine 实例；真实数据库验证必须显式运行独立任务。

## 支持基线

| 项目 | 支持范围 |
|---|---|
| 服务端 | 已验证 3.3.6.13 与 3.4.1.13 Community |
| Connector | `taos-jdbcdriver:3.9.0` |
| 连接方式 | `jdbc:TAOS-WS://`，默认端口 6041 |
| 本地依赖 | 不需要 `libtaos` 或 Native Client |
| 时间精度 | `ms`、`us`、`ns`；通过 JDBC、内核 JSON 模型和前端字符串展示保留原精度 |
| 工作台 | 超级表、普通表、子表分页/搜索、字段、Tag 定义/值、原生 DDL、只读预览，以及三类对象的专属可视化创建器 |
| SQL 编辑器 | 执行、分页预览、TDengine 关键字/函数/超级表补全、时序模板 |

3.3.6.13 和 3.4.1.13 的真实矩阵均覆盖：连接生命周期、目录元数据、超级表/子表/普通表创建与清理、Tags、NULL/空字符串/Unicode、`ms/us/ns`、`BOOL`、`BIGINT UNSIGNED`、`NCHAR`、长单元格截断、关闭后重连和错误脱敏。

## 可视化创建器

工作台通过统一的“新建 TDengine 对象”入口创建超级表、普通表和单个子表。流程固定为“配置对象 → 服务端 DDL 预览与确认”两步；创建请求只提交结构化 DTO，内核会重新校验并生成单条 DDL，不执行前端回传 SQL。

* 超级表：至少包含时间戳字段、一个数据字段和一个 Tag。
* 普通表：至少包含时间戳字段和一个数据字段。
* 子表：父超级表只能从当前数据库选择，必须显式填写全部 Tag；NULL 开关与空字符串是不同值。
* 首字段自动为 `ts TIMESTAMP`，允许重命名，不允许删除、改类型或移动。
* 稳定类型子集：`TIMESTAMP`、`BOOL`、有符号/无符号整数、`FLOAT`、`DOUBLE`、`BINARY`、`VARCHAR`、`NCHAR`。
* 创建语句不使用 `IF NOT EXISTS`；重名会失败，不覆盖也不静默复用已有对象。
* 表单修改后旧预览立即失效；创建中禁用重复提交。成功后超级表/普通表刷新对象目录，子表只刷新对应父超级表的 children，并保留其他树状态。

## 当前限制

* 数据预览仍固定只读，不提供行编辑、通用关系型表设计器、重命名、清空或删除入口。TDengine 创建只通过专属时序对象创建器开放；通用表创建路由仍返回 `UNSUPPORTED_DB_FEATURE`。
* 创建器暂不支持编辑、ALTER、克隆、批量/CSV 子表创建、跨库父超级表、数据插入、TTL、SMA、压缩、复合主键、虚拟表和复杂类型。
* 暂不支持数据迁移、同步、结构对比、备份恢复、SQL 文件导入、数据库导出、数据追踪和慢查询分析。
* 暂不支持 Cloud token、多 endpoint failover、REST/Native 连接方式。
* 官方 WebSocket JDBC 3.9.0 在已验证服务端上不支持 `Statement.cancel()`，会抛出 `SQLFeatureNotSupportedException`。EasyDB 可以关闭结果集、Statement、分页查询会话和连接，但不宣称能够中断一个正在服务端执行的长查询。
* SQL 美化使用显式的 MySQL-compatible fallback；`STABLE`、`TAGS`、`PARTITION BY` 和 `INTERVAL` 有回归测试，但复杂 TDengine 专有语法仍应在执行前人工复核。

## 安全边界

* 只能使用专用测试实例和非 root 测试账号。
* 凭据只通过进程环境注入，不得写入源码、Gradle 参数文件、测试日志或 JDBC URL。
* JDBC URL 不能包含用户名、密码、token 或换行。
* DDL 测试必须额外设置 `EASYDB_TDENGINE_ALLOW_DDL=true`。
* 主测试库及三种精度库都必须以 `EASYDB_TEST` 开头。
* 用例只创建随机 `EASYDB_IT_` 前缀对象，并在 `finally` 中逆序清理；不创建或删除用户、数据库。
* 清理失败会使显式集成任务失败，不允许吞掉异常后报告成功。

## 环境变量

```text
EASYDB_TDENGINE_INTEGRATION_TEST=true
EASYDB_TDENGINE_JDBC_URL=jdbc:TAOS-WS://<dedicated-test-host>:6041/<main-test-db>
EASYDB_TDENGINE_USERNAME=<dedicated-non-root-user>
EASYDB_TDENGINE_PASSWORD=<secret>
EASYDB_TDENGINE_TEST_DATABASE=EASYDB_TEST_MAIN
EASYDB_TDENGINE_PRECISION_DATABASES=ms=EASYDB_TEST_MS,us=EASYDB_TEST_US,ns=EASYDB_TEST_NS
EASYDB_TDENGINE_ALLOW_DDL=false
```

`EASYDB_TDENGINE_PRECISION_DATABASES` 必须完整定义三个不同数据库。测试运行前由管理员分别以 `PRECISION 'ms'`、`PRECISION 'us'`、`PRECISION 'ns'` 创建，并按部署版本的权限模型授权测试账号。

TDengine Community 3.4.1.13 的 CLI 会将数据库级 `GRANT` 报告为企业版能力。此时不要假定旧版本的授权语句仍然有效，应根据实际版本验证测试账号权限；生产环境尤其不能把 Community 的默认账号行为当作细粒度授权方案。

## 运行方式

默认离线测试：

```bash
./gradlew :drivers:tdengine:test
./gradlew :drivers:tdengine:tdengineIntegrationTest
```

未设置 master switch 时，第二个任务必须显示 `SKIPPED`。只设置 master switch、缺少任一配置时，任务必须在加载驱动或建立连接前失败，并且错误只能指出缺少的环境变量名。

完整真实测试由当前终端的安全凭据工具注入上述变量后运行：

```bash
./gradlew :drivers:tdengine:tdengineIntegrationTest --rerun-tasks
```

## 打包验证

内核 shadow JAR 和桌面包必须同时包含以下类：

```text
com/easydb/drivers/tdengine/TdengineDatabaseAdapter.class
com/taosdata/jdbc/ws/WebSocketDriver.class
```

构建后可分别检查：

```bash
jar tf kernel/launcher/build/libs/launcher-all.jar
jar tf apps/desktop-ui/src-tauri/resources/easydb-kernel.jar
```

最终桌面包应从 `EasyDB.app/Contents/Resources/resources/easydb-kernel.jar` 加载内核，并使用随包 JRE 运行，因此在未安装 `libtaos` 的机器上仍可通过 6041 WebSocket 连接。

## 排障

* “连接超时”：检查 6041 映射、`taosAdapter`/容器健康状态及防火墙。
* “认证失败”：检查账号密码；不要把凭据拼进 JDBC URL。
* “数据库不存在或无权访问”：确认默认数据库存在，并按对应 Community/Enterprise 版本验证权限模型。
* “TLS 握手失败”：检查服务端 TLS、CA 与证书校验设置。
* “驱动未找到”：重新执行 `:launcher:shadowJar` 或桌面打包，并检查上述两个 WebSocket driver 类。
* SQL 取消失败：这是当前 WebSocket JDBC 限制；关闭查询页签/查询会话可释放 EasyDB 侧资源，服务端长查询需按 TDengine 运维方式处理。
