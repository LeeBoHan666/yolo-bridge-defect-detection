"""
Merge LoRA → 导出 GGUF → Modelfile（服务器端运行）
=====================================================
关键改动：bf16 加载基座（非 4-bit），merge 后权重为纯 bf16，
llama.cpp 的 convert 和 quantize 才能正确处理。

用法：
  python export_gguf.py

可选参数：
  --lora-path    LoRA adapter 路径（默认 ./gemma4-bridge-lora）
  --base-model   基座模型路径（默认 /root/rivermind-data/lost+found/gemma）
  --output-dir   输出目录（默认 ./gemma4-bridge-export）
  --quant        量化类型（默认 q4_k_m）
  --skip-merge   跳过 merge，直接转 GGUF
"""

import os
import sys
import argparse
import torch
import shutil

# ============================================================
# 0. 参数
# ============================================================
parser = argparse.ArgumentParser(description="Merge LoRA + Export GGUF")
parser.add_argument("--lora-path", default="./gemma4-bridge-lora")
parser.add_argument("--base-model", default="/root/rivermind-data/lost+found/gemma")
parser.add_argument("--output-dir", default="/gemma4-bridge-export")
parser.add_argument("--quant", default="q4_k_m",
                    choices=["q4_k_m", "q4_0", "q5_k_m", "q8_0", "f16"])
parser.add_argument("--skip-merge", action="store_true")
parser.add_argument("--cpu", action="store_true", help="CPU 上 merge（避免 GPU OOM，需要 ~25GB RAM）")
args = parser.parse_args()

LORA_PATH = args.lora_path
BASE_MODEL = args.base_model
OUTPUT_DIR = args.output_dir
MERGED_DIR = os.path.join(OUTPUT_DIR, "merged")
GGUF_DIR = os.path.join(OUTPUT_DIR, "gguf")
QUANT = args.quant
GGUF_F16 = os.path.join(GGUF_DIR, "gemma4-bridge-f16.gguf")
GGUF_FINAL = os.path.join(GGUF_DIR, f"gemma4-bridge-{QUANT}.gguf")

os.makedirs(OUTPUT_DIR, exist_ok=True)

print("=" * 60)
print("  Gemma 4 E4B → GGUF 导出")
print("=" * 60)
print(f"  基础模型:  {BASE_MODEL}")
print(f"  LoRA路径:  {LORA_PATH}")
print(f"  输出目录:  {OUTPUT_DIR}")
print(f"  量化类型:  {QUANT}")
print("=" * 60)


# ============================================================
# 1. 检查 transformers 版本
# ============================================================
def check_transformers():
    """确保 transformers 支持 gemma4"""
    from transformers.models.auto.configuration_auto import CONFIG_MAPPING
    if "gemma4" not in CONFIG_MAPPING:
        print("\n❌ transformers 不支持 gemma4，正在升级...")
        import subprocess
        subprocess.run(
            [sys.executable, "-m", "pip", "install", "--upgrade", "transformers", "-q"],
            check=True,
        )
        print("  ⚠ 已升级，请重新运行脚本: python export_gguf.py")
        sys.exit(1)
    print("✅ transformers 支持 gemma4 ✓")


# ============================================================
# 2. 展开 Gemma4ClippableLinear
# ============================================================
def unwrap_gemma4_clippable(model):
    """展开 ClippableLinear → 内部 nn.Linear（PEFT 兼容）"""
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
        print(f"🔧 展开 {replaced} 个 ClippableLinear → nn.Linear")
    return model


