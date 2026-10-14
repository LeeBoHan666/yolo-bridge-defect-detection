"""
桥梁缺陷智能检测系统 - 主程序入口
=====================================
架构: Java Swing(前端) → python app.py(后端) → YOLO检测 + Ollama大模型解读

运行方式:
  - 命令行模式（供Java调用）: python app.py --image <图片路径> [--question <问题>]
  - 交互模式（直接运行）:     python app.py
  - Web界面模式:              streamlit run app_web.py

输出: JSON 格式检测结果（stdout），Java 前端解析后展示
"""

import argparse
import json
import os
import sys
from datetime import datetime
from pathlib import Path

# 将当前目录加入模块搜索路径，确保能导入同目录下的 detect 和 llm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from detect import BridgeDefectDetector
from llm.ollama_chat import OllamaClient, build_defect_analysis_prompt, SYSTEM_PROMPT


def run_full_pipeline(
    image_path: str,
    question: str = None,
    model_path: str = None,
    ollama_url: str = "http://localhost:11434",
    ollama_model: str = "gemma4:e4b",
    output_dir: str = None,
    enable_llm: bool = True,
    conf_threshold: float = 0.25,
) -> dict:
    """
    完整检测流水线：YOLO检测 → (可选)LLM解读 → 保存结果

    Args:
        image_path: 待检测的桥梁图片路径
        question: 用户提问（可选，用于LLM分析）
        model_path: YOLO权重路径
        ollama_url: Ollama服务地址
        ollama_model: Ollama模型名
        output_dir: 结果输出目录
        enable_llm: 是否启用大模型分析
        conf_threshold: 检测置信度阈值

    Returns:
        完整结果 dict
    """
    start_time = datetime.now()

    # ========== Step 1: YOLO 缺陷检测 ==========
    print("=" * 60, file=sys.stderr)
    print("[流水线] Step 1/3: 加载 YOLO 模型并执行检测...", file=sys.stderr)

    try:
        detector = BridgeDefectDetector(model_path)
    except FileNotFoundError as e:
        return {"success": False, "stage": "init", "error": str(e)}

    detection = detector.detect(image_path, conf_threshold=conf_threshold)

    if not detection["success"]:
        return {"success": False, "stage": "detection", "error": detection.get("error", "检测失败")}

    # 保存标注图片
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)
        base_name = os.path.splitext(os.path.basename(image_path))[0]
        annotated_path = os.path.join(output_dir, f"{base_name}_detected.jpg")
    else:
        annotated_path = None

    annotated_path = detector.draw_annotations(image_path, detection["defects"], annotated_path)

    # ========== Step 2: LLM 智能分析 ==========
    llm_result = None
    if enable_llm:
        print("[流水线] Step 2/3: 调用大模型进行专业解读...", file=sys.stderr)
        from llm.bridge_standards import enrich_prompt
        client = OllamaClient(base_url=ollama_url, model=ollama_model)
        llm_prompt = build_defect_analysis_prompt(detection, question)
        # RAG: inject relevant national standards into prompt
        llm_prompt = enrich_prompt(llm_prompt)
        llm_result = client.chat(llm_prompt, system_prompt=SYSTEM_PROMPT)
    else:
        print("[流水线] Step 2/3: 跳过（LLM未启用）", file=sys.stderr)

    # ========== Step 3: 组装返回结果 ==========
    print("[流水线] Step 3/3: 整理结果...", file=sys.stderr)
    elapsed = (datetime.now() - start_time).total_seconds()

    result = {
        "success": True,
        "timestamp": start_time.isoformat(),
        "elapsed_seconds": round(elapsed, 2),
        # 检测结果
        "detection": {
            "num_defects": detection["num_defects"],
            "defects": detection["defects"],
            "summary": detection["summary"],
        },
        # 标注图片路径
        "annotated_image": annotated_path,
        # 大模型分析
        "llm_analysis": llm_result,
        # 元信息
        "metadata": {
            "model_path": detector.model_path,
            "ollama_model": ollama_model if enable_llm else None,
            "image_size": detection["image_size"],
        },
    }

    # 保存 JSON 结果到输出目录
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)
        json_path = os.path.join(output_dir, f"{base_name}_result.json")
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print(f"[流水线] JSON 结果已保存: {json_path}", file=sys.stderr)

    print(f"[流水线] 全部完成! 耗时 {elapsed:.1f}秒", file=sys.stderr)
    print("=" * 60, file=sys.stderr)

    return result


def main():
    parser = argparse.ArgumentParser(
        description="桥梁缺陷智能检测系统 - Python后端",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python app.py --image bridge.jpg
  python app.py --image bridge.jpg --question "这些缺陷需要紧急维修吗？"
  python app.py --image bridge.jpg --output-dir ./results --no-llm
  python app.py --image bridge.jpg --ollama-model gemma4:e4b --conf 0.3
        """,
    )
    parser.add_argument("--image", "-i", required=True, help="待检测的桥梁图片路径")
    parser.add_argument("--question", "-q", default=None, help="向大模型提问（可选）")
    parser.add_argument("--model-path", "-m", default=None, help="YOLO模型权重路径")
    parser.add_argument("--ollama-url", default="http://localhost:11434", help="Ollama服务地址")
    parser.add_argument("--ollama-model", default="gemma4:e4b", help="Ollama模型名称")
    parser.add_argument("--output-dir", "-o", default=None, help="结果输出目录")
    parser.add_argument("--conf", type=float, default=0.25, help="检测置信度阈值 (0~1)")
    parser.add_argument("--no-llm", action="store_true", help="禁用大模型分析，仅做检测")
    parser.add_argument("--json-only", action="store_true", help="仅输出JSON到stdout，不打印日志")

    args = parser.parse_args()

    # 如果未指定输出目录，默认输出到图片所在目录的 output 子文件夹
    output_dir = args.output_dir
    if output_dir is None:
        img_parent = os.path.dirname(os.path.abspath(args.image))
        output_dir = os.path.join(img_parent, "output")

    # 执行流水线
    result = run_full_pipeline(
        image_path=args.image,
        question=args.question,
        model_path=args.model_path,
        ollama_url=args.ollama_url,
        ollama_model=args.ollama_model,
        output_dir=output_dir,
        enable_llm=not args.no_llm,
        conf_threshold=args.conf,
    )

    # 输出 JSON 结果到 stdout（Java 前端读取此输出）
    # 为了确保 Java 能精确解析，额外加一行分隔标记
    print("===JSON_RESULT_START===")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print("===JSON_RESULT_END===")

    # 返回退出码
    sys.exit(0 if result["success"] else 1)


# ============================================================
# 交互模式（无命令行参数时进入）
# ============================================================
if __name__ == "__main__":
    if len(sys.argv) > 1:
        # 有命令行参数，走命令行模式
        main()
    else:
        # 无参数，提供交互式测试
        print("=" * 60)
        print("  桥梁缺陷智能检测系统 - Python 后端")
        print("=" * 60)
        print()
        print("使用方法：")
        print("  python app.py --image <图片路径> [--question <问题>]")
        print()
        print("示例：")
        print('  python app.py --image "D:/test.jpg" --question "分析缺陷"')
        print("=" * 60)
