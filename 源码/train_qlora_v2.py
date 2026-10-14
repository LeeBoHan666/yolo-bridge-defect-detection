"""
QLoRA 微调 Gemma 4 E4B 脚本（离线版）— 桥梁缺陷检测智能助手
============================================================
- 基座: 本地 ./gemma (Gemma 4 E4B, 多模态)
- 量化: 4-bit NF4 (bitsandbytes) — 纯离线，不联网
- LoRA: r=16 alpha=16 → 只训 text decoder 的线性层
- 冻结: Vision encoder + Audio encoder（不动多模态能力）
- 数据: sql_train.json + qa_train.json
- 输出: LoRA adapter → 合并 → GGUF → Ollama

用法:
  pip install transformers peft bitsandbytes accelerate datasets
  python train_qlora_v2.py

注: 使用标准 transformers.Trainer，不依赖 trl，避免 API 兼容问题。
"""

import os
import json
import torch

# 减少显存碎片（OOM 时有效）
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")

# ============================================================
# 配置
# ============================================================
# 自动检测模型路径（本地 Windows / 服务器 Linux）
import platform
_local = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "gemma")
_server = "/root/rivermind-data/lost+found/gemma"
MODEL_PATH = _server if os.path.exists(_server) else _local
OUTPUT_DIR = "./gemma4-bridge-lora"
MERGED_DIR = "./gemma4-bridge-merged"
DATA_DIR = "./training_data"

LORA_R = 16
LORA_ALPHA = 16
LORA_DROPOUT = 0.05
LEARNING_RATE = 2e-4
BATCH_SIZE = 1        # E4B + 1024 tokens 显存紧张，降到 1
GRAD_ACCUM = 16       # 有效 batch = 1×16 = 16
EPOCHS = 10          # ~7-8 小时，让模型充分收敛
MAX_LENGTH = 1024  # SQL 数据最长 790 字符，必须保留；YOLO JSON 截断到 1024 足够
SAVE_STEPS = 200
LOGGING_STEPS = 10
WARMUP_RATIO = 0.03


# ============================================================
# 1. 加载训练数据
# ============================================================
def load_training_data():
    all_data = []
    sql_path = os.path.join(DATA_DIR, "sql_train.json")
    qa_path = os.path.join(DATA_DIR, "qa_train.json")
    yolo_path = os.path.join(DATA_DIR, "yolo_json_train.json")

    for path, tag in [(sql_path, "sql"), (qa_path, "qa"), (yolo_path, "yolo_json")]:
        if not os.path.exists(path):
            print(f"⚠ 未找到 {path}，跳过")
            continue
        with open(path, "r", encoding="utf-8") as f:
            items = json.load(f)
        for item in items:
            system = item.get("system", "你是桥梁缺陷检测智能助手，精通SQL查询和桥梁工程专业知识。")
            instruction = item["instruction"]
            output = item["output"]
            text = (
                f"<|im_start|>system\n{system}<|im_end|>\n"
                f"<|im_start|>user\n{instruction}<|im_end|>\n"
                f"<|im_start|>assistant\n{output}<|im_end|>"
            )
            all_data.append({"text": text, "tag": tag})

    sql_n = sum(1 for d in all_data if d["tag"] == "sql")
    qa_n = sum(1 for d in all_data if d["tag"] == "qa")
    print(f"✅ 加载数据: SQL={sql_n}, QA={qa_n}, 总计={len(all_data)}")
    return all_data