# ============================================================
# 3. Merge LoRA（4-bit 加载，8.7GB → 峰值 ~16GB，24GB 卡安全）
# ============================================================
def merge_lora():
    from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig
    from peft import PeftModel, prepare_model_for_kbit_training

    use_cpu = args.cpu

    if use_cpu:
        # CPU 模式：bf16 加载到内存（~12GB），merge 峰值 ~25GB 系统 RAM
        print("\n[Step 1/3] 加载基础模型 (CPU, bf16)...")
        print("  ⚠ 需要 ~25 GB 系统 RAM")
        model = AutoModelForCausalLM.from_pretrained(
            BASE_MODEL,
            device_map="cpu",
            trust_remote_code=True,
            local_files_only=True,
            torch_dtype=torch.bfloat16,
        )
        tokenizer = AutoTokenizer.from_pretrained(
            BASE_MODEL, local_files_only=True, trust_remote_code=True,
        )
        if tokenizer.pad_token is None:
            tokenizer.pad_token = tokenizer.eos_token

        # CPU 上不需要 k-bit 训练准备，但需要展开 ClippableLinear
        model = unwrap_gemma4_clippable(model)

        print("\n[Step 2/3] 加载 LoRA + CPU merge...")
        model = PeftModel.from_pretrained(model, LORA_PATH)
        print("  merge_and_unload 中，CPU 较慢请等待（5-15 分钟）...")
        merged = model.merge_and_unload()
        print("  Merge 完成")

        os.makedirs(MERGED_DIR, exist_ok=True)
        print(f"\n[Step 3/3] 保存 → {MERGED_DIR}")
        merged.save_pretrained(MERGED_DIR, safe_serialization=True)
        tokenizer.save_pretrained(MERGED_DIR)
        print(f"  ✅ 合并模型已保存")
        del model, merged
        return MERGED_DIR

    # GPU 模式：4-bit 加载，8.7GB → 峰值 ~16GB
    print("\n[Step 1/3] 加载基础模型 (4-bit 量化)...")

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )

    model = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL,
        quantization_config=bnb_config,
        device_map="auto",
        trust_remote_code=True,
        local_files_only=True,
        torch_dtype=torch.bfloat16,
    )
    alloc = torch.cuda.memory_allocated() / 1024**3
    print(f"  基础模型加载完成，显存: {alloc:.1f} GB")

    tokenizer = AutoTokenizer.from_pretrained(
        BASE_MODEL, local_files_only=True, trust_remote_code=True,
    )
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    # 准备 k-bit + 展开 ClippableLinear
    model = prepare_model_for_kbit_training(model)
    model = unwrap_gemma4_clippable(model)

    print("\n[Step 2/3] 加载 LoRA adapter + Merge...")
    model = PeftModel.from_pretrained(model, LORA_PATH)
    print("  正在 merge_and_unload（4-bit → bf16 解量化），约 1-3 分钟...")
    merged = model.merge_and_unload()

    alloc = torch.cuda.memory_allocated() / 1024**3
    print(f"  Merge 完成，显存: {alloc:.1f} GB")

    os.makedirs(MERGED_DIR, exist_ok=True)
    print(f"\n[Step 3/3] 保存合并模型 → {MERGED_DIR}")
    merged.save_pretrained(MERGED_DIR, safe_serialization=True)
    tokenizer.save_pretrained(MERGED_DIR)

    print(f"  ✅ 合并模型已保存")
    del model, merged
    torch.cuda.empty_cache()
    return MERGED_DIR


# ============================================================
# 4. GGUF 导出
# ============================================================
def ensure_llama_cpp():
    """克隆 llama.cpp 并编译 llama-quantize"""
    import subprocess

    clone_dir = os.path.abspath("./llama.cpp")

    if not os.path.exists(clone_dir):
        print("  正在克隆 llama.cpp...")
        subprocess.run(
            ["git", "clone", "--depth", "1",
             "https://github.com/ggerganov/llama.cpp.git", clone_dir],
            check=True,
        )
        # 安装依赖（不降级已有包）
        req_file = os.path.join(clone_dir, "requirements",
                                "requirements-convert_hf_to_gguf.txt")
        if os.path.exists(req_file):
            print("  安装转换依赖...")
            subprocess.run(
                [sys.executable, "-m", "pip", "install", "-r", req_file, "-q",
                 "--upgrade-strategy", "only-if-needed"],
                check=False,
            )

    convert_script = os.path.join(clone_dir, "convert_hf_to_gguf.py")
    if not os.path.exists(convert_script):
        raise FileNotFoundError(f"❌ 未找到: {convert_script}")

    quantize_bin = find_or_build_quantize(clone_dir)
    return convert_script, quantize_bin


def find_or_build_quantize(clone_dir):
    """查找或编译 llama-quantize"""
    import subprocess

    clone_dir = os.path.abspath(clone_dir)

    # 先找已编译的
    for name in ["llama-quantize", "quantize"]:
        for root, dirs, files in os.walk(clone_dir):
            if name in files:
                path = os.path.join(root, name)
                os.chmod(path, 0o755)
                print(f"  找到: {path}")
                return path

    # 尝试编译
    print("  未找到 llama-quantize，尝试 cmake 编译...")
    build_dir = os.path.join(clone_dir, "build")
    os.makedirs(build_dir, exist_ok=True)

    try:
        subprocess.run(
            ["cmake", "-B", build_dir, "-S", clone_dir, "-DLLAMA_CURL=OFF"],
            check=True, timeout=120,
        )
        subprocess.run(
            ["cmake", "--build", build_dir, "--target", "llama-quantize", "-j4"],
            check=True, timeout=300,
        )
        # 查找编译产物
        for name in ["llama-quantize", "quantize"]:
            for root, dirs, files in os.walk(build_dir):
                if name in files:
                    path = os.path.join(root, name)
                    os.chmod(path, 0o755)
                    print(f"  ✅ 编译成功: {path}")
                    return path
    except Exception as e:
        print(f"  ⚠ 编译失败: {e}")

    # Fallback: Python API
    try:
        import llama_cpp
        print("  将使用 llama_cpp_python API 量化")
        return "__python_api__"
    except ImportError:
        pass

    print("  ⚠ 无法获取量化工具，只保留 f16")
    return None


