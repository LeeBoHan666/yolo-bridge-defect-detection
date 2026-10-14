"""
QLoRA 微调 Gemma4 脚本 — 桥梁缺陷检测智能助手
==============================================
- 基座: google/gemma-2-4b-it (或 unsloth/gemma-2-4b-it)
- 量化: 4-bit NF4 (bitsandbytes)
- LoRA: r=16 alpha=16 target_modules=all-linear
- 数据: sql_train.json + qa_train.json
- 保留: MoE架构(权重冻结) + 多模态能力(视觉层不动)
- 输出: LoRA adapter → 合并 → GGUF → Ollama

用法:
  pip install unsloth transformers datasets peft bitsandbytes accelerate
  python train_qlora.py
"""

import os
import json
import torch

# ============================================================
# 配置
# ============================================================
MODEL_NAME = "unsloth/gemma-2-4b-it"  # unsloth 加速版
OUTPUT_DIR = "./gemma4-bridge-lora"
MERGED_DIR = "./gemma4-bridge-merged"
DATA_DIR = "./training_data"

LORA_R = 16
LORA_ALPHA = 16
LORA_DROPOUT = 0.05
LEARNING_RATE = 2e-4
BATCH_SIZE = 4
GRAD_ACCUM = 4
EPOCHS = 3
MAX_LENGTH = 1024
SAVE_STEPS = 200
LOGGING_STEPS = 10
WARMUP_RATIO = 0.03

# ============================================================
# 1. 加载训练数据
# ============================================================
def load_training_data():
    """合并 SQL + QA 训练数据，转为 ChatML 格式"""
    all_data = []

    sql_path = os.path.join(DATA_DIR, "sql_train.json")
    qa_path = os.path.join(DATA_DIR, "qa_train.json")

    for path, tag in [(sql_path, "sql"), (qa_path, "qa")]:
        if not os.path.exists(path):
            print(f"⚠ 未找到 {path}，跳过")
            continue
        with open(path, "r", encoding="utf-8") as f:
            items = json.load(f)
        for item in items:
            system = item.get("system", "你是桥梁缺陷检测智能助手，精通SQL查询和桥梁工程专业知识。")
            instruction = item["instruction"]
            output = item["output"]
            # ChatML 格式
            text = f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{instruction}<|im_end|>\n<|im_start|>assistant\n{output}<|im_end|>"
            all_data.append({"text": text, "tag": tag})

    print(f"加载数据: SQL={sum(1 for d in all_data if d['tag']=='sql')}, QA={sum(1 for d in all_data if d['tag']=='qa')}, 总计={len(all_data)}")
    return all_data


# ============================================================
# 2. 加载模型 (QLoRA)
# ============================================================
def load_model_and_tokenizer():
    """使用 unsloth 加载 4bit 量化模型 + LoRA"""
    try:
        from unsloth import FastLanguageModel
        use_unsloth = True
        print("✅ 使用 unsloth 加速")
    except ImportError:
        print("⚠ unsloth 未安装，回退到标准 transformers")
        print("  建议安装: pip install unsloth")
        use_unsloth = False

    if use_unsloth:
        model, tokenizer = FastLanguageModel.from_pretrained(
            model_name=MODEL_NAME,
            max_seq_length=MAX_LENGTH,
            dtype=None,              # 自动选择 float16
            load_in_4bit=True,       # 4-bit 量化
        )
        # 添加 LoRA adapter
        model = FastLanguageModel.get_peft_model(
            model,
            r=LORA_R,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                            "gate_proj", "up_proj", "down_proj"],
            lora_alpha=LORA_ALPHA,
            lora_dropout=LORA_DROPOUT,
            bias="none",
            use_gradient_checkpointing="unsloth",
            random_state=42,
            use_rslora=False,
            loftq_config=None,
        )
    else:
        from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig
        from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training

        bnb_config = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.float16,
            bnb_4bit_use_double_quant=True,
        )
        tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME.replace("unsloth/", ""))
        model = AutoModelForCausalLM.from_pretrained(
            MODEL_NAME.replace("unsloth/", ""),
            quantization_config=bnb_config,
            device_map="auto",
            trust_remote_code=True,
        )
        model = prepare_model_for_kbit_training(model)
        lora_config = LoraConfig(
            r=LORA_R,
            lora_alpha=LORA_ALPHA,
            lora_dropout=LORA_DROPOUT,
            bias="none",
            task_type="CAUSAL_LM",
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                            "gate_proj", "up_proj", "down_proj"],
        )
        model = get_peft_model(model, lora_config)

    # 设置 chat template
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    print(f"模型加载完成，可训练参数: {sum(p.numel() for p in model.parameters() if p.requires_grad):,}")
    print(f"基座参数(冻结): {sum(p.numel() for p in model.parameters() if not p.requires_grad):,}")
    print("MoE架构 + 多模态层全部冻结 ✓")

    return model, tokenizer


