#!/usr/bin/env python3
"""
Gemma 4 E4B QLoRA → GGUF 转换脚本
====================================
功能：
  1. 合并 LoRA adapter 到 base model（如果存在）
  2. 调用 llama.cpp convert_hf_to_gguf.py 转为 GGUF
  3. 用 llama-quantize 量化到 q4_k_m
  4. 生成 Ollama Modelfile

用法：
  # 从 merged 模型直接转（跳过硬合并）
  python convert_to_gguf.py --merged-path ./gemma4-bridge-merged

  # 从 base + lora 合并再转
  python convert_to_gguf.py \
    --base-model ./gemma \
    --lora-path ./gemma4-bridge-lora

  # 完整指定所有参数
  python convert_to_gguf.py \
    --base-model ./gemma \
    --lora-path ./gemma4-bridge-lora \
    --output-dir ./gguf-output \
    --quant q4_k_m \
    --model-name gemma4-bridge

依赖：
  pip install torch transformers peft accelerate sentencepiece
  + llama.cpp（脚本会自动 clone/更新）
"""

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path


# ── 颜色输出 ──────────────────────────────────────────────
def green(s):  return f"\033[92m{s}\033[0m"
def yellow(s): return f"\033[93m{s}\033[0m"
def red(s):    return f"\033[91m{s}\033[0m"
def bold(s):   return f"\033[1m{s}\033[0m"

def log(msg):       print(f"[{green('✓')}] {msg}")
def warn(msg):      print(f"[{yellow('!')}] {msg}")
def error(msg):     print(f"[{red('✗')}] {msg}")
def section(title): print(f"\n{bold('='*60)}\n{bold('  ' + title)}\n{bold('='*60)}\n")


# ── 配置 ──────────────────────────────────────────────────
LLAMA_CPP_REPO = "https://github.com/ggml-org/llama.cpp.git"
QUANTIZATION_CHOICES = [
    "f16", "f32", "bf16",
    "q8_0",
    "q6_k", "q5_k_m", "q5_0", "q4_k_m", "q4_0",
    "q3_k_m", "q2_k", "iq4_nl",
]


def get_args():
    p = argparse.ArgumentParser(
        description="Gemma 4 QLoRA → GGUF 一键转换",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--base-model", type=str, default=None,
                   help="Base Gemma 4 模型路径（HuggingFace 格式）")
    p.add_argument("--lora-path", type=str, default=None,
                   help="QLoRA adapter 路径（peft 格式）")
    p.add_argument("--merged-path", type=str, default=None,
                   help="已合并好的 HF 模型路径（跳过 merge 步骤）")
    p.add_argument("--output-dir", type=str, default="./gguf-output",
                   help="GGUF 输出目录 (默认: ./gguf-output)")
    p.add_argument("--quant", type=str, default="q4_k_m",
                   choices=QUANTIZATION_CHOICES,
                   help="量化级别 (默认: q4_k_m)")
    p.add_argument("--model-name", type=str, default="gemma4-bridge",
                   help="模型名，用于 Ollama (默认: gemma4-bridge)")
    p.add_argument("--llama-cpp-dir", type=str, default="./llama.cpp",
                   help="llama.cpp 目录 (默认: ./llama.cpp，会自动 clone)")
    p.add_argument("--skip-merge", action="store_true",
                   help="跳过 LoRA merge（当 --merged-path 已经给定时自动跳过）")
    p.add_argument("--skip-quantize", action="store_true",
                   help="只生成 f16 GGUF，不量化")
    p.add_argument("--cpu", action="store_true", default=True,
                   help="用 CPU 做 merge（默认: True，避免 GPU OOM）")
    p.add_argument("--build-dir", type=str, default=None,
                   help="llama.cpp build 目录 (默认: <llama-cpp-dir>/build)")
    return p.parse_args()


# ── Step 0: 准备 llama.cpp ───────────────────────────────
def ensure_llama_cpp(llama_cpp_dir: Path) -> Path:
    """Clone 或更新 llama.cpp，确保是最新版本（支持 Gemma 4）"""
    section("Step 0: 准备 llama.cpp")

    if llama_cpp_dir.exists():
        log(f"llama.cpp 已存在: {llama_cpp_dir}")
        # 拉取最新代码
        log("拉取最新代码...")
        subprocess.run(
            ["git", "fetch", "origin", "master"],
            cwd=str(llama_cpp_dir), check=True,
        )
        subprocess.run(
            ["git", "reset", "--hard", "origin/master"],
            cwd=str(llama_cpp_dir), check=True,
        )
        log("已更新到最新 commit")
    else:
        log(f"克隆 llama.cpp 到 {llama_cpp_dir}...")
        subprocess.run(
            ["git", "clone", "--depth", "1", LLAMA_CPP_REPO, str(llama_cpp_dir)],
            check=True,
        )
        log("克隆完成")

    # 安装 Python 依赖
    req_file = llama_cpp_dir / "requirements" / "requirements-convert_hf_to_gguf.txt"
    if req_file.exists():
        log("安装 convert 依赖...")
        subprocess.run(
            [sys.executable, "-m", "pip", "install", "-r", str(req_file), "-q"],
            check=True,
        )

    return llama_cpp_dir


