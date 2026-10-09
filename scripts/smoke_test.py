"""真实 MQ/MySQL/HTTP 与 Java 进程重启验收。先执行 setup-local.ps1 和 Maven 构建。"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
TOKEN = "smoke-test-local-token"
LOCAL_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
BASE = "http://127.0.0.1:18080"
PREFIX = "smoke-" + str(time.time_ns())
TEMP = ROOT / "target" / "smoke"
TEMP.mkdir(parents=True, exist_ok=True)
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
CLASSPATH = os.pathsep.join([str(ROOT / "target/test-classes"), str(ROOT / "target/classes"),
                            (ROOT / "target/test-classpath.txt").read_text().strip()])


def api(path, method="GET", token=TOKEN):
    request = urllib.request.Request(BASE + path, method=method, headers={"X-Ops-Token": token})
    with LOCAL_HTTP.open(request, timeout=3) as response:
        return json.load(response)


def wait_for(check, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(0.1)
    raise AssertionError("条件未在时限内满足；查看 target/smoke 日志")


def task_path(business, name):
    return f"/internal/tasks/{business}/{PREFIX}-{name}"


def publish(name, supplier="supplier-a", contact="c1", business="business-a", raw=None):
    data = raw if raw is not None else json.dumps({"requestId": PREFIX + "-" + name, "supplier": supplier,
           "operation": "UPDATE_CONTACT_STATUS", "payload": {"contactId": contact, "status": "ACTIVE"}})
    file = TEMP / (name + ".json")
    file.write_text(data, encoding="utf-8")
    result = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", CLASSPATH, "io.anon.notify.DemoPublisher",
                             "127.0.0.1:9876", "notify-" + business, str(file)],
                            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=25,
                            creationflags=FLAGS)
    if result.returncode:
        raise AssertionError("MQ 发布失败: " + result.stdout[-2000:] + result.stderr[-2000:])


def wait_status(business, name, status, timeout=30):
    def check():
        task = api(task_path(business, name))
        return task if task["status"] == status else None
    return wait_for(check, timeout)


def start_app(log):
    env = os.environ.copy()
    env["OPS_TOKEN"] = TOKEN
    process = subprocess.Popen(["java", "-Dfile.encoding=UTF-8", "-jar", "target/http-notification-0.1.0.jar",
                                "--server.port=18080", "--notification.poll-interval=100ms",
                                "--notification.http-timeout=2s", "--notification.retry-delays=2s,2s,2s,2s",
                                "--notification.mq-max-reconsume-times=1"],
                               env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
    def ready():
        if process.poll() is not None:
            raise AssertionError("应用启动失败，查看 target/smoke/app.log")
        return api("/internal/status")["delivery"] == "RUNNING"
    try:
        wait_for(ready, 45)
    except BaseException:
        process.kill()
        process.wait()
        raise
    return process


if __name__ == "__main__":
    app = None
    mock = None
    with (TEMP / "app.log").open("w", encoding="utf-8") as app_log, (TEMP / "mock.log").open("w", encoding="utf-8") as mock_log:
        try:
            mock = subprocess.Popen([sys.executable, "scripts/mock_suppliers.py"], stdout=mock_log,
                                    stderr=subprocess.STDOUT, creationflags=FLAGS)
            app = start_app(app_log)
            if mock.poll() is not None:
                raise AssertionError("模拟供应商启动失败，可能端口已占用")
            for business in ("business-a", "business-b"):
                for supplier in ("supplier-a", "supplier-b"):
                    name = business + "-" + supplier
                    publish(name, supplier, business=business)
                    wait_status(business, name, "SUCCEEDED")
            print("PASS: 两个业务 × 两个供应商真实投递", flush=True)

            publish("business-a-supplier-a")
            duplicate = api(task_path("business-a", "business-a-supplier-a"))
            assert duplicate["totalAttempts"] == 1
            print("PASS: 重复 MQ 消息没有重复建立或执行任务", flush=True)

            publish("retry", contact="fail-c1")
            failed = wait_status("business-a", "retry", "FAILED")
            assert failed["attemptCount"] == 5
            api(task_path("business-a", "retry") + "/retry", "POST")
            replayed = wait_status("business-a", "retry", "FAILED")
            assert replayed["replayCount"] == 1 and replayed["totalAttempts"] == 10
            print("PASS: 五次上限、失败保留、人工开启新一轮", flush=True)

            publish("reject", "supplier-b", "reject-c1", "business-b")
            assert wait_status("business-b", "reject", "FAILED")["attemptCount"] == 1
            print("PASS: 不可恢复业务错误直接失败", flush=True)

            # 启动发送进程的同时观察任务，避免发布客户端关闭耗时错过 PROCESSING 窗口。
            import concurrent.futures
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                sent = executor.submit(publish, "crash", "supplier-a", "slow-c1")
                before = wait_status("business-a", "crash", "PROCESSING")
                app.kill()
                app.wait(timeout=10)
                sent.result()
            app = start_app(app_log)
            restored = wait_status("business-a", "crash", "FAILED", 40)
            assert restored["attemptCount"] == 5 and restored["totalAttempts"] == 5
            assert restored["replayCount"] == 0 and before["attemptCount"] >= 1
            assert api(task_path("business-a", "retry"))["totalAttempts"] == 10
            print("PASS: 强制结束 Java 进程后恢复任务，预算不清零，失败任务不自动重投", flush=True)

            try:
                api(task_path("business-a", "retry") + "/retry", "POST", token="wrong")
                raise AssertionError("未授权重投不应成功")
            except urllib.error.HTTPError as error:
                assert error.code == 401
            print("PASS: 运维接口拒绝未授权调用", flush=True)
            publish("invalid", raw="not-json")
            print("PASS: 已发送非法消息供 DLQ 验证（需等待一次 MQ 重试，另查 Broker）", flush=True)
            # 给消费重试和进入死信留出时间；由文档命令检查内容。
            time.sleep(15)
            print("SMOKE OK: " + PREFIX, flush=True)
        finally:
            for process in (app, mock):
                if process is not None and process.poll() is None:
                    process.terminate()
                    process.wait(timeout=15)