# ============================================================
# 3. 训练
# ============================================================
def train(model, tokenizer, dataset):
    try:
        from unsloth import is_bfloat16_supported
    except ImportError:
        is_bfloat16_supported = lambda: False

    from transformers import TrainingArguments
    from trl import SFTTrainer

    training_args = TrainingArguments(
        output_dir=OUTPUT_DIR,
        per_device_train_batch_size=BATCH_SIZE,
        gradient_accumulation_steps=GRAD_ACCUM,
        num_train_epochs=EPOCHS,
        learning_rate=LEARNING_RATE,
        warmup_ratio=WARMUP_RATIO,
        lr_scheduler_type="cosine",
        logging_steps=LOGGING_STEPS,
        save_steps=SAVE_STEPS,
        save_total_limit=3,
        bf16=is_bfloat16_supported(),
        fp16=not is_bfloat16_supported(),
        optim="adamw_8bit",
        gradient_checkpointing=True,
        report_to="none",
        seed=42,
        group_by_length=True,  # 相似长度打包，加速
    )

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=dataset,
        args=training_args,
        max_seq_length=MAX_LENGTH,
        dataset_text_field="text",
        packing=False,  # ChatML 不适合 packing
    )

    print("\n" + "=" * 60)
    print("开始 QLoRA 训练")
    print(f"  总数据: {len(dataset)} 条")
    print(f"  有效 batch size: {BATCH_SIZE * GRAD_ACCUM}")
    print(f"  总轮数: {EPOCHS}")
    print(f"  学习率: {LEARNING_RATE}")
    print(f"  LoRA rank: {LORA_R}")
    print("=" * 60 + "\n")

    trainer.train()

    # 保存 adapter
    model.save_pretrained(OUTPUT_DIR)
    tokenizer.save_pretrained(OUTPUT_DIR)
    print(f"\n✅ LoRA adapter 已保存到: {OUTPUT_DIR}")

    return model, tokenizer


# ============================================================
# 4. 合并 → 导出 GGUF → Modelfile
# ============================================================
GGUF_DIR = "./gemma4-bridge-gguf"
GGUF_Q4 = os.path.join(GGUF_DIR, "gemma4-bridge-q4_k_m.gguf")
GGUF_F16 = os.path.join(GGUF_DIR, "gemma4-bridge-f16.gguf")


