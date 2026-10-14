"""
去量化合并脚本 — bf16 加载基座 → merge LoRA → 保存干净 safetensors
=====================================================================
解决的问题：
  - 4-bit 加载基座模型 → merge_and_unload 后，safetensors 中残留 2512 个
    量化元数据张量（absmax / quant_map），llama.cpp 无法识别
  - 本脚本用 bf16 加载基座（无量化），merge 后保存的 safetensors 完全干净：
    2130 个张量，0 个 absmax，llama.cpp convert/quantize 正常

原理：
  基座以 bf16 全精度加载到 CPU/GPU → 不经过 bitsandbytes 量化层
  → merge_and_unload 只是纯 bf16 矩阵加法 → 保存即干净权重

用法：
  # 仅合并（最常用）
  python dequant_merge.py

  # 指定路径
  python dequant_merge.py \
    --base D:\Users\a1563\Desktop\人工智能大作业\gemma \
    --lora ./gemma4-bridge-lora \
    --out ./gemma4-bridge-merged

  # 合并后继续转 GGUF（需要 llama.cpp）
  python dequant_merge.py --export-gguf

  # CPU 模式（默认，~25GB RAM）
  python dequant_merge.py --cpu

  # GPU 模式（bf16 加载，~14GB 显存）
  python dequant_merge.py --gpu

环境要求：
  pip install torch transformers peft safetensors
"""

import os
import sys
import argparse
import time
import torch
import shutil


# ============================================================
# 参数
# ============================================================
parser = argparse.ArgumentParser(
    description="Dequant Merge: bf16 加载基座 → clean safetensors",
)
parser.add_argument(
    "--base",
    default=r"D:\Users\a1563\Desktop\人工智能大作业\gemma",
    help="基座模型路径 (HuggingFace 格式)",
)
parser.add_argument(
    "--lora",
    default="./gemma4-bridge-lora",
    help="LoRA adapter 路径",
)
parser.add_argument(
    "--out",
    default="./gemma4-bridge-merged",
    help="输出目录",
)
parser.add_argument(
    "--cpu", action="store_true", default=True,
    help="CPU 上 merge (默认，需 ~25GB RAM，慢但稳)",
)
parser.add_argument(
    "--gpu", action="store_true",
    help="GPU 上 merge (bf16，需 ~14GB 显存，快)",
)
parser.add_argument(
    "--export-gguf", action="store_true",
    help="合并后继续导出 GGUF + 量化",
)
parser.add_argument(
    "--quant", default="q4_k_m",
    choices=["q4_k_m", "q4_0", "q5_k_m", "q8_0", "f16"],
    help="GGUF 量化类型 (默认 q4_k_m)",
)
parser.add_argument(
    "--skip-copy-configs", action="store_true",
    help="跳过复制 config/tokenizer 文件（仅输出 safetensors）",
)
args = parser.parse_args()

BASE = args.base
LORA = args.lora
OUT = args.out
DEVICE = "cpu" if args.cpu or not args.gpu else "cuda"

os.makedirs(OUT, exist_ok=True)

print("=" * 60)
print("  Dequant Merge — bf16 加载 → 干净 safetensors")
print("=" * 60)
print(f"  基座:      {BASE}")
print(f"  LoRA:      {LORA}")
print(f"  输出:      {OUT}")
print(f"  设备:      {DEVICE}")
print(f"  量化导出:  {'是' if args.export_gguf else '否（仅 merge）'}")
print("=" * 60)


# ============================================================
# Step 1: 加载基座 (bf16，无量化)
# ============================================================
print("\n[Step 1/3] 加载基座模型 (bf16, 无量化)...")

from transformers import AutoTokenizer, AutoModelForCausalLM

model_kwargs = dict(
    trust_remote_code=True,
    local_files_only=True,
    torch_dtype=torch.bfloat16,
)

if DEVICE == "cpu":
    model_kwargs["device_map"] = "cpu"
    print("  ⚠ CPU 模式，需要 ~25 GB 系统 RAM")
else:
    model_kwargs["device_map"] = "auto"
    alloc_before = torch.cuda.memory_allocated() / 1024**3

t0 = time.time()
model = AutoModelForCausalLM.from_pretrained(BASE, **model_kwargs)
print(f"  基座加载完成 ({time.time() - t0:.0f}s)")

if DEVICE == "cuda":
    alloc = torch.cuda.memory_allocated() / 1024**3
    print(f"  显存占用: {alloc:.1f} GB")