# ============================================================
# 2. Gemma 4 兼容层：展开 Gemma4ClippableLinear → 标准 Linear
# ============================================================
def unwrap_gemma4_clippable(model):
    """
    Gemma 4 使用 Gemma4ClippableLinear 包装标准 Linear 层。
    PEFT 只认识 torch.nn.Linear，需要先把包装拆掉。
    """
    import torch.nn as nn

    replaced = 0
    for name, module in list(model.named_modules()):
        module_type = type(module).__name__

        # 检查是否是 Gemma4ClippableLinear 或其子类
        if "ClippableLinear" in module_type or "Clippable" in module_type:
            # 找到内部的 nn.Linear / Linear4bit
            inner_linear = None
            for child_name, child in module.named_children():
                if isinstance(child, (nn.Linear,)):
                    inner_linear = child
                    break
                # bnb 4bit 包装
                if "Linear4bit" in type(child).__name__:
                    inner_linear = child
                    break

            if inner_linear is not None:
                # 获取父模块和当前模块名
                parent = model.get_submodule(".".join(name.split(".")[:-1])) if "." in name else model
                leaf_name = name.split(".")[-1]

                # 替换: 用内部 linear 取代整个 clippable wrapper
                setattr(parent, leaf_name, inner_linear)
                replaced += 1

    if replaced > 0:
        print(f"🔧 展开 {replaced} 个 Gemma4ClippableLinear → 标准 Linear (PEFT 兼容)")
    return model


def find_linear_modules(model):
    """扫描模型，找出所有 nn.Linear 层的前缀名，用于 LoRA target_modules"""
    import torch.nn as nn
    modules = set()
    for name, module in model.named_modules():
        if isinstance(module, nn.Linear):
            modules.add(name.split(".")[-1])
    return sorted(modules)


# ============================================================
# 3. 加载模型（纯离线 QLoRA）
# ============================================================
def load_model_and_tokenizer():
    from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig
    from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training

    if not os.path.exists(MODEL_PATH):
        raise FileNotFoundError(f"❌ 模型路径不存在: {MODEL_PATH}\n"
                                f"   请把 Gemma 4 E4B 放在: {os.path.abspath(MODEL_PATH)}")

    print(f"📦 从本地加载模型: {os.path.abspath(MODEL_PATH)}")

    # --- 4-bit 量化配置 ---
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )

    # --- Tokenizer ---
    print("加载 tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(
        MODEL_PATH,
        local_files_only=True,
        trust_remote_code=True,
    )
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    # --- 模型（4bit 量化）---
    print("加载 4bit 模型（约 4-5 GB 显存，取决于碎片）...")
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_PATH,
        quantization_config=bnb_config,
        device_map="auto",
        trust_remote_code=True,
        local_files_only=True,
        torch_dtype=torch.bfloat16,
    )

    # --- 准备 k-bit 训练 ---
    model = prepare_model_for_kbit_training(model)

    # --- 展开 Gemma4ClippableLinear（PEFT 兼容）---
    model = unwrap_gemma4_clippable(model)

    # --- 扫描线性层，确认 target_modules ---
    linear_names = find_linear_modules(model)
    print(f"\n🔍 模型中的线性层类型: {linear_names}")

    # Gemma 4 实测线性层: q/k/v/o_proj, gate/up/down_proj,
    #   per_layer_input_gate, per_layer_projection, per_layer_model_projection,
    #   input_proj, input_proj_linear, output_proj, embedding_projection
    # LoRA 只加在 Attention + FFN 核心层，per_layer 和 input/output 层不动
    candidate_targets = [
        "q_proj", "k_proj", "v_proj", "o_proj",          # attention
        "gate_proj", "up_proj", "down_proj",              # FFN
    ]
    target_modules = [t for t in candidate_targets if t in linear_names]

    if not target_modules:
        # fallback: 用所有 linear 层（可能包含 embedding 等，但总能跑）
        print("⚠ 未匹配到标准 attention/FFN 层名，使用所有 linear 层作为 target")
        target_modules = linear_names

    print(f"🎯 LoRA target_modules: {target_modules}")

    # --- 注入 LoRA ---
    lora_config = LoraConfig(
        r=LORA_R,
        lora_alpha=LORA_ALPHA,
        lora_dropout=LORA_DROPOUT,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=target_modules,
    )
    model = get_peft_model(model, lora_config)

    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    frozen = sum(p.numel() for p in model.parameters() if not p.requires_grad)
    print(f"✅ 可训练参数: {trainable:,}")
    print(f"   冻结参数:   {frozen:,}")
    print(f"   训练比例:   {100 * trainable / (trainable + frozen):.2f}%")
    print(f"   Vision + Audio encoder 全部冻结 ✓")

    return model, tokenizer