def export_gguf(merged_dir):
    import subprocess
    os.makedirs(GGUF_DIR, exist_ok=True)

    convert_script, quantize_bin = ensure_llama_cpp()

    # Step 1: HF → GGUF f16
    print("\n  [1/2] 转换 HF → GGUF f16...")
    cmd = [
        sys.executable, convert_script,
        merged_dir,
        "--outtype", "f16",
        "--outfile", GGUF_F16,
    ]
    print(f"  {' '.join(cmd)}")
    result = subprocess.run(cmd)
    if result.returncode != 0:
        print(f"❌ 转换失败 (exit: {result.returncode})")
        sys.exit(1)

    size_gb = os.path.getsize(GGUF_F16) / 1024**3
    print(f"  ✅ f16 GGUF: {size_gb:.1f} GB")

    if QUANT == "f16":
        os.rename(GGUF_F16, GGUF_FINAL)
        return

    # Step 2: 量化
    print(f"\n  [2/2] 量化 f16 → {QUANT}...")
    success = False

    if quantize_bin and quantize_bin != "__python_api__":
        for qtype in [QUANT.upper(), QUANT]:
            try:
                subprocess.run(
                    [quantize_bin, GGUF_F16, GGUF_FINAL, qtype],
                    check=True, timeout=1800,
                )
                success = True
                break
            except Exception:
                continue

    if not success and quantize_bin == "__python_api__":
        try:
            import llama_cpp.llama_cpp as llama_c
            ftype = llama_c.llama_ftype_from_str(QUANT.upper().encode())
            rc = llama_c.llama_model_quantize(
                GGUF_F16.encode(), GGUF_FINAL.encode(), ftype, 4,
            )
            success = (rc == 0)
        except Exception as e:
            print(f"  Python 量化失败: {e}")

    if success:
        size_gb = os.path.getsize(GGUF_FINAL) / 1024**3
        print(f"  ✅ 量化完成: {size_gb:.2f} GB")
        os.remove(GGUF_F16)
    else:
        print(f"  ⚠ 量化失败，保留 f16")
        shutil.copy2(GGUF_F16, GGUF_FINAL)
        os.remove(GGUF_F16)
        print(f"  手动量化: ./llama.cpp/build/bin/llama-quantize {GGUF_F16} {GGUF_FINAL} Q4_K_M")


# ============================================================
# 5. Modelfile
# ============================================================
def create_modelfile():
    print(f"\n[Modelfile] 创建...")
    gguf_abs = os.path.abspath(GGUF_FINAL)

    modelfile = f'''FROM {gguf_abs}
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
    mf_path = os.path.join(OUTPUT_DIR, "Modelfile")
    with open(mf_path, "w", encoding="utf-8") as f:
        f.write(modelfile)
    print(f"  ✅ {mf_path}")

    print(f"""
{'='*55}
  服务器部署:
    cd {os.path.abspath(OUTPUT_DIR)}
    ollama create gemma4-bridge -f Modelfile
    ollama run gemma4-bridge "什么是裂缝"

  下载到本地:
    scp user@server:{gguf_abs} .
    scp user@server:{os.path.abspath(mf_path)} .

  本地部署 (RTX 5070 8GB, GGUF ~2.5GB → 推理 ~3.5GB):
    ollama create gemma4-bridge -f Modelfile
    ollama run gemma4-bridge "什么是裂缝"
{'='*55}
""")


# ============================================================
# Main
# ============================================================
if __name__ == "__main__":
    check_transformers()

    if not args.skip_merge:
        print("\n" + "=" * 60)
        print("  阶段 1/2: Merge LoRA → 完整模型 (bf16)")
        print("=" * 60)
        merge_lora()
    else:
        if not os.path.exists(MERGED_DIR):
            print(f"❌ --skip-merge 但模型不存在: {MERGED_DIR}")
            sys.exit(1)
        print(f"✅ 跳过 merge: {MERGED_DIR}")

    print("\n" + "=" * 60)
    print("  阶段 2/2: 导出 GGUF + Modelfile")
    print("=" * 60)
    export_gguf(MERGED_DIR)
    create_modelfile()

    print("\n✅ 全部完成!")
