#!/usr/bin/env python3
"""Stand-in for the camera's HTTP API, for testing the app without the camera.

    python3 tools/mock_camera.py <folder-of-jpgs> [port]

In the app: Settings > Direct camera address > http://10.0.2.2:8080 (emulator)
or http://<this-computer's-ip>:8080 (phone on the same Wi-Fi).
Every third file is reported as a video, like the camera's "2 photos + 1 video" mode.
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

folder = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
files = sorted(f for f in os.listdir(folder) if f.lower().endswith((".jpg", ".jpeg")))
# ids start at 1001 like the real firmware
media = {
    1001 + i: {
        "id": 1001 + i,
        "type": 2 if i % 3 == 2 else 1,
        "date": "2026-11-%02d %02d:%02d:07" % (1 + i // 4, (5 + i * 3) % 24, (i * 7) % 60),
        "size": os.path.getsize(os.path.join(folder, f)),
        "path": os.path.join(folder, f),
    }
    for i, f in enumerate(files)
}


class Handler(BaseHTTPRequestHandler):
    def send_json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        p = self.path.strip("/").split("/")
        if p[:2] == ["cmd", "standby"]:
            return self.send_json({"code": 0})
        if p[:2] == ["cmd", "info"]:
            data = {
                "1": {"brand": "GardePro", "product": "E9 (mock)"},
                "2": {"temperature": 11, "voltage": 7.9, "vol_value": 82, "ext_power": 0},
                "3": {"total": 32000, "used": 4100},
            }.get(p[2] if len(p) > 2 else "", None)
            return self.send_json({"code": 0, "data": data} if data else {"code": -1})
        if p[:2] == ["list", "detail"] and len(p) == 5:
            if p[2] == "MP4":
                return self.send_json({"code": -1, "desc": "illegeal para"})
            below, count = int(p[3]), min(int(p[4]), 40)  # firmware caps the page size
            ids = sorted((i for i in media if i < below), reverse=True)[:count]
            rows = [{k: v for k, v in media[i].items() if k != "path"} for i in ids]
            return self.send_json({"code": 0, "data": rows})
        if p[0] in ("thumb", "file") and len(p) == 3 and int(p[1]) in media:
            with open(media[int(p[1])]["path"], "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(500)
        self.send_header("Content-Length", "0")
        self.end_headers()


print(f"mock camera: {len(media)} files on port {port}")
ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
