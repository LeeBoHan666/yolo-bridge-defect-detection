"""
纯 safetensors 合并 LoRA + 导出 GGUF（不加载模型，零 GPU 依赖）
==============================================================
原理：直接读 safetensors 文件，对 LoRA 层做 W = W_base + lora_B @ lora_A * (alpha/r)
不需要 transformers / peft 加载模型，内存占用 ~2-3 GB，速度 = 磁盘 I/O

用法：
  python merge_gguf.py
  python merge_gguf.py --lora ./gemma4-bridge-lora --base /root/.../gemma --out /output
"""

import os
import sys
import json
import struct
import argparse
import shutil
import torch
from safetensors.torch import load_file, save_file

# ============================================================
# 参数
# ============================================================
parser = argparse.ArgumentParser(description="Pure safetensors LoRA merge + GGUF export")
parser.add_argument("--lora", default="./gemma4-bridge-lora", help="LoRA adapter 目录")
parser.add_argument("--base", default="/root/rivermind-data/lost+found/gemma", help="基座模型目录")
parser.add_argument("--out", default="/gemma4-bridge-export", help="输出目录")
parser.add_argument("--quant", default="q4_k_m", choices=["q4_k_m","q4_0","q5_k_m","q8_0","f16"])
args = parser.parse_args()

LORA_DIR = args.lora
BASE_DIR = args.base
OUT_DIR = args.out
MERGED_DIR = os.path.join(OUT_DIR, "merged")
GGUF_DIR = os.path.join(OUT_DIR, "gguf")
QUANT = args.quant

os.makedirs(MERGED_DIR, exist_ok=True)
os.makedirs(GGUF_DIR, exist_ok=True)

print("=" * 60)
print("  纯 safetensors LoRA 合并 + GGUF 导出")
print("=" * 60)
print(f"  基座:    {BASE_DIR}")
print(f"  LoRA:    {LORA_DIR}")
print(f"  输出:    {OUT_DIR}")
print(f"  量化:    {QUANT}")

# ============================================================
# Step 1: 读取 LoRA 配置
# ============================================================
adapter_config_path = os.path.join(LORA_DIR, "adapter_config.json")
adapter_weights_path = os.path.join(LORA_DIR, "adapter_model.safetensors")

with open(adapter_config_path, "r") as f:
    lora_cfg = json.load(f)

lora_r = lora_cfg["r"]
lora_alpha = lora_cfg["lora_alpha"]
scaling = lora_alpha / lora_r  # 16/16 = 1.0
target_modules = lora_cfg["target_modules"]
print(f"\n  LoRA config: r={lora_r}, alpha={lora_alpha}, scaling={scaling}")
print(f"  target_modules: {target_modules}")

# ============================================================
# Step 2: 加载 LoRA 权重 → 构建合并映射
# ============================================================
print(f"\n[1/5] 加载 LoRA adapter 权重...")
lora_weights = load_file(adapter_weights_path)
print(f"  LoRA 张量数: {len(lora_weights)}")

# 解析 LoRA key，构建 {base_key: (lora_A_tensor, lora_B_tensor)} 映射
# PEFT adapter key 格式: base_model.model.{path}.lora_A.default.weight
# 对应的 base model key: {path}.weight
merge_map = {}  # base_key → (lora_A, lora_B)

for key, tensor in lora_weights.items():
    # 检查是否是 lora_A
    if ".lora_A." in key:
        # 提取 base key
        # "base_model.model.model.layers.0.self_attn.q_proj.lora_A.default.weight"
        # → "model.layers.0.self_attn.q_proj.weight"
        base_key = key
        # 去掉 PEFT 前缀
        for prefix in ["base_model.model.", "base_model."]:
            if base_key.startswith(prefix):
                base_key = base_key[len(prefix):]
                break
        # 去掉 lora_A.default.weight 后缀
        base_key = base_key.replace(".lora_A.default.weight", ".weight")
        base_key = base_key.replace(".lora_A.weight", ".weight")

        # 对应的 lora_B key
        lora_b_key = key.replace("lora_A", "lora_B")

        if lora_b_key in lora_weights:
            merge_map[base_key] = (tensor, lora_weights[lora_b_key])

print(f"  待合并层数: {len(merge_map)}")
# 展示几个 key
for i, k in enumerate(list(merge_map.keys())[:3]):
    lora_a, lora_b = merge_map[k]
    print(f"    {k}: lora_A{lora_a.shape} lora_B{lora_b.shape}")