# ============================================================
# 4. 训练（标准 transformers.Trainer，无 trl 依赖）
# ============================================================
def tokenize_dataset(tokenizer, dataset, max_length=1024):
    """预编码文本，动态 padding（batch_size=1 时无浪费）"""
    def _tokenize(examples):
        texts = examples["text"]
        result = tokenizer(
            texts,
            truncation=True,
            padding=False,               # 不 pad，等 collator 按 batch 最长动态 pad
            max_length=max_length,
        )
        result["labels"] = result["input_ids"]  # 直接引用，avoid copy 开销
        return result

    tokenized = dataset.map(_tokenize, batched=True, remove_columns=["text", "tag"])
    return tokenized


def train(model, tokenizer, dataset):
    from transformers import TrainingArguments, Trainer
    from transformers.data import DataCollatorForSeq2Seq

    # 预编码
    print("预编码训练数据...")
    tokenized = tokenize_dataset(tokenizer, dataset, max_length=MAX_LENGTH)

    # collator：按 batch 内最长样本动态 pad（batch=1 时几乎无开销）
    data_collator = DataCollatorForSeq2Seq(
        tokenizer=tokenizer,
        model=model,
        padding=True,
    )

    training_args = TrainingArguments(
        output_dir=OUTPUT_DIR,
        per_device_train_batch_size=BATCH_SIZE,
        gradient_accumulation_steps=GRAD_ACCUM,
        num_train_epochs=EPOCHS,
        learning_rate=LEARNING_RATE,
        warmup_steps=50,
        lr_scheduler_type="cosine",
        logging_steps=LOGGING_STEPS,
        save_steps=SAVE_STEPS,
        save_total_limit=3,
        bf16=True,
        fp16=False,
        optim="adamw_8bit",
        gradient_checkpointing=True,
        report_to="none",
        seed=42,
        remove_unused_columns=False,
    )

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized,
        data_collator=data_collator,
    )

    print("\n" + "=" * 60)
    print("  开始 QLoRA 训练 (Gemma 4 E4B)")
    print(f"  总数据: {len(dataset)} 条")
    print(f"  有效 batch: {BATCH_SIZE} × {GRAD_ACCUM} = {BATCH_SIZE * GRAD_ACCUM}")
    print(f"  总轮数: {EPOCHS}")
    print(f"  学习率: {LEARNING_RATE}")
    print(f"  LoRA rank: {LORA_R}")
    print("=" * 60 + "\n")

    trainer.train()

    model.save_pretrained(OUTPUT_DIR)
    tokenizer.save_pretrained(OUTPUT_DIR)
    print(f"\n✅ LoRA adapter 已保存到: {OUTPUT_DIR}")

    return model, tokenizer


# ============================================================
# 5. 合并 LoRA → 导出 GGUF → Modelfile
# ============================================================
GGUF_DIR = "./gemma4-bridge-gguf"
GGUF_Q4 = os.path.join(GGUF_DIR, "gemma4-bridge-q4_k_m.gguf")


