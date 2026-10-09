# 内部 HTTP 通知服务

Java 17 / Spring Boot 3.5.16 / RocketMQ 5.3.2 / MySQL 8.4。

业务系统向自己的 Topic 提交供应商、操作和业务参数。中台先保存任务，再确认 MQ 消费；后台按业务分别执行 HTTP 投递，处理供应商响应、有限重试及人工恢复。

```text
business-a → Topic A → 消费者 A ┐                  ┌→ 线程池 A → 供应商 A/B
                              ├→ MySQL 任务表 → 扫描
business-b → Topic B → 消费者 B ┘                  └→ 线程池 B → 供应商 A/B
```

这是单实例 MVP。一次通知允许重复投递；外部业务幂等由供应商负责。自动尝试有限，不保证所有任务最终成功，不保证跨请求顺序。完整设计见 [docs/DESIGN.md](docs/DESIGN.md)，讨论依据、取舍与实现推导见 [plan](docs/plans/http-notification.md)。

## 作业提交内容与阅读入口

本仓库 `rc_Anon2Tokyo` 对应 API 通知系统设计与实现作业。建议按以下顺序阅读，无需依赖开发过程中的聊天记录。

| 作业要求 | 对应文件 | 可以看到什么 |
| --- | --- | --- |
| 对问题的理解、整体架构、核心设计 | 本 README、[设计说明](docs/DESIGN.md) | 服务定位、责任边界、接入与投递主链路、数据与协议 |
| 可靠性、失败处理、取舍与演进 | [设计说明](docs/DESIGN.md) | 投递语义、崩溃窗口、重试终止、替代方案和演进条件 |
| 最小可行代码实现 | [业务代码](src/main/java/io/anon/notify)、[配置与表结构](src/main/resources)、[本地运行配置](compose.yml) | 一个可运行的 Spring Boot 服务及其依赖 |
| AI 在哪些关键地方提供帮助 | [AI 使用说明](AI_USAGE.md) | AI 参与阶段、未采纳的建议、开发者的决定及取舍依据 |
| 验证依据（补充材料） | [验证记录](docs/VERIFICATION.md)、[测试代码](src/test/java/io/anon/notify) | 21 个测试、真实中间件链路及进程恢复验证、未验证范围 |
| 设计与实现推导（补充材料） | [plan](docs/plans/http-notification.md) | 每轮决策、理由、动作及代码落点 |

`设计方案模版.md` 是设计过程的参考提纲；正式提交说明以 `docs/DESIGN.md` 为准。`AGENTS.md` 保存开发流程约定，不属于运行依赖。

## 我对问题的理解

业务系统希望把“调用外部 API”这个不稳定、可能耗时的动作交给内部服务。核心问题是外部接口超时、拒绝或长期不可用时，任务能否被保留、区分结果并继续处理，而不只是完成一次 HTTP 转发。

“业务系统不关心返回值”不代表中台忽略返回值：上游无需处理供应商结果，中台仍需结合 HTTP 状态和供应商业务码判断成功。题目没有提供真实供应商协议，本实现通过两个模拟协议验证差异，不宣称已经接入真实 CRM 或广告系统。

第一版选择 MQ 作为上游入口，选择由中台组装供应商请求，而不是提供任意 URL 的通用代理。当前范围是两个预配置业务来源、两个模拟供应商、一个“更新客户状态”操作。

## 第一版的重要取舍

| 决定 | 为什么这样做 | 付出的代价 / 限制 |
| --- | --- | --- |
| 任务落库后确认 MQ 消费 | 把接收与外部调用分开，明确重试状态及恢复依据 | 多一个数据库和后台扫描流程 |
| 按业务方隔离消费及投递资源 | 不同业务触发频率不同，限制彼此挤占 | 仍共享进程、Broker、数据库；不是完整多租户隔离 |
| 中台维护两套供应商协议 | 上游不用组装不同 Header/Body 和解析响应 | 新供应商需要中台适配代码 |
| 有限重试、失败保留、人工重投 | 长期故障时控制资源消耗并保留恢复入口 | 不承诺永久故障下自动成功 |
| 单实例加启动恢复 | 首先证明进程故障后的恢复逻辑 | 停机期间暂停投递，无多实例高可用 |

