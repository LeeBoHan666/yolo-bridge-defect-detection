"""
LLM 后端脚本 — 供 Java OllamaChat 调用
=======================================
Java 调用方式:
  python llm_backend.py --mode chat   --url ... --model ... --prompt "..." --output result.json
  python llm_backend.py --mode stream --url ... --model ... --prompt "..." --output result.json
  python llm_backend.py --mode image  --url ... --model ... --prompt "..." --image PATH --output result.json

输出: JSON 写入 --output 文件 {"success": true/false, "response": "...", "error": "..."}
stream 模式额外逐行输出 token 到 stdout，以 __STREAM_DONE__ 结束
"""

import argparse
import json
import sys
import base64
import os

try:
    import requests
except ImportError:
    print("ERROR: requests not installed. Run: pip install requests", file=sys.stderr)
    sys.exit(1)


def escape_json(s):
    """转义字符串为 JSON 安全格式"""
    if s is None:
        return ""
    return (s.replace("\\", "\\\\")
             .replace('"', '\\"')
             .replace("\n", "\\n")
             .replace("\r", "\\r")
             .replace("\t", "\\t"))


def unescape_json(s):
    """反转义 JSON 字符串"""
    result = []
    i = 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            nxt = s[i + 1]
            if nxt == "n": result.append("\n"); i += 2; continue
            if nxt == "t": result.append("\t"); i += 2; continue
            if nxt == "r": result.append("\r"); i += 2; continue
            if nxt == '"': result.append('"'); i += 2; continue
            if nxt == "\\": result.append("\\"); i += 2; continue
        result.append(s[i])
        i += 1
    return "".join(result)


def extract_response(json_text):
    """从 Ollama JSON 响应中提取 response 字段"""
    key = '"response":"'
    idx = json_text.find(key)
    if idx < 0:
        raise RuntimeError(f"Ollama response missing 'response' field: {json_text[:200]}")
    start = idx + len(key)

    # 状态机追踪转义引号
    end = start
    while end < len(json_text):
        c = json_text[end]
        if c == "\\" and end + 1 < len(json_text):
            end += 2
            continue
        if c == '"':
            after = end + 1
            if after >= len(json_text):
                break
            if json_text[after] in (",", "}"):
                break
        end += 1

    return unescape_json(json_text[start:end])


def strip_json_tail(text):
    """切除 JSON 尾部元数据"""
    if not text:
        return text
    for marker in ['","done"', '","context"', '","done_reason"', '"}']:
        idx = text.find(marker)
        if idx >= 0:
            text = text[:idx]
    return text


def do_chat(url, model, prompt):
    """非流式文本对话"""
    payload = {
        "model": model,
        "prompt": prompt,
        "stream": False,
    }
    resp = requests.post(url, json=payload, timeout=(30, 300))
    resp.raise_for_status()
    text = resp.text
    return strip_json_tail(extract_response(text))


def do_stream(url, model, prompt, cancel_check=None):
    """流式文本对话，逐 token yield，返回完整文本"""
    payload = {
        "model": model,
        "prompt": prompt,
        "stream": True,
    }
    resp = requests.post(url, json=payload, stream=True, timeout=(30, 300))
    resp.raise_for_status()

    full = []
    for raw_line in resp.iter_lines(decode_unicode=True):
        if not raw_line:
            continue
        # iter_lines 在某些版本返回 bytes，统一转 str
        if isinstance(raw_line, bytes):
            raw_line = raw_line.decode("utf-8")
        line = raw_line.strip()
        if not line:
            continue

        # 提取 token
        key = '"response":"'
        idx = line.find(key)
        if idx < 0:
            continue
        start = idx + len(key)
        end = start
        while end < len(line):
            c = line[end]
            if c == "\\" and end + 1 < len(line):
                end += 2
                continue
            if c == '"':
                after = end + 1
                if after >= len(line) or line[after] in (",", "}"):
                    break
            end += 1
        if end <= start:
            continue
        token = unescape_json(line[start:end])
        full.append(token)
        # 输出到 stdout（Java 逐行读取）
        sys.stdout.buffer.write((token + '\n').encode('utf-8'))
        sys.stdout.buffer.flush()

        if '"done":true' in line:
            break

    sys.stdout.buffer.write(b'__STREAM_DONE__\n')
    sys.stdout.buffer.flush()
    return strip_json_tail("".join(full))


def do_image(url, model, prompt, image_path=None, image_base64=None):
    """图片 + 文字多模态对话"""
    if image_path:
        with open(image_path, "rb") as f:
            image_base64 = base64.b64encode(f.read()).decode("utf-8")
    elif not image_base64:
        raise ValueError("必须提供 --image 或 --image-base64")

    payload = {
        "model": model,
        "prompt": prompt,
        "images": [image_base64],
        "stream": False,
    }
    resp = requests.post(url, json=payload, timeout=(30, 300))
    resp.raise_for_status()
    text = resp.text
    return strip_json_tail(extract_response(text))


def write_result(output_path, success, response="", error=""):
    """写入 JSON 结果文件"""
    result = {"success": success}
    if success:
        result["response"] = response
    else:
        result["error"] = error
    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False)


def main():
    import traceback
    parser = argparse.ArgumentParser(description="LLM Backend for Java OllamaChat")
    parser.add_argument("--mode", required=True, choices=["chat", "stream", "image"])
    parser.add_argument("--url", required=True, help="Ollama API URL")
    parser.add_argument("--model", required=True, help="Model name")
    parser.add_argument("--prompt", required=True, help="Prompt text")
    parser.add_argument("--image", default=None, help="Image file path (for image mode)")
    parser.add_argument("--image-base64", default=None, help="Base64 image data (for image mode)")
    parser.add_argument("--image-base64-file", default=None, help="File containing Base64 image data")
    parser.add_argument("--output", required=True, help="Output JSON file path")
    args = parser.parse_args()

    try:
        if args.mode == "chat":
            response = do_chat(args.url, args.model, args.prompt)
            write_result(args.output, True, response=response)

        elif args.mode == "stream":
            response = do_stream(args.url, args.model, args.prompt)
            write_result(args.output, True, response=response)

        elif args.mode == "image":
            # 支持三种传图方式
            img_b64 = args.image_base64
            if args.image_base64_file:
                with open(args.image_base64_file, "r") as f:
                    img_b64 = f.read().strip()
            response = do_image(args.url, args.model, args.prompt,
                                image_path=args.image,
                                image_base64=img_b64)
            write_result(args.output, True, response=response)

    except Exception as e:
        import traceback
        tb = traceback.format_exc()
        write_result(args.output, False, error=str(e) + "\n" + tb)
        print(f"ERROR: {e}", file=sys.stderr)
        print(tb, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
