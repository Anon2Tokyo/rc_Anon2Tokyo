# HTTP 通知服务设计

## 目标与边界

接收多个内部业务系统提交的通知任务，按供应商协议调用外部 HTTP API，并对失败提供可恢复的处理路径。Java/Spring Boot 实现单实例 MVP，RocketMQ 接入、MySQL 保存任务。

上游负责业务事务与可靠发布；中台从接收后承担任务持久化、投递和失败处理；供应商负责业务幂等。允许重复调用，采用有限自动重试＋人工恢复，不保证无条件最终成功。不保证不同请求按序执行，也不解决旧状态覆盖新状态。

## 一次通知怎样走完

业务 A/B 各使用独立 Topic 和 Consumer Group。消息只包含 requestId、supplier、operation、payload，业务身份由订阅配置决定。当前预配置两业务、两供应商，支持更新客户状态一个操作。

消费者调用 `TaskStore.accept`：校验消息、插入任务、提交事务，再向 RocketMQ 返回消费成功。唯一键为 `(business_id, request_id)`。重复同内容可确认；同标识异内容不能覆盖既有任务。

后台分别扫描各业务到期任务，按空闲容量交给各自的有界执行器。工作线程条件领取任务为 PROCESSING，持久化增加尝试数后才发 HTTP。HTTP 不占用数据库事务。响应由供应商协议解析，结果更新为成功、等待重试或失败。

独立业务执行资源限制相互挤占，数据库和 Broker 仍共享。没有按供应商/操作拆分额外队列，没有动态租户配置，也没有面向各业务用户的数据权限系统。

## 状态与故障窗口

| 状态 | 含义 | 下一步 |
| --- | --- | --- |
| PENDING | 已持久化，等待首次或人工重投 | 条件领取 |
| PROCESSING | 已登记一次尝试，调用结果尚未持久化 | 更新结果；进程异常后启动恢复 |
| RETRY_WAIT | 可重试失败，等待指定时间 | 到期领取 |
| SUCCEEDED | 供应商协议判定成功 | 终态 |
| FAILED | 不可恢复或本轮预算耗尽 | 人工处理后显式重投 |

| 故障位置 | 恢复依据 |
| --- | --- |
| DB 提交前 | 未报告 MQ 成功，消费重试 |
| DB 提交后、消费确认前 | 重复接收通过唯一键核对，不重建任务 |
| 消费确认后、HTTP 执行前 | MySQL 任务仍在，重新扫描 |
| 登记尝试后进程宕机 | PROCESSING 结果未知，预算未耗尽则安排重试，否则 FAILED |
| 外部成功、本地结果未提交 | 可能重复调用，供应商负责外部幂等 |
| HTTP 结果保存失败 | 只重试结果写入，不立即重复 HTTP；持续失败则停止新增投递，报告故障并等待受控重启 |

启动恢复必须在扫描和消费者启动前完成，并且旧应用实例已经退出。既有 RETRY_WAIT 时间及次数不变，SUCCEEDED/FAILED 不会自动重新执行。登记后但 HTTP 尚未发出就宕机也占用一次预算，这个不确定窗口无法靠本地记录消除。

## 重试和人工恢复

每轮最多 5 次尝试，四次间隔为 10 秒、30 秒、2 分钟、10 分钟，均可配置。网络异常、408/429/5xx、暂时失败业务码及未知响应有限重试，明确的其他 4xx 和不可恢复业务码直接失败。

内部运维查询与重投接口通过令牌保护。只有 FAILED 可以条件更新为 PENDING，当前轮次数清零，累计次数与最近失败原因保留。原参数不变；修改参数需要上游使用新标识提交。第一版没有管理页面或完整历史审计。

非法 MQ 消息或落库一直失败由 RocketMQ 重试和 DLQ 保留；恢复步骤见 README。DLQ 的有限保留不是永久存档，不能视为数据库任务的替代。

## 关键取舍与演进

- 选择落库后确认，便于追踪失败和控制重试；相比直接 MQ→HTTP，增加数据库和扫描代码。任务库作为投递状态的唯一持久依据，不另设 Redis 状态。
- 用户选择中台组装供应商协议，而不是上游提交完整 URL/Header/Body。两个具体协议集中在 `SupplierGateway`，暂不引入规则引擎或策略注册框架。
- 使用 RocketMQ Remoting 客户端 5.3.2，按回调返回值确认；配套 Broker 5.3.2，减少额外 Proxy 部署。Broker 单节点同步刷盘，仅用于本地演示，不提供磁盘损坏或节点丢失的容灾。
- 若不使用 MQ，替代为 HTTP 接入＋事务落库，后续投递链路可以复用；当前 MQ 是用户选定的上游契约。
- 规模增长后才评估多实例原子领取、租约与旧执行者保护、全局业务配额、供应商限速、任务归档和中间件高可用；这些不是第一版已经提供的能力。

## 依据与 Review

完整需求映射、用户决策和实现推导在 [plan](plans/http-notification.md)，不是将 AI 的建议当作用户原始要求。

参考官方文档：[Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Spring 显式事务](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)、[RocketMQ Remoting PushConsumer](https://rocketmq.apache.org/docs/4.x/consumer/02push/)、[MySQL InnoDB 事务](https://dev.mysql.com/doc/refman/8.4/en/innodb-autocommit-commit-rollback.html)。Remoting API 随客户端依赖编译验证，不能与 RocketMQ 5.x gRPC API 混用。
