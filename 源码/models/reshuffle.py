import os, random, shutil
from pathlib import Path

SRC = Path("/root/rivermind-data/lost+found/yolo")
TMP = Path("/root/rivermind-data/lost+found/yolo/_merge")
SEED = 233

random.seed(SEED)

# ===== 第1步: 创建临时目录, 把所有文件移动过去 =====
tmp_img = TMP / "images"
tmp_lbl = TMP / "labels"
tmp_img.mkdir(parents=True, exist_ok=True)
tmp_lbl.mkdir(parents=True, exist_ok=True)

print("Step 1: Move all files to _merge ...")
for split in ["train", "val", "test"]:
    for src_dir, dst_dir in [(SRC/"images"/split, tmp_img), (SRC/"labels"/split, tmp_lbl)]:
        if src_dir.exists():
            for f in src_dir.iterdir():
                shutil.move(str(f), str(dst_dir / f.name))

# ===== 第2步: 收集所有图片-标签对 =====
print("Step 2: Collect pairs ...")
pairs = []
for img_file in tmp_img.iterdir():
    if img_file.suffix.lower() in {".jpg", ".png"}:
        lbl_file = tmp_lbl / (img_file.stem + ".txt")
        pairs.append((str(img_file), str(lbl_file) if lbl_file.exists() else None))

print(f"Total: {len(pairs)} images")
random.shuffle(pairs)

# ===== 第3步: 8:1:1 划分 =====
n = len(pairs)
n_train = int(n * 0.8)
n_val   = int(n * 0.1)

splits = {
    "train": pairs[:n_train],
    "val":   pairs[n_train:n_train + n_val],
    "test":  pairs[n_train + n_val:],
}

# ===== 第4步: 分配到目标目录 (源和目标不同, 安全) =====
print("Step 3: Distribute to train/val/test ...")
for name, items in splits.items():
    img_out = SRC / "images" / name
    lbl_out = SRC / "labels" / name
    img_out.mkdir(parents=True, exist_ok=True)
    lbl_out.mkdir(parents=True, exist_ok=True)
    for img_path, lbl_path in items:
        shutil.move(img_path, str(img_out / Path(img_path).name))
        if lbl_path:
            shutil.move(lbl_path, str(lbl_out / Path(lbl_path).name))
    print(f"  {name}: {len(items)} images")

# ===== 第5步: 删除临时目录 =====
shutil.rmtree(str(TMP), ignore_errors=True)
print("Done! No files deleted before copy.")
