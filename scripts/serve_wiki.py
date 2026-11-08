#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Local Docsify Wiki Preview Server for MaaMeow AI Control.
Uses Python's built-in http.server, auto-detects a free port, and serves the `wiki/` folder.

Usage:
    python scripts/serve_wiki.py              # Start server on default/auto port (e.g. 3000)
    python scripts/serve_wiki.py --port 8080   # Specify preferred port
    python scripts/serve_wiki.py --open        # Auto-open browser
    python scripts/serve_wiki.py --test        # Run self-test verifying all files serve 200 OK
"""

from __future__ import annotations

import argparse
import http.server
import os
import socket
import socketserver
import sys
import threading
import time
import urllib.request
import webbrowser
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
WIKI_DIR = PROJECT_ROOT / "wiki"


def find_free_port(start_port: int = 3000, max_attempts: int = 50) -> int:
    """Finds an available TCP port starting from start_port."""
    for port in range(start_port, start_port + max_attempts):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    # Fallback to system-allocated dynamic port
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class WikiRequestHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WIKI_DIR), **kwargs)

    def log_message(self, format: str, *args):
        # Concise logging in terminal
        sys.stderr.write(f"[{time.strftime('%H:%M:%S')}] {args[0]} -> {args[1]}\n")


def run_self_test(host: str, port: int) -> bool:
    """Verifies that Docsify index and all referenced wiki markdown files return 200 OK."""
    base_url = f"http://{host}:{port}"
    test_paths = [
        "/",
        "/index.html",
        "/_sidebar.md",
        "/_navbar.md",
        "/_coverpage.md",
        "/README.md",
        "/architecture/design_draft.md",
        "/architecture/reuse_audit.md",
        "/architecture/account_personalization.md",
        "/architecture/ai_fallback_decision.md",
        "/hand/mcp_tools.md",
        "/hand/service_and_security.md",
        "/hand/scheduled_tasks.md",
        "/hand/in_app_chat.md",
        "/brain/rag_knowledge_base.md",
        "/brain/anti_hallucination.md",
        "/brain/evaluation_benchmark.md",
        "/scripting/script_recorder.md",
        "/client/in_app_chat.md",
        "/client/external_clients.md",
    ]

    print("\n🔍 Running Wiki Self-Test on:", base_url)
    all_passed = True
    for rel_path in test_paths:
        target_url = f"{base_url}{rel_path}"
        try:
            req = urllib.request.Request(target_url, headers={"User-Agent": "WikiSelfTester/1.0"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                status = resp.status
                length = len(resp.read())
                if status == 200 and length > 0:
                    print(f"  ✅ [200 OK] {rel_path} ({length:,} bytes)")
                else:
                    print(f"  ❌ [{status}] {rel_path} (Empty or unexpected)")
                    all_passed = False
        except Exception as e:
            print(f"  ❌ [FAIL] {rel_path}: {e}")
            all_passed = False

    if all_passed:
        print(f"\n🎉 All {len(test_paths)} wiki documents and assets served successfully with 0 errors/404s!")
    else:
        print("\n⚠️ Self-test failed: some assets returned non-200 responses.")
    return all_passed


def main():
    parser = argparse.ArgumentParser(description="Serve MaaMeow AI Control Docsify Wiki")
    parser.add_argument("--port", type=int, default=3000, help="Preferred starting port (default: 3000)")
    parser.add_argument("--host", type=str, default="127.0.0.1", help="Host interface (default: 127.0.0.1)")
    parser.add_argument("--open", action="store_true", help="Automatically open browser on start")
    parser.add_argument("--test", action="store_true", help="Run automated HTTP self-test and exit")
    args = parser.parse_args()

    if not WIKI_DIR.is_dir():
        print(f"Error: Wiki directory does not exist at '{WIKI_DIR}'", file=sys.stderr)
        sys.exit(1)

    port = find_free_port(args.port)
    server_address = (args.host, port)

    class ThreadedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
        daemon_threads = True

    httpd = ThreadedHTTPServer(server_address, WikiRequestHandler)
    url = f"http://{args.host}:{port}/"

    if args.test:
        server_thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        server_thread.start()
        passed = run_self_test(args.host, port)
        httpd.shutdown()
        httpd.server_close()
        sys.exit(0 if passed else 1)

    print("=" * 68)
    print(f"🚀 MaaMeow AI Control Wiki is running!")
    print(f"📖 Local URL: {url}")
    print(f"📁 Serving Directory: {WIKI_DIR}")
    print("   Press Ctrl+C to stop the server.")
    print("=" * 68)

    if args.open:
        webbrowser.open(url)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping wiki server...")
    finally:
        httpd.shutdown()
        httpd.server_close()


if __name__ == "__main__":
    main()
