import json
import time
from http.server import HTTPServer, BaseHTTPRequestHandler

class MockOpenAiHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_len = int(self.headers.get("Content-Length", 0))
        post_body = self.rfile.read(content_len)
        try:
            req = json.loads(post_body.decode("utf-8"))
        except Exception:
            req = {}
        messages = req.get("messages", [])

        print(f"\n[MockServer] Received request with {len(messages)} messages:")
        for m in messages:
            role = m.get("role")
            content = m.get("content")
            tc = m.get("tool_calls")
            print(f"  [{role}] {content} (tool_calls={tc})")

        # Determine if this request includes tool execution results
        has_tool_role = any(m.get("role") == "tool" for m in messages)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        if not has_tool_role:
            print("[MockServer] Emitting tool call: maa_status...")
            # Stream tool call
            chunk1 = {
                "id": "chatcmpl-mock-1",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "mock-gpt",
                "choices": [{
                    "index": 0,
                    "delta": {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [{
                            "index": 0,
                            "id": "call_maa_status_01",
                            "type": "function",
                            "function": {
                                "name": "maa_status",
                                "arguments": "{}"
                            }
                        }]
                    },
                    "finish_reason": None
                }]
            }
            self.wfile.write(f"data: {json.dumps(chunk1)}\n\n".encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.1)

            chunk2 = {
                "id": "chatcmpl-mock-1",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "mock-gpt",
                "choices": [{
                    "index": 0,
                    "delta": {},
                    "finish_reason": "tool_calls"
                }]
            }
            self.wfile.write(f"data: {json.dumps(chunk2)}\n\n".encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.05)

            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            print("[MockServer] Tool call emitted, waiting for tool result...")
        else:
            print("[MockServer] Received tool result from MaaMeow! Generating final answer...")
            tool_msg = next((m for m in reversed(messages) if m.get("role") == "tool"), {})
            tool_res = tool_msg.get("content", "")
            print(f"[MockServer] Raw tool result: {tool_res[:200]}")

            answer_text = "已查询当前 Maa 运行状态：MaaCore 已加载且处于就绪状态，当前没有正在执行的任务。"
            chunk1 = {
                "id": "chatcmpl-mock-2",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "mock-gpt",
                "choices": [{
                    "index": 0,
                    "delta": {
                        "role": "assistant",
                        "content": answer_text
                    },
                    "finish_reason": None
                }]
            }
            self.wfile.write(f"data: {json.dumps(chunk1)}\n\n".encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.1)

            chunk2 = {
                "id": "chatcmpl-mock-2",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "mock-gpt",
                "choices": [{
                    "index": 0,
                    "delta": {},
                    "finish_reason": "stop"
                }]
            }
            self.wfile.write(f"data: {json.dumps(chunk2)}\n\n".encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.05)

            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            print("[MockServer] Final answer completed successfully.")

    def log_message(self, format, *args):
        pass

if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 8080), MockOpenAiHandler)
    print("Mock OpenAI Server listening on 127.0.0.1:8080...")
    server.serve_forever()
