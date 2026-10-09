"""两个本地模拟供应商。contactId: fail-* 临时失败，reject-* 拒绝，slow-* 延迟。"""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Supplier(BaseHTTPRequestHandler):
    def do_POST(self):
        is_a = self.server.server_port == 18081
        expected_path = "/contacts/status" if is_a else "/v1/contact/enable"
        header = "X-Api-Key" if is_a else "Authorization"
        expected_token = "demo-a" if is_a else "Bearer demo-b"
        if self.path != expected_path:
            self.send_error(404)
            return
        if self.headers.get(header) != expected_token:
            self.send_error(401)
            return
        try:
            data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            contact = data["contactId" if is_a else "user_id"]
            if is_a:
                valid = data.get("status") in ("ACTIVE", "INACTIVE")
            else:
                valid = isinstance(data.get("enabled"), bool)
            if not isinstance(contact, str) or not valid:
                raise ValueError("invalid payload")
        except (ValueError, KeyError, TypeError):
            self.send_error(400)
            return
        if contact.startswith("slow-"):
            time.sleep(8)
        if contact.startswith("fail-"):
            result = {"code": 1001} if is_a else {"result": "TEMPORARY_FAILURE"}
        elif contact.startswith("reject-"):
            result = {"code": 2001} if is_a else {"result": "INVALID_ARGUMENT"}
        else:
            result = {"code": 0} if is_a else {"result": "SUCCESS"}
        body = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


if __name__ == "__main__":
    servers = [ThreadingHTTPServer(("127.0.0.1", port), Supplier) for port in (18081, 18082)]
    for server in servers:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print("Mock suppliers: http://127.0.0.1:18081 and :18082", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        for server in servers:
            server.shutdown()
