"""
大模型（LLM）交互模块 - 通过 Ollama 调用本地大模型
实现桥梁缺陷检测结果的智能解读、维修建议生成、自然语言问答

支持两种模式：
1. 文本分析：将 YOLO 检测结果发给大模型进行智能解读
2. 视觉问答：将图片 + 问题发给多模态大模型（如 Gemma4）直接分析
"""

import json
import sys
import base64
from typing import Optional


class OllamaClient:
    """Ollama 大模型客户端，封装 HTTP API 调用"""

    def __init__(self, base_url: str = "http://localhost:11434", model: str = "gemma4:e4b"):
        """
        Args:
            base_url: Ollama 服务地址
            model: 使用的模型名称（需已通过 ollama pull 下载）
        """
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.generate_url = f"{self.base_url}/api/generate"

    def chat(self, prompt: str, system_prompt: str = None) -> dict:
        """
        纯文本对话（无图片）

        Args:
            prompt: 用户问题
            system_prompt: 系统提示词（角色设定）

        Returns:
            {"success": True/False, "response": "回答文本", "model": "模型名"}
        """
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": False,
        }
        if system_prompt:
            payload["system"] = system_prompt

        print(f"[LLM] 发送文本请求, 模型={self.model}", file=sys.stderr)

        try:
            import requests
        except ImportError:
            return {"success": False, "error": "请安装 requests 库: pip install requests"}

        try:
            resp = requests.post(self.generate_url, json=payload, timeout=120)
            resp.raise_for_status()
            data = resp.json()
            response_text = data.get("response", "")

            print(f"[LLM] 收到回复, 长度={len(response_text)}字符", file=sys.stderr)
            return {
                "success": True,
                "response": response_text.strip(),
                "model": self.model,
            }
        except requests.exceptions.ConnectionError:
            return {
                "success": False,
                "error": f"无法连接到 Ollama ({self.base_url})，请确保 Ollama 已启动",
            }
        except Exception as e:
            return {"success": False, "error": f"Ollama 调用失败: {str(e)}"}

    def chat_with_image(self, prompt: str, image_path: str) -> dict:
        """
        多模态对话（图片 + 文字）
        要求模型支持视觉输入（如 gemma4、llava 等）

        Args:
            prompt: 问题文字
            image_path: 图片文件路径

        Returns:
            {"success": True/False, "response": "回答文本"}
        """
        # 读取图片并转为 Base64
        try:
            with open(image_path, "rb") as f:
                image_bytes = f.read()
            image_base64 = base64.b64encode(image_bytes).decode("utf-8")
        except FileNotFoundError:
            return {"success": False, "error": f"图片不存在: {image_path}"}

        payload = {
            "model": self.model,
            "prompt": prompt,
            "images": [image_base64],
            "stream": False,
        }

        print(f"[LLM] 发送多模态请求, 图片={image_path}", file=sys.stderr)

        try:
            import requests
        except ImportError:
            return {"success": False, "error": "请安装 requests 库: pip install requests"}

        try:
            resp = requests.post(self.generate_url, json=payload, timeout=120)
            resp.raise_for_status()
            data = resp.json()
            response_text = data.get("response", "")

            print(f"[LLM] 收到回复, 长度={len(response_text)}字符", file=sys.stderr)
            return {
                "success": True,
                "response": response_text.strip(),
                "model": self.model,
            }
        except requests.exceptions.ConnectionError:
            return {
                "success": False,
                "error": f"无法连接到 Ollama ({self.base_url})，请确保 Ollama 已启动",
            }
        except Exception as e:
            return {"success": False, "error": f"Ollama 调用失败: {str(e)}"}


# ============================================================
# 桥梁缺陷分析专用提示词
# ============================================================

SYSTEM_PROMPT = """你是一位资深的桥梁工程检测专家，拥有20年桥梁病害诊断经验。
你的任务是分析 YOLO 深度学习模型对桥梁图片的自动检测结果，并给出专业解读。

请遵循以下规则：
1. 用简洁专业的语言解读检测到的每种缺陷的含义和危险程度
2. 对"严重"级别缺陷给出维修优先级建议
3. 如果检测结果为空，说明可能原因（图片质量/拍摄角度/确实无损）
4. 回答长度控制在200字以内，重点突出
5. 使用中文回答"""


def build_defect_analysis_prompt(detection_result: dict, user_question: str = None) -> str:
    """
    根据 YOLO 检测结果构建发给大模型的提示词

    Args:
        detection_result: BridgeDefectDetector.detect() 的返回结果
        user_question: 用户额外问题（可选）

    Returns:
        组装好的完整提示词
    """
    if not detection_result.get("success"):
        return f"检测失败: {detection_result.get('error', '未知错误')}"

    defects = detection_result.get("defects", [])
    summary = detection_result.get("summary", "")
    img_size = detection_result.get("image_size", [])

    prompt_parts = [
        f"【桥梁检测数据】",
        f"图片尺寸: {img_size[0]}×{img_size[1]} 像素",
        f"检测到缺陷数量: {len(defects)}",
        f"",
    ]

    if defects:
        prompt_parts.append("检测到的具体缺陷：")
        for i, d in enumerate(defects, 1):
            prompt_parts.append(
                f"  {i}. {d['class_name']} | "
                f"置信度: {d['confidence']:.0%} | "
                f"严重程度: {d['severity']} | "
                f"位置: ({d['bbox_center'][0]:.0f}, {d['bbox_center'][1]:.0f})"
            )
        prompt_parts.append(f"")
        prompt_parts.append(f"自动摘要: {summary}")

    if user_question:
        prompt_parts.append(f"")
        prompt_parts.append(f"用户问题: {user_question}")
    else:
        prompt_parts.append(f"")
        prompt_parts.append(f"请对该桥梁检测结果进行专业解读，并给出维修建议。")

    return "\n".join(prompt_parts)


# ============================================================
# 命令行测试入口
# ============================================================
if __name__ == "__main__":
    # 测试纯文本对话
    client = OllamaClient()
    result = client.chat("你好，请简单介绍你自己", system_prompt="你是一个桥梁检测专家助手")
    print(json.dumps(result, ensure_ascii=False, indent=2))
