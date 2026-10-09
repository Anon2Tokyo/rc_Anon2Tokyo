# MVP 验证记录

## 验证对象与证据来源

- 验证日期：2026-10-09。
- 实现基线：`fcbbe36`（`feat: implement reliable HTTP notification MVP`）。本轮完善提交说明没有修改业务代码。
- 本地环境：Windows 11、Java 17.0.8、Maven 3.9.8、Docker Compose；应用为 Spring Boot 3.5.16，RocketMQ 客户端/Broker 为 5.3.2，MySQL 镜像为 8.4。
- 实际执行记录来自 [plan 的 I6](plans/http-notification.md)、Maven Surefire 报告以及当次真实链路输出。本次文档整理又核对了保留的 Surefire XML：21 tests、0 failures、0 errors、0 skipped。
- Surefire 原件、冒烟日志和临时导出文件位于被忽略的 `target/`，不要求仓库克隆者具有这些本地文件；本文件保存可审阅摘要，复现命令见 [README](../README.md)。

以下区分快速测试、真实 MySQL 复跑、真实 MQ 冒烟和人工执行的管理命令。没有把它们统称为同一种“全链路测试”。

## 1. 自动化行为测试

命令：`mvn -B test`。默认使用 H2 的 MySQL 模式；HTTP 判据测试使用实际本地 HTTP Server。

| 测试类 | 执行用例数 | 验证内容 | 结果 |
| --- | --- | --- | --- |
| [TaskStoreTest](../src/test/java/io/anon/notify/TaskStoreTest.java) | 6 | 重复/冲突请求、非法消息、五次预算及时间、永久失败/成功终态、五种状态启动恢复、并发人工重投 | 通过 |
| [SupplierGatewayTest](../src/test/java/io/anon/notify/SupplierGatewayTest.java) | 11 | 两种请求映射；8 组 HTTP 状态分类；业务码、未知/非法响应、超时 | 通过 |
| [DeliveryDispatcherTest](../src/test/java/io/anon/notify/DeliveryDispatcherTest.java) | 2 | 慢业务与其他业务的投递资源隔离；结果保存故障不重复 HTTP 并停止新增投递 | 通过 |
| [IngressAndOpsTest](../src/test/java/io/anon/notify/IngressAndOpsTest.java) | 2 | 接收成功后记录可见、接收异常返回重试；运维认证、查询、重投状态约束及停止状态 | 通过 |
| 合计 | **21** | 参数化 HTTP 测试按实际执行次数计数 | **0 失败、0 错误、0 跳过** |

同一套测试通过环境变量改接专用 MySQL `notifications_test` 数据库后再次全部通过；因此数据访问与状态更新不仅在 H2 上验证。测试会清理专用库的任务表，不允许将普通业务库地址作为测试库。

### 关键断言

- 同业务同 requestId 同内容只产生一条任务；不同内容不能覆盖；不同业务允许复用 requestId。
- 成功任务再次收到相同消息不会重新执行；失败任务不会因为普通重复消费自动增加一轮。
- 可重试失败用完 5 次预算后进入 FAILED；重试时间未到不能被正常扫描。
- 八个并发人工重投调用中只有一个成功开启新一轮；当前轮归零，累计次数与最近失败保留。
- PROCESSING 在启动恢复时保留预算；FAILED/SUCCEEDED 保持终态，已有 RETRY_WAIT 的执行时间不被重置。
- 慢业务 A 占住自己的工作线程且有积压时，业务 B 在另一线程池完成调用。这是行为隔离测试，不是生产吞吐量基准。
- 注入结果保存失败时只发送一次 HTTP，最多尝试三次结果写入，然后产生停止标志。测试没有把写库失败变成再次发送 HTTP。

## 2. 真实中间件与进程恢复冒烟

执行入口：[scripts/smoke_test.py](../scripts/smoke_test.py)。运行前已启动真实 RocketMQ 和 MySQL，并创建两个业务 Topic。脚本使用打包后的 Java 应用与两个独立本地模拟 HTTP 服务，没有使用内存 MQ 替身。

当次任务前缀：`smoke-1791531183481262700`。脚本将 HTTP 重试间隔缩短为 2 秒、MQ 最大重试覆盖为 1，以便验证失败与恢复；默认业务配置仍是 HTTP 每轮 5 次、间隔 10 秒/30 秒/2 分钟/10 分钟，以及 MQ 消费重试 16 次。