# Tokenizer
tokenizer = AutoTokenizer.from_pretrained(
    BASE, local_files_only=True, trust_remote_code=True,
)
if tokenizer.pad_token is None:
    tokenizer.pad_token = tokenizer.eos_token
print(f"  Tokenizer: pad_token={tokenizer.pad_token}")


# ============================================================
# Step 2: 展开 ClippableLinear + 加载 LoRA + Merge
# ============================================================
print("\n[Step 2/3] 展开 ClippableLinear → 加载 LoRA → merge_and_unload...")

# 展开 Gemma4ClippableLinear → nn.Linear（PEFT 兼容）
import torch.nn as nn
replaced = 0
for name, module in list(model.named_modules()):
    if "Clippable" not in type(module).__name__:
        continue
    inner = None
    for child in module.children():
        if isinstance(child, nn.Linear):
            inner = child
            break
    if inner is not None:
        parent = model.get_submodule(".".join(name.split(".")[:-1])) if "." in name else model
        leaf = name.split(".")[-1]
        setattr(parent, leaf, inner)
        replaced += 1
if replaced:
    print(f"  🔧 展开 {replaced} 个 ClippableLinear → nn.Linear")

# 加载 LoRA
from peft import PeftModel
model = PeftModel.from_pretrained(model, LORA)
trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
print(f"  LoRA 可训练参数: {trainable:,}")

# Merge
t0 = time.time()
if DEVICE == "cpu":
    print("  merge_and_unload 中 (CPU，约 5-15 分钟)...")
else:
    print("  merge_and_unload 中 (GPU，约 1-3 分钟)...")

merged = model.merge_and_unload()
print(f"  ✅ Merge 完成 ({time.time() - t0:.0f}s)")

# 验证：确认没有量化残留
from collections import Counter
tensor_name_patterns = Counter()
for name, param in merged.named_parameters():
    # 提取张量名关键词
    if "absmax" in name.lower():
        tensor_name_patterns["absmax"] += 1
    if "quant_map" in name.lower():
        tensor_name_patterns["quant_map"] += 1

if tensor_name_patterns.get("absmax", 0) > 0:
    print(f"  ⚠ 警告: 检测到 {tensor_name_patterns['absmax']} 个 absmax 张量（不应出现）")
else:
    print(f"  ✅ 无量化残留 (absmax=0, quant_map=0)")

total_params = sum(p.numel() for p in merged.parameters())
print(f"  总参数: {total_params:,}")


# ============================================================
# Step 3: 保存干净 safetensors
# ============================================================
print(f"\n[Step 3/3] 保存合并模型 → {OUT}...")

t0 = time.time()
merged.save_pretrained(OUT, safe_serialization=True)
tokenizer.save_pretrained(OUT)
print(f"  保存完成 ({time.time() - t0:.0f}s)")

# 统计
shard_files = list(
    f for f in os.listdir(OUT) if f.startswith("model") and f.endswith(".safetensors")
)
if shard_files:
    from safetensors.torch import load_file
    total_tensors = 0
    total_size = 0
    for sf in shard_files:
        tensors = load_file(os.path.join(OUT, sf))
        total_tensors += len(tensors)
        total_size += os.path.getsize(os.path.join(OUT, sf))
    print(f"  Safetensors 文件: {len(shard_files)} 个")
    print(f"  总张量数: {total_tensors}")
    print(f"  总大小: {total_size / 1024**3:.1f} GB")

# 复制配置文件
if not args.skip_copy_configs:
    print("  复制配置文件...")
    for fname in os.listdir(BASE):
        src = os.path.join(BASE, fname)
        dst = os.path.join(OUT, fname)
        if os.path.isfile(src) and not fname.startswith("model.safetensors"):
            if not os.path.exists(dst):
                shutil.copy2(src, dst)
    # 确保关键文件存在
    for critical in ["config.json", "tokenizer.json", "tokenizer.model",
                     "tokenizer_config.json", "preprocessor_config.json"]:
        if not os.path.exists(os.path.join(OUT, critical)):
            src = os.path.join(BASE, critical)
            if os.path.exists(src):
                shutil.copy2(src, os.path.join(OUT, critical))
                print(f"    ✓ 补充: {critical}")

print(f"\n{'='*60}")
print(f"  ✅ 去量化合并完成！")
print(f"  输出目录: {os.path.abspath(OUT)}")
print(f"{'='*60}")

# 清理
del model, merged
if DEVICE == "cuda":
    torch.cuda.empty_cache()


