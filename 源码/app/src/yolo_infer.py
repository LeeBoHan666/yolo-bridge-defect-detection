#!/usr/bin/env python3
"""
YOLO 推理脚本 — CPU 模式

直接使用 CPU 推理，跳过 GPU 检测，避免 CUDA 初始化耗时和不稳定问题。
"""

import sys
import os
import json
import argparse
import logging

# 必须在 import torch 之前设置，跳过 CUDA 初始化
os.environ['CUDA_VISIBLE_DEVICES'] = ''

logging.getLogger('ultralytics').setLevel(logging.ERROR)


def main():
    parser = argparse.ArgumentParser(description='YOLO inference (CPU)')
    parser.add_argument('--model', required=True, help='YOLO .pt model path')
    parser.add_argument('--image', required=True, help='Input image path')
    parser.add_argument('--output', required=True, help='Output JSON file path')
    parser.add_argument('--annotate-dir', default=None, help='Optional: output dir for annotated image')
    parser.add_argument('--device', default='cpu',
                        choices=['auto', 'cuda', 'cpu'],
                        help='Device (default: cpu)')
    args = parser.parse_args()

    device = 'cpu'
    print('[YOLO] CPU 模式', flush=True)

    # ---- 加载模型并推理 ----
    print('[YOLO] 加载模型中...', flush=True)
    from ultralytics import YOLO

    model = YOLO(args.model, verbose=False)
    print('[YOLO] 模型加载完成，开始推理...', flush=True)

    # device: "cuda" -> 0, "cpu" -> "cpu"
    dev = 0 if device == "cuda" else "cpu"
    results = model(args.image, verbose=False, device=dev, imgsz=1280)

    # ---- 解析结果 ----
    detections = []
    for r in results:
        boxes = r.boxes
        if boxes is not None:
            for i in range(len(boxes)):
                xyxy = boxes.xyxy[i].tolist()
                conf = float(boxes.conf[i])
                cls_name = model.names[int(boxes.cls[i])]
                detections.append({
                    'x1': xyxy[0],
                    'y1': xyxy[1],
                    'x2': xyxy[2],
                    'y2': xyxy[3],
                    'confidence': conf,
                    'class': cls_name
                })

    with open(args.output, 'w', encoding='utf-8') as f:
        json.dump(detections, f, ensure_ascii=False, separators=(',', ':'))

    # ---- 可选：画框标注 ----
    annotated_path = None
    if args.annotate_dir and detections:
        try:
            import cv2
            import numpy as np
            os.makedirs(args.annotate_dir, exist_ok=True)
            img = cv2.imread(args.image)
            colors = [
                (0, 0, 255), (0, 165, 255), (0, 255, 255),
                (255, 0, 255), (255, 0, 0), (0, 255, 0)
            ]
            for i, d in enumerate(detections):
                x1, y1 = int(d['x1']), int(d['y1'])
                x2, y2 = int(d['x2']), int(d['y2'])
                color = colors[i % len(colors)]
                cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
                label = f"{d['class']} {d['confidence']:.2f}"
                (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 2)
                cv2.rectangle(img, (x1, y1 - th - 6), (x1 + tw + 4, y1), color, -1)
                cv2.putText(img, label, (x1 + 2, y1 - 4),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)

            fname = f"yolo_{os.path.basename(args.image)}"
            annotated_path = os.path.join(args.annotate_dir, fname)
            cv2.imwrite(annotated_path, img)
        except ImportError:
            pass  # cv2 未安装则跳过画框

    # 输出标注图片路径（Java 读取）
    if annotated_path:
        print(f'ANNOTATED:{annotated_path}', flush=True)

    print(f'[YOLO] 完成！检测到 {len(detections)} 个目标, device={device}', flush=True)


if __name__ == '__main__':
    main()