# ── Step 1: Merge LoRA ────────────────────────────────────
def merge_lora(base_model: str, lora_path: str, output_dir: Path) -> Path:
    """合并 LoRA adapter 到 base model"""
    section("Step 1: 合并 LoRA → HF 模型")

    import torch
    from transformers import AutoModelForImageTextToText, AutoProcessor
    from peft import PeftModel

    log(f"Base model:  {base_model}")
    log(f"LoRA path:    {lora_path}")
    log(f"Output:       {output_dir}")

    # 加载 base model（CPU，省显存）
    log("加载 base model (CPU, bf16)...")
    t0 = time.time()
    model = AutoModelForImageTextToText.from_pretrained(
        base_model,
        torch_dtype=torch.bfloat16,
        device_map="cpu",
        local_files_only=True,
    )
    log(f"Base model 加载完成 ({time.time() - t0:.0f}s)")

    # 加载 LoRA
    log("加载 LoRA adapter...")
    model = PeftModel.from_pretrained(model, lora_path)
    log(f"LoRA trainable: {sum(p.numel() for p in model.parameters() if p.requires_grad):,}")

    # Merge
    log("合并中 (merge_and_unload)...")
    t0 = time.time()
    model = model.merge_and_unload()
    log(f"合并完成 ({time.time() - t0:.0f}s)")

    # 保存
    log(f"保存到 {output_dir}...")
    output_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(output_dir), safe_serialization=True)

    # 保存 processor/tokenizer
    log("保存 processor & tokenizer...")
    processor = AutoProcessor.from_pretrained(base_model, local_files_only=True)
    processor.save_pretrained(str(output_dir))

    # 确认关键文件
    _verify_hf_model(output_dir)

    del model
    log(f"Merge 完成 → {output_dir}")
    return output_dir


def _verify_hf_model(path: Path):
    """验证 HF 模型完整性"""
    required = ["config.json", "tokenizer.json"]
    for f in required:
        fp = path / f
        if not fp.exists():
            warn(f"缺少文件: {f}（转换可能失败）")
        else:
            log(f"  ✓ {f} ({fp.stat().st_size:,} bytes)")

    # 检查 model files
    shards = list(path.glob("model*.safetensors"))
    if not shards:
        warn("未找到 model*.safetensors，检查是否为 sharded 保存")
    else:
        total_mb = sum(s.stat().st_size for s in shards) / 1024 / 1024
        log(f"  ✓ {len(shards)} safetensors shard(s), 共 {total_mb:.0f} MB")

    # 特殊检查: tokenizer.model（Gemma 可能需要 SentencePiece）
    sp_model = path / "tokenizer.model"
    if not sp_model.exists():
        warn("缺少 tokenizer.model (SentencePiece)，llama.cpp 转换可能需要它")
        warn("如果转换报错，尝试: cp <base_model>/tokenizer.model <output>/")


# ── Step 2: Convert HF → GGUF (unquantized) ──────────────
def convert_to_gguf(
    hf_model_path: Path,
    llama_cpp_dir: Path,
    out_gguf: Path,
    outtype: str = "f16",
) -> Path:
    """调用 llama.cpp convert_hf_to_gguf.py"""
    section("Step 2: HF → GGUF (unquantized)")

    converter = llama_cpp_dir / "convert_hf_to_gguf.py"
    if not converter.exists():
        error(f"找不到转换脚本: {converter}")
        sys.exit(1)

    log(f"Converter: {converter}")
    log(f"输入模型:  {hf_model_path}")
    log(f"输出 GGUF:  {out_gguf}")
    log(f"输出类型:  {outtype}")

    cmd = [
        sys.executable, str(converter),
        str(hf_model_path),
        "--outfile", str(out_gguf),
        "--outtype", outtype,
    ]
    log(f"执行: {' '.join(cmd)}")
    log("这可能需要 10-30 分钟，取决于模型大小和磁盘速度...")

    t0 = time.time()
    result = subprocess.run(cmd, check=False, capture_output=False)

    if result.returncode != 0:
        error("GGUF 转换失败！")
        error("常见原因：")
        error("  1. 缺少 tokenizer.model → 从 base_model 复制过来")
        error("  2. 模型架构不兼容 → 检查 llama.cpp 是否最新")
        error("  3. 显存/内存不足 → 用 --merged-path 指定已合并模型")
        sys.exit(1)

    elapsed = (time.time() - t0) / 60
    size_mb = out_gguf.stat().st_size / 1024 / 1024 / 1024 if out_gguf.exists() else 0
    log(f"GGUF 生成完成 ({elapsed:.1f} min, {size_mb:.1f} GB)")
    return out_gguf