# ============================================================
# Step 3: 检查基座模型文件结构
# ============================================================
print(f"\n[2/5] 检查基座模型文件...")
base_sf_path = os.path.join(BASE_DIR, "model.safetensors")
base_index_path = os.path.join(BASE_DIR, "model.safetensors.index.json")

if os.path.exists(base_index_path):
    # 分片模型
    with open(base_index_path, "r") as f:
        index = json.load(f)
    weight_map = index["weight_map"]
    shard_files = sorted(set(weight_map.values()))
    print(f"  分片模型: {len(shard_files)} 个文件")
else:
    # 单文件模型
    weight_map = None
    shard_files = ["model.safetensors"]
    print(f"  单文件模型")

# ============================================================
# Step 4: 逐个 shard 合并并写入
# ============================================================
print(f"\n[3/5] 合并 LoRA 权重（纯张量运算）...")

all_keys = []
merged_count = 0

for shard_name in shard_files:
    shard_path = os.path.join(BASE_DIR, shard_name)
    print(f"  处理 {shard_name}...")
    shard = load_file(shard_path)
    new_shard = {}

    for key, tensor in shard.items():
        if key in merge_map:
            lora_a, lora_b = merge_map[key]
            # 确保 dtype 匹配
            orig_dtype = tensor.dtype
            lora_a = lora_a.to(dtype=torch.float32)
            lora_b = lora_b.to(dtype=torch.float32)
            tensor_f32 = tensor.to(dtype=torch.float32)

            # W_merged = W_base + lora_B @ lora_A * scaling
            delta = (lora_b @ lora_a) * scaling
            merged = tensor_f32 + delta
            new_shard[key] = merged.to(dtype=orig_dtype)
            merged_count += 1
        else:
            new_shard[key] = tensor

    # 写入合并后的 shard
    out_shard_path = os.path.join(MERGED_DIR, shard_name)
    save_file(new_shard, out_shard_path)
    all_keys.extend(new_shard.keys())
    print(f"    合并 {shard_name}: {len(new_shard)} tensors → {out_shard_path}")

print(f"\n  ✅ 共合并 {merged_count} 个 LoRA 层，总计 {len(all_keys)} tensors")

# ============================================================
# Step 5: 复制配置和 tokenizer 文件
# ============================================================
print(f"\n[4/5] 复制配置/tokenizer 文件...")
for fname in os.listdir(BASE_DIR):
    src = os.path.join(BASE_DIR, fname)
    dst = os.path.join(MERGED_DIR, fname)
    if os.path.isfile(src) and not fname.startswith("model.safetensors"):
        shutil.copy2(src, dst)
        print(f"  ✓ {fname}")

# 如果有 shard index，也更新
if os.path.exists(base_index_path):
    shutil.copy2(base_index_path, os.path.join(MERGED_DIR, "model.safetensors.index.json"))

print(f"  ✅ 合并模型已保存到 {MERGED_DIR}")

# ============================================================
# Step 6: 导出 GGUF
# ============================================================
GOT_GGUF = False  # 标记是否成功导出