| 场景 | 当次观察与断言 | 结果 |
| --- | --- | --- |
| 两业务 × 两供应商 | 四组任务均成为 SUCCEEDED，说明没有固定业务与供应商一对一绑定 | 通过 |
| 相同 MQ 消息再次提交 | 已有成功任务的 totalAttempts 仍为 1 | 通过；并与存储层查重测试共同佐证 |
| 可重试失败 | supplier-a 暂时失败最终为 FAILED，attemptCount=5 | 通过 |
| 人工重投 | 同任务开启第二轮，最终 replayCount=1、totalAttempts=10 | 通过 |
| 不可恢复业务拒绝 | supplier-b 拒绝后 attemptCount=1、FAILED | 通过 |
| PROCESSING 时强制结束 Java | 新 Java 进程恢复任务，最终 attemptCount=5、totalAttempts=5、replayCount=0 | 通过 |
| 既有失败终态重启后保持 | 前面两轮失败任务的 totalAttempts 仍为 10，没有被启动恢复自动重投 | 通过 |
| 运维认证 | 错误令牌调用重投返回 401 | 通过 |

端到端重复消息检查是在再次发布后查询既有任务；它不是对任意长时间窗口的持续去重监控。真正保证不重复建任务的依据还包括数据库唯一键及存储层测试。

这次进程中断发生在任务可查询为 PROCESSING 时，证明了进程重启后的状态恢复；没有精确控制到每一个 CPU 指令或模拟所有网络分区。

## 3. 死信保留及导出检查

冒烟脚本发送非法消息 `not-json`；额外执行 RocketMQ 管理命令核验，不把“已发送非法消息”的输出误当作 DLQ 自动断言。

1. `mqadmin topicStatus` 查询 `%DLQ%notify-business-a-consumer`，当次队列 0 的 offset 范围为 `[0, 1)`。
2. `mqadmin queryMsgByOffset` 查询 offset 0，返回对应消息及容器内 `Message Body Path`。
3. 使用 `docker compose cp` 导出该路径，文件内容仍为原始 `not-json`。

这证明接入异常经过 MQ 重试后保留在死信队列，且可取回原消息。修复后重发的方法已写入 README；这次未额外声称对全部死信恢复分支做过端到端自动化验证。Broker 配置的保留期有限，不能将导出成功解释为永久存档保证。

## 4. 故障窗口与证据强度

| 设计中的故障窗口 | 已有证据 | 尚未覆盖的部分 |
| --- | --- | --- |
| 接收时数据库不可写 | 测试删除表后监听器返回重试，而不是成功 | 未逐一模拟所有事务提交网络故障 |
| 已落库但消费成功反馈丢失 | 接收查重测试、真实重复发布以及唯一约束 | 未在真实 Broker 确认的准确瞬间注入崩溃 |
| HTTP 超时、响应未知 | 实际 HTTP 超时/未知响应测试、真实 slow 模拟调用 | 未接入实际供应商网络和 SLA |
| 执行中进程退出 | 实际结束 Java 进程并重新启动 | 不等于主机断电、磁盘损坏或数据库丢失恢复 |
| HTTP 成功后结果保存失败 | 注入持久化异常，验证 HTTP 不被直接重复调用 | 未证明供应商副作用恰好一次；中台也没有此承诺 |
| 一个业务投递阻塞 | 独立线程池行为测试 | 未压测共享数据库/Broker/进程资源耗尽 |

## 5. 运行问题与修正

- Broker 启动时不能写 `/home/rocketmq/store/lock`：实际根因为数据卷属主，加入只初始化卷属主的短生命周期服务后 Broker 启动成功。
- 时间断言偶发相差 1ms：测试输入包含毫秒以下精度，数据库舍入与断言截断不同；固定测试时钟到毫秒后通过。
- 本地冒烟健康查询受宿主代理影响：脚本改为本机直连后可访问服务。
- Windows PowerShell 5 误读无 BOM 的中文字符串：启动脚本提示改为 ASCII，中文操作说明保留在 README；脚本实测创建两个 Topic 成功。
- 构建 jar 被正在运行的 Java 占用：结束脚本自己的进程后打包成功；没有把跳过测试的打包命令当作测试证据。

## 6. 未验证与交付边界

尚未验证真实供应商、生产 TLS/鉴权集成、MQ 发布 ACL、Broker/MySQL 复制与容灾、磁盘损坏、生产负载或多实例并行。任务顺序、供应商业务幂等和完整租户数据权限本来就不在本版实现范围内。

验证结束后停止本项目容器与脚本进程，保留数据卷。自动化报告仅证明所列场景，不代表每条任务最终必然成功，也不代替代码 review。