这些是已经实现的选择。动态租户、顺序保证、多实例调度、供应商级配额和管理页面没有包含在 MVP 中。中间件选择、替代方案及演进触发条件详见设计说明。

## 本地运行

需要 Java 17、Maven 3.9、Docker Compose；演示供应商和冒烟脚本另需 Python 3。以下命令在仓库根目录执行。Compose 端口仅绑定本机，RocketMQ 对外通告地址也是 127.0.0.1，因此应用应在宿主机运行。

1. 启动 MySQL、NameServer、Broker 并创建两个业务 Topic：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup-local.ps1
```

MySQL 使用宿主机 3307 端口，RocketMQ 使用 9876、10911、10909。一个短生命周期初始化容器设置 Broker 数据卷属主，Broker 本身按镜像默认非 root 用户运行。数据保存于命名卷。

2. 新终端运行模拟供应商：

```powershell
python scripts/mock_suppliers.py
```

3. 构建并启动中台：

```powershell
mvn -B package
$env:OPS_TOKEN = 'local-demo-ops-token-change-me'
java -jar target/http-notification-0.1.0.jar
```

默认监听 `127.0.0.1:8080`。OPS_TOKEN 必须设置，至少 16 个字符。MySQL 默认开发账号为 `notify / notify-local`；可通过 DB_URL、DB_USER、DB_PASSWORD 覆盖。供应商地址和凭据通过 SUPPLIER_A_URL、SUPPLIER_A_TOKEN、SUPPLIER_B_URL、SUPPLIER_B_TOKEN 配置，不随业务消息提交。

4. 用示例上游发送通知：

```powershell
mvn -q exec:java '-Dexec.mainClass=io.anon.notify.DemoPublisher' '-Dexec.classpathScope=test' '-Dexec.args=127.0.0.1:9876 notify-business-a examples/notification.json'
```

示例发送端同步检查 SEND_OK；真实上游还须自行处理业务事务与发布的一致性。Broker 在演示配置中使用 SYNC_FLUSH，但单 Broker 没有节点容灾能力。

此处打印发送成功只代表 MQ 发布结果，不代表供应商已经处理完成；最终投递状态通过下方任务查询检查。

5. 在另一个操作终端查询和重投，令牌须与运行中台的终端保持一致：

```powershell
$env:OPS_TOKEN = 'local-demo-ops-token-change-me'
$headers = @{ 'X-Ops-Token' = $env:OPS_TOKEN }
Invoke-RestMethod 'http://127.0.0.1:8080/internal/tasks/business-a/contact-update-001' -Headers $headers
Invoke-RestMethod 'http://127.0.0.1:8080/internal/status' -Headers $headers
# 只有 FAILED 任务可重投；其他状态返回 409。
Invoke-RestMethod 'http://127.0.0.1:8080/internal/tasks/business-a/contact-update-001/retry' -Method Post -Headers $headers
```

修改消息内容时必须换 requestId；重复投递同标识同内容不会新建任务。同标识不同内容会进入接入失败处理，不能覆盖原任务。业务身份来自 Topic 配置，不由消息自行声明。演示环境未设置 RocketMQ 发布端 ACL，生产接入需限制各业务的 Topic 发布权限。

## 协议与失败演示

消息样例见 [examples/notification.json](examples/notification.json)。仅支持 `UPDATE_CONTACT_STATUS`，状态为 ACTIVE/INACTIVE；`requestId` 限 1–100 个字母、数字、下划线或连字符。

| 供应商 | 请求 | 成功响应 |
| --- | --- | --- |
| supplier-a | POST /contacts/status；X-Api-Key；contactId/status | HTTP 2xx 且数字 code=0 |
| supplier-b | POST /v1/contact/enable；Bearer；user_id/enabled | HTTP 2xx 且 result=SUCCESS |

这些是模拟协议。Header `X-Notification-Id` 在重试时保持稳定，但模拟供应商不实现业务幂等，不能据此声称消除了重复副作用。

模拟服务按 contactId 前缀返回结果：普通值成功，`fail-` 暂时失败，`reject-` 不可恢复失败，`slow-` 延迟 8 秒。发往 B 时该值会映射为 user_id。

- HTTP 408、429、5xx、网络/超时、暂时失败业务码：有限重试。
- 其余 4xx、明确不可恢复业务码：直接 FAILED。
- 无法解析或未知响应、未跟随的重定向：有限重试，不误判成功。
- 每轮最多 5 次尝试（包含首次）；重试间隔 10 秒、30 秒、2 分钟、10 分钟。
- 人工重投只重置本轮次数，保留累计次数、人工重投次数、最近失败原因；不修改参数。

默认每业务 1 个消费线程、2 个投递线程，500ms 扫描一次；HTTP 超时 5s。配置在 `application.yml`，可用 Spring 配置覆盖。测试可使用 `--notification.retry-delays=100ms,200ms,300ms,400ms`。尝试上限为“间隔数量 + 1”。

### 一次可复现的失败与人工重投演示

在中台和模拟供应商已运行的情况下，用新标识发送一个确定失败的任务。这里用不可恢复错误演示，避免等待完整自动重试周期。

```powershell
New-Item -ItemType Directory -Force target | Out-Null
$requestId = 'demo-reject-' + [guid]::NewGuid().ToString('N')
$message = @{
    requestId = $requestId
    supplier = 'supplier-b'
    operation = 'UPDATE_CONTACT_STATUS'
    payload = @{ contactId = 'reject-c001'; status = 'ACTIVE' }
} | ConvertTo-Json -Depth 3
# PowerShell 5 的 -Encoding UTF8 会写 BOM，这里明确生成无 BOM 的 UTF-8 消息。
[IO.File]::WriteAllText((Join-Path $PWD 'target/demo-reject.json'), $message, [Text.UTF8Encoding]::new($false))
mvn -q exec:java '-Dexec.mainClass=io.anon.notify.DemoPublisher' '-Dexec.classpathScope=test' '-Dexec.args=127.0.0.1:9876 notify-business-b target/demo-reject.json'
$uri = "http://127.0.0.1:8080/internal/tasks/business-b/$requestId"
Invoke-RestMethod $uri -Headers $headers
# 确认状态为 FAILED 后再执行；消费/投递异步，刚发送时可能暂时查不到或仍在执行。
Invoke-RestMethod "$uri/retry" -Method Post -Headers $headers
Invoke-RestMethod $uri -Headers $headers
```

第一次最终状态为 FAILED、attemptCount=1。重投后 replayCount=1，原参数不变，模拟供应商会再次拒绝，最终 totalAttempts=2。这说明人工重投是重新尝试，不是把任务强制改成成功。实际恢复应先修好外部故障或配置；若业务参数错误，上游须修正参数并提交新 requestId。

## 两种失败恢复

**HTTP 失败任务**已经在 MySQL 中，使用上面的运维查询/重投接口。没有自动清理失败任务；第一版只保留当前状态和最近错误，没有完整逐次审计历史。

**MQ 接入失败**还没有成为合法任务，例如 DB 不可用、非法 JSON 或同标识异内容。监听器返回失败，由 RocketMQ 重试（默认 16 次），耗尽后进入对应消费组的 `%DLQ%...` Topic。

查看死信位置及消息：

```powershell
docker compose exec -T broker sh mqadmin topicStatus -n namesrv:9876 -t '%DLQ%notify-business-a-consumer'
docker compose exec -T broker sh mqadmin queryMsgByOffset -n namesrv:9876 -b notify-broker -t '%DLQ%notify-business-a-consumer' -i 0 -o 0
```

队列编号和 offset 按实际 topicStatus 输出选择。queryMsgByOffset 会显示 `Message Body Path`（位于 Broker 容器内）。用 `docker compose cp broker:<该路径> target/dlq-message.json` 导出原消息体。修复数据库问题后，通过 DemoPublisher 将文件发回原业务 Topic；非法消息先按契约修正。若原请求参数要改变或存在标识冲突，必须用新 requestId。保留原死信供核对，不在演示脚本中自动清空。

Broker 的消息文件保留时间示例为 72 小时，磁盘压力等因素也可能影响保留；死信不是永久存档，应及时检查并恢复或导出。这里的默认 16 次 MQ 重试与 5 次 HTTP 尝试是两个独立预算。

若 `/internal/status` 返回 503，说明投递过程中数据库状态不确定或发生异常，系统已停止新增投递。先查看日志并恢复数据库/配置，再确认旧实例退出后重启；启动恢复不会自动重投 FAILED。不要同时启动两个实例操作同一任务库。

## 验证

已完成的验证记录于 2026-10-09，代码基线为 `fcbbe36`：快速测试 21 项通过、同一套测试在真实 MySQL 上通过、真实 RocketMQ→MySQL→模拟 HTTP 链路及进程恢复通过。详细证据、每项验收与测试的对应关系见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。本次补充说明不代表新增一次中间件测试运行。

快速测试（H2 MySQL 模式＋真实本地 HTTP 服务，**不代表真实 MQ/MySQL 验证**）：

```powershell
mvn -B test
```

使用专用 MySQL 测试库运行相同测试：

```powershell
docker compose exec -T mysql mysql -uroot -proot-local-only -e "CREATE DATABASE IF NOT EXISTS notifications_test CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; GRANT ALL ON notifications_test.* TO 'notify'@'%';"
$env:NOTIFICATION_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:3307/notifications_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:NOTIFICATION_TEST_DB_USER = 'notify'
$env:NOTIFICATION_TEST_DB_PASSWORD = 'notify-local'
mvn -B test
Remove-Item Env:NOTIFICATION_TEST_DB_URL,Env:NOTIFICATION_TEST_DB_USER,Env:NOTIFICATION_TEST_DB_PASSWORD
```

测试只接受名称为 notifications_test 的真实数据库，并清理该库的任务表；不要向其中存放业务数据。

完整冒烟（先关闭手工启动的中台和模拟供应商，避免单实例/端口冲突）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup-local.ps1
mvn -B package
mvn -q dependency:build-classpath '-Dmdep.outputFile=target/test-classpath.txt' '-Dmdep.includeScope=test'
python scripts/smoke_test.py
```