def merge_and_export_gguf(model, tokenizer):
    os.makedirs(GGUF_DIR, exist_ok=True)

    # Step 1: 合并
    print("\n[1/3] 合并 LoRA 到基座...")
    merged = model.merge_and_unload()
    merged.save_pretrained(MERGED_DIR)
    tokenizer.save_pretrained(MERGED_DIR)
    print(f"  合并模型: {MERGED_DIR}")

    # Step 2: 导出 GGUF
    print("\n[2/3] 导出 GGUF q4_k_m...")
    try:
        import subprocess
        subprocess.run([
            "python", "-m", "llama_cpp.convert_hf_to_gguf",
            MERGED_DIR,
            "--outtype", "q4_k_m",
            "--outfile", GGUF_Q4,
        ], check=True)
        print(f"  ✅ GGUF: {GGUF_Q4}")
    except Exception as e:
        print(f"  ⚠ llama.cpp 导出失败: {e}")
        print(f"  手动方案: pip install llama-cpp-python")
        print(f"  然后: python -m llama_cpp.convert_hf_to_gguf {MERGED_DIR} --outtype q4_k_m --outfile {GGUF_Q4}")

    # Step 3: Modelfile
    print("\n[3/3] 创建 Ollama Modelfile...")
    modelfile = f"""FROM ./{os.path.basename(GGUF_Q4)}
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
"""
    mf_path = os.path.join(GGUF_DIR, "Modelfile")
    with open(mf_path, "w", encoding="utf-8") as f:
        f.write(modelfile)
    print(f"  ✅ Modelfile: {mf_path}")

    # 部署指令
    print(f"""
{'='*50}
  本机部署 (RTX 5070 8GB):
  1. cd {GGUF_DIR}
  2. ollama create gemma4-bridge -f Modelfile
  3. ollama run gemma4-bridge "什么是裂缝"
{'='*50}
""")


# ============================================================
# 6. 快速测试
# ============================================================
def test_model(model, tokenizer):
    model.eval()
    test_questions = [
        "最近一周有多少条裂缝检测记录",
        "什么是钢筋外露，有多危险",
        "帮我查一下破损的检测数量",
        "宽1.2mm的裂缝在梁底跨中怎么处理",
    ]
    print("\n" + "=" * 60)
    print("  训练后验证")
    print("=" * 60)

    for q in test_questions:
        prompt = f"<|im_start|>user\n{q}<|im_end|>\n<|im_start|>assistant\n"
        inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
        with torch.no_grad():
            outputs = model.generate(
                **inputs,
                max_new_tokens=200,
                temperature=0.3,
                do_sample=True,
                top_p=0.8,
            )
        response = tokenizer.decode(outputs[0], skip_special_tokens=True)
        if "<|im_start|>assistant" in response:
            response = response.split("<|im_start|>assistant")[-1].strip()
        print(f"\nQ: {q}")
        print(f"A: {response[:300]}")


# ============================================================
# Main
# ============================================================
if __name__ == "__main__":
    print("=" * 60)
    print("  Gemma 4 E4B QLoRA 离线微调 — 桥梁缺陷检测智能助手")
    print("=" * 60)
    print(f"""
配置:
  基座模型:   {os.path.abspath(MODEL_PATH)}
  架构:       Gemma 4 E4B (多模态: 文本+图像+音频)
  量化:       4-bit NF4 (bitsandbytes, 纯离线)
  LoRA:       r={LORA_R} alpha={LORA_ALPHA}
  学习率:     {LEARNING_RATE}
  Batch:      {BATCH_SIZE} × {GRAD_ACCUM} = {BATCH_SIZE * GRAD_ACCUM}
  Epochs:     {EPOCHS}
  最大长度:   {MAX_LENGTH}
  Vision/Audio: 冻结 (保留多模态)
  网络:       纯离线 ✓
""")

    # 1. 数据
    data = load_training_data()
    if len(data) == 0:
        print("❌ 无训练数据! 请先运行 generate_sql_data.py 和 generate_qa_data.py")
        exit(1)

    # 2. 模型
    print("\n加载模型（全程离线）...")
    model, tokenizer = load_model_and_tokenizer()

    # 3. 训练
    from datasets import Dataset
    hf_dataset = Dataset.from_list(data)
    model, tokenizer = train(model, tokenizer, hf_dataset)

    # 4. 导出
    merge_and_export_gguf(model, tokenizer)

    # 5. 验证
    test_model(model, tokenizer)

    print("\n✅ 训练完成!")