def try_gguf_export():
    global GOT_GGUF, gguf_final
    import subprocess

    print(f"\n[5/5] 导出 GGUF...")

    # 克隆 llama.cpp（尝试多个源）
    LLAMA_DIR = os.path.abspath("./llama.cpp")
    if not os.path.exists(LLAMA_DIR):
        print("  克隆 llama.cpp...")
        for url in [
            "https://github.com/ggerganov/llama.cpp.git",
            "https://ghproxy.com/https://github.com/ggerganov/llama.cpp.git",
        ]:
            try:
                subprocess.run(["git", "clone", "--depth", "1", url, LLAMA_DIR],
                               check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                break
            except Exception:
                continue

    convert_py = os.path.join(LLAMA_DIR, "convert_hf_to_gguf.py")
    if not os.path.exists(convert_py):
        print("  ⚠ 无法获取 llama.cpp（服务器无外网），跳过 GGUF 导出")
        return

    # 安装依赖
    req = os.path.join(LLAMA_DIR, "requirements", "requirements-convert_hf_to_gguf.txt")
    if os.path.exists(req):
        subprocess.run([sys.executable, "-m", "pip", "install", "-r", req, "-q",
                        "--upgrade-strategy", "only-if-needed"], check=False)

    gguf_f16 = os.path.join(GGUF_DIR, "gemma4-bridge-f16.gguf")
    gguf_final_local = os.path.join(GGUF_DIR, f"gemma4-bridge-{QUANT}.gguf")

    print(f"  转换 HF → GGUF f16...")
    subprocess.run([sys.executable, convert_py, MERGED_DIR,
                    "--outtype", "f16", "--outfile", gguf_f16], check=True)
    print(f"  ✅ f16: {os.path.getsize(gguf_f16)/1024**3:.1f} GB")

    if QUANT == "f16":
        os.rename(gguf_f16, gguf_final_local)
        GOT_GGUF = True
        return gguf_final_local

    # 找/编译 llama-quantize
    quantize_bin = None
    for name in ["llama-quantize", "quantize"]:
        for root, dirs, files in os.walk(LLAMA_DIR):
            if name in files:
                quantize_bin = os.path.join(root, name)
                break
        if quantize_bin:
            break

    if not quantize_bin:
        print("  编译 llama-quantize...")
        build_dir = os.path.join(LLAMA_DIR, "build")
        os.makedirs(build_dir, exist_ok=True)
        try:
            subprocess.run(["cmake", "-B", build_dir, "-S", LLAMA_DIR,
                            "-DLLAMA_CURL=OFF"], check=True, timeout=120,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["cmake", "--build", build_dir, "--target",
                            "llama-quantize", "-j4"], check=True, timeout=300,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for name in ["llama-quantize", "quantize"]:
                for root, dirs, files in os.walk(build_dir):
                    if name in files:
                        quantize_bin = os.path.join(root, name)
                        break
        except Exception:
            pass

    if quantize_bin:
        print(f"  量化 {QUANT}...")
        for qtype in [QUANT.upper(), QUANT]:
            try:
                subprocess.run([quantize_bin, gguf_f16, gguf_final_local, qtype],
                               check=True, timeout=1800)
                os.remove(gguf_f16)
                GOT_GGUF = True
                print(f"  ✅ {QUANT}: {os.path.getsize(gguf_final_local)/1024**3:.2f} GB")
                return gguf_final_local
            except Exception:
                continue

    # 保底：用 f16
    print(f"  ⚠ 量化失败，保留 f16")
    shutil.copy2(gguf_f16, gguf_final_local)
    os.remove(gguf_f16)
    GOT_GGUF = True
    return gguf_final_local


gguf_final = try_gguf_export()

# ============================================================
# Modelfile
# ============================================================
modelfile_path = os.path.join(OUT_DIR, "Modelfile")

if GOT_GGUF and gguf_final and os.path.exists(gguf_final):
    gguf_path = os.path.abspath(gguf_final)
    size_gb = os.path.getsize(gguf_path) / 1024**3
    modelfile_content = f'''FROM {gguf_path}
TEMPLATE """<|im_start|>system
{{{{ .System }}}}<|im_end|>
<|im_start|>user
{{{{ .Prompt }}}}<|im_end|>
<|im_start|>assistant
"""
PARAMETER temperature 0.3
PARAMETER top_p 0.8
PARAMETER top_k 40
PARAMETER repeat_penalty 1.05
PARAMETER stop <|im_end|>
PARAMETER stop "<|im_start|>user"
PARAMETER stop "<|im_start|>system"
'''
    with open(modelfile_path, "w") as f:
        f.write(modelfile_content)

    print(f"""
{'='*55}
  ✅ 全部完成!

  合并模型: {MERGED_DIR}
  GGUF:     {gguf_path} ({size_gb:.2f} GB)
  Modelfile:{modelfile_path}

  服务器部署:
    ollama create gemma4-bridge -f {modelfile_path}
    ollama run gemma4-bridge "什么是裂缝"

  下载到本地 (RTX 5070 8GB):
    scp user@server:{gguf_path} .
    scp user@server:{modelfile_path} .
    ollama create gemma4-bridge -f Modelfile
{'='*55}
""")
else:
    # GGUF 导出失败，告诉用户本地转换
    print(f"""
{'='*55}
  ✅ 合并完成 (纯 safetensors), ⚠ GGUF 导出跳过

  合并模型: {MERGED_DIR}

  本地转换 GGUF (Windows, Python 已装好 llama_cpp_python):
    1. 从服务器下载合并模型到本地
    2. git clone https://github.com/ggerganov/llama.cpp.git
    3. python llama.cpp/convert_hf_to_gguf.py {MERGED_DIR} --outtype f16 --outfile gemma4-bridge-f16.gguf
    4. 编译 llama-quantize 或用 pip install llama-cpp-python
    5. 量化: llama-quantize gemma4-bridge-f16.gguf gemma4-bridge-q4_k_m.gguf Q4_K_M
{'='*55}
""")