脚本启动自己的 Java 和模拟供应商进程，端口为 18080/18081/18082，验证两业务×两供应商、重复消息、重试耗尽、人工重投、真实进程强制结束后的恢复及运维访问限制。任务使用唯一前缀保留在本地 notifications 库，日志放在 target/smoke；结束时关闭脚本自己的进程，不删除数据库或 MQ 数据。非法消息另用上述命令核查死信。

运行结束可用 `docker compose stop` 停止本项目基础设施，保留数据卷。

提交时保留源代码、配置、示例、脚本和说明即可，jar、测试报告原件、运行日志、临时文件及本地环境变量由 `.gitignore` 排除。测试报告的可审阅摘要保存在版本管理内的验证记录中，克隆仓库后可用上述命令重新生成原件。

## 代码阅读顺序

1. `RocketIngress.consume`：从哪里接收，什么时候确认。
2. `TaskStore.accept/claim/complete/recover`：任务如何持久化和流转，这是可靠性的核心。
3. `DeliveryDispatcher`：每业务如何领取、执行和限制并发。
4. `SupplierGateway`：业务参数如何变成不同 HTTP 协议。
5. `OpsController`：如何查看失败与开启新一轮。

设计依据见 [plan](docs/plans/http-notification.md)，AI 建议取舍见 [AI_USAGE.md](AI_USAGE.md)。