# ── Step 3: Quantize ──────────────────────────────────────
def quantize_gguf(
    llama_cpp_dir: Path,
    build_dir: Path,
    f16_gguf: Path,
    quant_gguf: Path,
    quant_type: str,
) -> Path:
    """量化 GGUF 文件"""
    section(f"Step 3: 量化 → {quant_type}")

    # 找 quantize 二进制
    os_name = "windows" if sys.platform == "win32" else "linux"
    bin_name = "llama-quantize.exe" if sys.platform == "win32" else "llama-quantize"

    quantize_bin = None
    search_paths = [
        build_dir / "bin" / "Release" / bin_name,
        build_dir / "bin" / bin_name,
        build_dir / bin_name,
        llama_cpp_dir / "build" / "bin" / "Release" / bin_name,
        llama_cpp_dir / "build" / "bin" / bin_name,
    ]
    for p in search_paths:
        if p.exists():
            quantize_bin = p
            break

    # 如果没找到，尝试编译
    if quantize_bin is None:
        log("未找到 llama-quantize，正在编译...")
        _build_llama_cpp(llama_cpp_dir, build_dir)

        for p in search_paths:
            if p.exists():
                quantize_bin = p
                break

    if quantize_bin is None:
        error("无法找到或编译 llama-quantize")
        error(f"请手动编译 llama.cpp: cmake -B {build_dir} && cmake --build {build_dir} --config Release")
        sys.exit(1)

    log(f"Quantizer: {quantize_bin}")
    log(f"输入: {f16_gguf} ({f16_gguf.stat().st_size / 1024 / 1024 / 1024:.1f} GB)")
    log(f"输出: {quant_gguf}")
    log(f"量化: {quant_type}")

    cmd = [
        str(quantize_bin),
        str(f16_gguf),
        str(quant_gguf),
        quant_type,
    ]
    log(f"执行: {' '.join(cmd)}")

    t0 = time.time()
    result = subprocess.run(cmd, check=False)

    if result.returncode != 0:
        error("量化失败！")
        sys.exit(1)

    elapsed = (time.time() - t0) / 60
    size_gb = quant_gguf.stat().st_size / 1024 / 1024 / 1024
    log(f"量化完成 ({elapsed:.1f} min, {size_gb:.2f} GB)")

    # 打印对比
    original_gb = f16_gguf.stat().st_size / 1024 / 1024 / 1024
    ratio = size_gb / original_gb * 100
    log(f"压缩比: {original_gb:.1f} GB → {size_gb:.1f} GB ({ratio:.0f}%)")
    return quant_gguf


def _build_llama_cpp(llama_cpp_dir: Path, build_dir: Path):
    """编译 llama.cpp"""
    build_dir.mkdir(parents=True, exist_ok=True)

    cmake_cmd = ["cmake", "-S", str(llama_cpp_dir), "-B", str(build_dir)]
    if sys.platform == "win32":
        cmake_cmd += ["-G", "Visual Studio 17 2022"]  # VS 2022

    log("Running cmake...")
    subprocess.run(cmake_cmd, check=True)

    log("Building...")
    subprocess.run(
        ["cmake", "--build", str(build_dir), "--config", "Release", "-j"],
        check=True,
    )


# ── Step 4: 生成 Ollama Modelfile ─────────────────────────
def generate_modelfile(
    gguf_path: Path,
    model_name: str,
    output_dir: Path,
):
    """生成 Ollama Modelfile"""
    section("Step 4: 生成 Ollama Modelfile")

    modelfile = output_dir / "Modelfile"

    # Gemma 的 chat template
    content = f"""# Ollama Modelfile for {model_name}
# 桥墩缺陷检测助手

FROM {gguf_path.resolve()}

# ── Chat Template (Gemma 格式) ──
TEMPLATE """<bos><start_of_turn>user
{{{{ .Prompt }}}}<end_of_turn>
<start_of_turn>model
{{{{ .Response }}}}<end_of_turn>
"""

PARAMETER stop "<start_of_turn>user"
PARAMETER stop "<end_of_turn>"

# ── 推理参数 ──
PARAMETER temperature 0.7
PARAMETER top_p 0.9
PARAMETER top_k 40
PARAMETER num_predict 2048
PARAMETER repeat_penalty 1.1

# ── System Prompt ──
SYSTEM """你是一个专业的桥梁缺陷检测助手，专门分析和识别桥梁结构中的各类缺陷。
你能回答关于桥梁检测、缺陷分类、安全评估等方面的问题。

