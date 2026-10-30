import argparse
import http.client
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


class CountingProxy(ThreadingHTTPServer):
    daemon_threads = True
    request_lock = threading.Lock()

    def __init__(self, address, handler, target, counter_file):
        super().__init__(address, handler)
        self.target = urlsplit(target)
        self.counter_file = counter_file

    def count_request(self):
        with self.request_lock, open(self.counter_file, "a", encoding="ascii") as counter:
            counter.write("1\n")


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __getattr__(self, name):
        if name.startswith("do_"):
            return self.forward
        raise AttributeError(name)

    def forward(self):
        self.server.count_request()
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        connection = http.client.HTTPConnection(
            self.server.target.hostname,
            self.server.target.port,
            timeout=30,
        )
        try:
            headers = {
                key: value
                for key, value in self.headers.items()
                if key.lower()
                not in {
                    "connection",
                    "content-length",
                    "host",
                    "keep-alive",
                    "proxy-connection",
                    "te",
                    "trailer",
                    "transfer-encoding",
                    "upgrade",
                }
            }
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            self.send_response_only(response.status, response.reason)
            for key, value in response.getheaders():
                if key.lower() not in {
                    "connection",
                    "content-length",
                    "keep-alive",
                    "proxy-authenticate",
                    "proxy-authorization",
                    "te",
                    "trailer",
                    "transfer-encoding",
                    "upgrade",
                }:
                    self.send_header(key, value)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD" and response.status not in {204, 304}:
                self.wfile.write(payload)
        finally:
            connection.close()
            self.close_connection = True

    def log_message(self, format_string, *args):
        return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True)
    parser.add_argument("--counter-file", required=True)
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()

    proxy = CountingProxy(
        ("127.0.0.1", args.port),
        ProxyHandler,
        args.target,
        args.counter_file,
    )
    print(f"READY {proxy.server_address[1]}", flush=True)
    proxy.serve_forever()


if __name__ == "__main__":
    main()
