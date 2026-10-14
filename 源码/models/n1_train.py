# ============================================================
# n1.py - GYU-DET 论文复现训练脚本
# 论文: Multi-defect type beam bridge dataset: GYU-DET
# 期刊: Scientific Data (Nature), 2025
# 模型: YOLO11n (预训练), batch=16, epochs=300, 全部默认参数
# ============================================================

from ultralytics import YOLO

if __name__ == '__main__':

    DATA_YAML = r"/root/rivermind-data/lost+found/yolo/data.yaml"

    # ============================================================
    # 1. 加载预训练模型 (论文使用 yolo11n.pt)
    # ============================================================
    model = YOLO("yolo11n.pt")  # 自动下载, ~5.5MB

    # ============================================================
    # 2. 训练 —— 全部参数与论文 Table 2 + 仓库 train.py 一致
    # ============================================================
    model.train(
        # ---- 数据集 ----
        data=DATA_YAML,             # 6 类缺陷检测
        epochs=300,                 # 论文设定
        patience=100,               # 100 轮无提升则早停
        batch=16,                   # 论文 batch size
        imgsz=640,                  # 输入尺寸 640x640

        # ---- 学习率 (YOLO11 默认值, 论文未修改) ----
        lr0=0.01,                   # 初始学习率 (SGD=1e-2, Adam=1e-3)
        lrf=0.01,                   # 最终学习率系数: min_lr = lr0 * lrf
        momentum=0.937,             # AdamW beta1
        weight_decay=0.0005,        # L2 正则化
        cos_lr=False,               # 不使用余弦退火
        warmup_epochs=3.0,          # 预热 3 轮
        warmup_momentum=0.8,        # 预热初始动量
        warmup_bias_lr=0.1,         # 预热偏置学习率

        # ---- 损失权重 (YOLO11 默认值) ----
        box=7.5,                    # 边框回归损失
        cls=0.5,                    # 分类损失
        dfl=1.5,                    # DFL (Distribution Focal Loss)

        # ---- 数据增强 (YOLO11 默认值, 论文未修改) ----
        hsv_h=0.015,                # HSV-色调增强
        hsv_s=0.7,                  # HSV-饱和度增强
        hsv_v=0.4,                  # HSV-明度增强
        degrees=0.0,                # 随机旋转 (±角度)
        translate=0.1,              # 随机平移 (±比例)
        scale=0.5,                  # 随机缩放 (±比例)
        shear=0.0,                  # 随机剪切
        perspective=0.0,            # 随机透视
        flipud=0.0,                 # 垂直翻转概率
        fliplr=0.5,                 # 水平翻转概率 (50%)
        mosaic=1.0,                 # Mosaic 增强
        mixup=0.0,                  # MixUp 关闭
        cutmix=0.0,                 # CutMix 关闭
        close_mosaic=10,            # 最后 10 轮关闭 mosaic

        # ---- 系统 ----
        optimizer="auto",           # YOLO11n 自动选择 AdamW
        amp=True,                   # 自动混合精度 (FP16)
        seed=0,                     # 随机种子 (复现关键!)
        deterministic=True,         # 确定性模式 (复现关键!)
        device=0,                   # GPU 设备号 (CPU 则填 "cpu")
        workers=8,                  # 数据加载线程数
        cache='RAM',                # 缓存图像到内存, 加速训练

        # ---- 保存 & 可视化 ----
        save=True,                  # 保存模型权重
        plots=True,                 # 保存训练曲线图
        verbose=True,               # 详细日志输出
        name="n1_gyu_det",          # 实验名称, 结果保存到 runs/detect/n1_gyu_det/
    )

    # ============================================================
    # 3. 训练完成后评估测试集
    # ============================================================
    # 论文测试集 mAP@0.5 基准: 0.568
    # 各缺陷 AP 基准 (论文 Table/Fig 7):
    #   Reinforcement: 0.689   Hole:      0.641
    #   Breakage:      0.605   Comb:      0.566
    #   Crack:         0.455   Seepage:   0.465