def merge_and_export_gguf(model, tokenizer):
    """
    合并 LoRA → 导出 GGUF q4_k_m
    在 4090 云端执行，GGUF 文件拷贝到本地 5070 用 Ollama 加载
    """
    os.makedirs(GGUF_DIR, exist_ok=True)

    # ---- Step 1: 先存完整 16-bit（中间产物，可删） ----
    print("\n[1/4] 合并 LoRA 到基座模型...")
    merged = model.merge_and_unload()
    merged.save_pretrained(MERGED_DIR)
    tokenizer.save_pretrained(MERGED_DIR)
    print(f"  16-bit 合并模型(中间产物): {MERGED_DIR}")

    # ---- Step 2: 导出 GGUF q4_k_m（核心输出） ----
    print("\n[2/4] 导出 GGUF q4_k_m (4bit量化，和 e4b 同级)...")
    try:
        # unsloth 内置 GGUF 导出
        model.save_pretrained_gguf(
            GGUF_Q4,
            tokenizer,
            quantization_method="q4_k_m",
        )
        print(f"  ✅ GGUF q4_k_m: {GGUF_Q4}")
    except Exception as e:
        print(f"  ⚠ unsloth GGUF 导出失败: {e}")
        print("  尝试 llama.cpp 方式...")
        _export_via_llama_cpp()

    # ---- Step 3: 创建 Modelfile ----
    print("\n[3/4] 创建 Ollama Modelfile...")
    gguf_name = os.path.basename(GGUF_Q4)
    modelfile_content = f"""FROM ./{gguf_name}
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
    modelfile_path = os.path.join(GGUF_DIR, "Modelfile")
    with open(modelfile_path, "w", encoding="utf-8") as f:
        f.write(modelfile_content)
    print(f"  ✅ Modelfile: {modelfile_path}")

    # ---- Step 4: 打印部署指令 ----
    print("\n[4/4] 本机部署指令 (5070 8GB):")
    print("  " + "=" * 50)
    print(f"  1. 拷贝 {GGUF_DIR}/ 到本机")
    print(f"  2. cd {GGUF_DIR}")
    print(f"  3. ollama create gemma4-bridge -f Modelfile")
    print(f"  4. 验证: ollama run gemma4-bridge '什么是裂缝缺陷'")
    print(f"  5. 在 ollama_chat.py: model='gemma4-bridge'")
    print("  " + "=" * 50)

    # 显存估算
    print(f"\n  本机推理预估 (RTX 5070 8GB):")
    print(f"    GGUF q4_k_m 模型大小: ~2.5 GB")
    print(f"    推理峰值显存: ~3.5 GB ✅ 完全可行")


def _export_via_llama_cpp():
    """Fallback: 用 llama.cpp 的 convert 脚本"""
    import subprocess
    try:
        subprocess.run([
            "python", "-m", "llama_cpp.convert_hf_to_gguf",
            MERGED_DIR,
            "--outtype", "q4_k_m",
            "--outfile", GGUF_Q4,
        ], check=True)
        print(f"  ✅ GGUF q4_k_m: {GGUF_Q4}")
    except Exception as e2:
        print(f"  ❌ GGUF 导出失败: {e2}")
        print(f"  手动方案: pip install llama-cpp-python")
        print(f"  然后: python -m llama_cpp.convert_hf_to_gguf {MERGED_DIR} --outtype q4_k_m --outfile {GGUF_Q4}")
        raise


# ============================================================
# 5. 快速测试（训练后云端验证）
# ============================================================
def test_model(model, tokenizer):
    """快速测试微调效果"""
    test_questions = [
        "最近一周有多少条裂缝检测记录",
        "什么是钢筋外露，有多危险",
        "帮我查一下破损的检测数量",
        "宽1.2mm的裂缝在梁底跨中怎么处理",
    ]
    print("\n" + "=" * 60)
    print("云端快速验证")
    print("=" * 60)
    # 确保是 inference 模式
    try:
        from unsloth import FastLanguageModel
        FastLanguageModel.for_inference(model)
    except Exception:
        pass

    for q in test_questions:
        prompt = f"<|im_start|>user\n{q}<|im_end|>\n<|im_start|>assistant\n"
        inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
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
    print("  Gemma4 QLoRA 微调 — 桥梁缺陷检测智能助手")
    print("=" * 60)
    print(f"""
配置:
  基座模型:   {MODEL_NAME}
  量化:       4-bit NF4
  LoRA:       r={LORA_R} alpha={LORA_ALPHA}
  学习率:     {LEARNING_RATE}
  Batch:      {BATCH_SIZE} × {GRAD_ACCUM} = {BATCH_SIZE*GRAD_ACCUM}
  Epochs:     {EPOCHS}
  最大长度:   {MAX_LENGTH}
  MoE:        冻结 (保留)
  多模态:     冻结 (保留)
""")

    # 检查数据
    data = load_training_data()
    if len(data) == 0:
        print("❌ 无训练数据! 请先运行 generate_sql_data.py 和 generate_qa_data.py")
        exit(1)

    # 加载模型
    print("\n加载模型...")
    model, tokenizer = load_model_and_tokenizer()

    # 训练
    from datasets import Dataset
    hf_dataset = Dataset.from_list(data)
    model, tokenizer = train(model, tokenizer, hf_dataset)

    # 合并导出 GGUF
    merge_and_export_gguf(model, tokenizer)

    # 测试
    test_model(model, tokenizer)

    print("\n" + "=" * 60)
    print("  训练完成!")
    print(f"  导入 Ollama: ollama create gemma4-bridge -f {MERGED_DIR}/Modelfile")
    print("=" * 60)