常见的桥梁缺陷类型包括：
- 裂缝 (Crack)
- 露筋 (Exposed Rebar)
- 剥落 (Spall)
- 蜂窝麻面 (Honeycomb)
- 渗水 (Efflorescence)
- 分层 (Delamination)
"""
"""

    modelfile.write_text(content, encoding="utf-8")
    log(f"Modelfile 已生成: {modelfile}")

    # 打印 Ollama 命令
    print(f"""
{bold('部署到 Ollama:')}
  ollama create {model_name} -f {modelfile}

{bold('测试:')}
  ollama run {model_name} "桥梁裂缝属于哪类缺陷？"

{bold('查看已安装模型:')}
  ollama list
""")


# ── Main ──────────────────────────────────────────────────
def main():
    args = get_args()

    # 工作目录设为脚本所在目录
    script_dir = Path(__file__).parent.resolve()
    os.chdir(str(script_dir))

    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    model_name = args.model_name
    llama_cpp_dir = Path(args.llama_cpp_dir).resolve()
    build_dir = Path(args.build_dir) if args.build_dir else (llama_cpp_dir / "build")

    # ── 0. 准备 llama.cpp ──
    ensure_llama_cpp(llama_cpp_dir)

    # ── 1. 获取 HF 模型路径 ──
    hf_model_path = None

    if args.merged_path:
        # 用户已提供合并好的模型
        hf_model_path = Path(args.merged_path).resolve()
        if not hf_model_path.exists():
            error(f"合并模型路径不存在: {hf_model_path}")
            sys.exit(1)
        log(f"使用已有模型: {hf_model_path}")

    elif args.base_model:
        base_model = Path(args.base_model).resolve()
        if not base_model.exists():
            error(f"Base model 不存在: {base_model}")
            sys.exit(1)

        if args.lora_path:
            lora_path = Path(args.lora_path).resolve()
            if not lora_path.exists():
                error(f"LoRA adapter 不存在: {lora_path}")
                sys.exit(1)
            # Merge
            merged_output = output_dir / "hf-merged"
            hf_model_path = merge_lora(
                str(base_model), str(lora_path), merged_output,
            )
        else:
            # 没有 LoRA，直接用 base model
            log(f"未提供 --lora-path，直接使用 base model: {base_model}")
            hf_model_path = base_model
    else:
        error("必须提供 --merged-path 或 --base-model（可加 --lora-path）")
        sys.exit(1)

    # ── 2. HF → GGUF (f16) ──
    f16_gguf = output_dir / f"{model_name}-f16.gguf"
    if not f16_gguf.exists():
        convert_to_gguf(
            hf_model_path=hf_model_path,
            llama_cpp_dir=llama_cpp_dir,
            out_gguf=f16_gguf,
            outtype="f16",
        )
    else:
        log(f"f16 GGUF 已存在，跳过: {f16_gguf}")

    # ── 3. Quantize ──
    if args.skip_quantize:
        log("跳过量化 (--skip-quantize)")
        final_gguf = f16_gguf
    else:
        quant_gguf = output_dir / f"{model_name}-{args.quant}.gguf"
        if not quant_gguf.exists():
            final_gguf = quantize_gguf(
                llama_cpp_dir=llama_cpp_dir,
                build_dir=build_dir,
                f16_gguf=f16_gguf,
                quant_gguf=quant_gguf,
                quant_type=args.quant,
            )
        else:
            log(f"量化文件已存在，跳过: {quant_gguf}")
            final_gguf = quant_gguf

    # ── 4. 生成 Modelfile ──
    generate_modelfile(
        gguf_path=final_gguf,
        model_name=model_name,
        output_dir=output_dir,
    )

    # ── 完成 ──
    section("✅ 全部完成！")
    print(f"  最终 GGUF:  {final_gguf} ({final_gguf.stat().st_size / 1024**3:.1f} GB)")
    print(f"  Modelfile:  {output_dir / 'Modelfile'}")
    print(f"\n  运行以下命令部署到 Ollama:")
    print(f"  {bold(f'ollama create {model_name} -f {output_dir / \"Modelfile\"}')}")


if __name__ == "__main__":
    main()