# ============================================================
# 可选: 导出 GGUF
# ============================================================
if not args.export_gguf:
    print(f"""
  下一步 — 手动转换 GGUF:
    git clone https://github.com/ggerganov/llama.cpp.git
    pip install -r llama.cpp/requirements/requirements-convert_hf_to_gguf.txt
    python llama.cpp/convert_hf_to_gguf.py {OUT} --outtype f16 --outfile gemma4-bridge-f16.gguf
    llama.cpp/build/bin/llama-quantize gemma4-bridge-f16.gguf gemma4-bridge-q4_k_m.gguf Q4_K_M
    ollama create gemma4-bridge -f Modelfile
""")
    sys.exit(0)


# ============================================================
# GGUF 导出
# ============================================================
import subprocess

GGUF_DIR = os.path.join(os.path.dirname(OUT), "gguf-output")
os.makedirs(GGUF_DIR, exist_ok=True)

GGUF_F16 = os.path.join(GGUF_DIR, "gemma4-bridge-f16.gguf")
GGUF_FINAL = os.path.join(GGUF_DIR, f"gemma4-bridge-{args.quant}.gguf")

# 检查/克隆 llama.cpp
LLAMA_DIR = os.path.abspath("./llama.cpp")
if not os.path.exists(LLAMA_DIR):
    print("\n克隆 llama.cpp...")
    subprocess.run(
        ["git", "clone", "--depth", "1",
         "https://github.com/ggerganov/llama.cpp.git", LLAMA_DIR],
        check=True,
    )

CONVERT_PY = os.path.join(LLAMA_DIR, "convert_hf_to_gguf.py")
if not os.path.exists(CONVERT_PY):
    print(f"❌ 未找到转换脚本: {CONVERT_PY}")
    sys.exit(1)

# 安装依赖
req = os.path.join(LLAMA_DIR, "requirements", "requirements-convert_hf_to_gguf.txt")
if os.path.exists(req):
    subprocess.run(
        [sys.executable, "-m", "pip", "install", "-r", req, "-q",
         "--upgrade-strategy", "only-if-needed"],
        check=False,
    )

# HF → GGUF f16
print(f"\n[GGUF 1/2] 转换 HF → GGUF f16...")
subprocess.run([
    sys.executable, CONVERT_PY, OUT,
    "--outtype", "f16",
    "--outfile", GGUF_F16,
], check=True)
print(f"  ✅ f16: {os.path.getsize(GGUF_F16)/1024**3:.1f} GB")

# 量化
if args.quant != "f16":
    print(f"\n[GGUF 2/2] 量化 f16 → {args.quant}...")

    # 找 quantize 二进制
    quantize_bin = None
    for name in ["llama-quantize", "llama-quantize.exe", "quantize", "quantize.exe"]:
        for root, dirs, files in os.walk(LLAMA_DIR):
            if name in files:
                quantize_bin = os.path.join(root, name)
                break
        if quantize_bin:
            break

    if quantize_bin:
        for qtype in [args.quant.upper(), args.quant]:
            try:
                subprocess.run(
                    [quantize_bin, GGUF_F16, GGUF_FINAL, qtype],
                    check=True, timeout=1800,
                )
                break
            except Exception:
                continue
        os.remove(GGUF_F16)
        print(f"  ✅ {args.quant}: {os.path.getsize(GGUF_FINAL)/1024**3:.2f} GB")
    else:
        print(f"  ⚠ 未找到 llama-quantize，保留 f16")
        shutil.copy2(GGUF_F16, GGUF_FINAL)
        os.remove(GGUF_F16)

# Modelfile
modelfile = os.path.join(GGUF_DIR, "Modelfile")
with open(modelfile, "w", encoding="utf-8") as f:
    f.write(f"""FROM {os.path.abspath(GGUF_FINAL)}
TEMPLATE \"\"\"<|im_start|>system
{{{{ .System }}}}<|im_end|>
<|im_start|>user
{{{{ .Prompt }}}}<|im_end|>
<|im_start|>assistant
\"\"\"
PARAMETER temperature 0.3
PARAMETER top_p 0.8
PARAMETER top_k 40
PARAMETER repeat_penalty 1.05
PARAMETER stop <|im_end|>
PARAMETER stop "<|im_start|>user"
PARAMETER stop "<|im_start|>system"
""")

print(f"""
{'='*60}
  ✅ 全部完成！
  GGUF:     {GGUF_FINAL}
  Modelfile:{modelfile}

  部署:
    ollama create gemma4-bridge -f {modelfile}
    ollama run gemma4-bridge "什么是裂缝"
{'='*60}
""")
