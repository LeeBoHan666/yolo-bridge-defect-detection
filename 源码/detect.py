"""
桥梁缺陷检测引擎 - YOLO 目标检测模块
使用训练好的 YOLO11 模型对桥梁图片进行缺陷检测
支持通过命令行直接运行，也可被 app.py 导入使用
"""

import json
import sys
import os
from pathlib import Path

import cv2
import numpy as np
from ultralytics import YOLO

# ============================================================
# 缺陷类别定义（GYU-DET 数据集，与训练时一致）
# ============================================================
CLASS_NAMES = {
    0: "裂缝 (Crack)",
    1: "破损 (Breakage)",
    2: "蜂窝 (Comb)",
    3: "孔洞 (Hole)",
    4: "钢筋外露 (Reinforcement)",
    5: "渗水 (Seepage)",
}

# 每种缺陷的颜色（BGR 格式，用于画框）
CLASS_COLORS = {
    0: (0, 0, 255),       # 裂缝 - 红色
    1: (0, 165, 255),     # 破损 - 橙色
    2: (0, 255, 255),     # 蜂窝 - 黄色
    3: (255, 0, 255),     # 孔洞 - 紫色
    4: (255, 0, 0),       # 钢筋外露 - 蓝色
    5: (0, 255, 0),       # 渗水 - 绿色
}


class BridgeDefectDetector:
    """桥梁缺陷检测器，封装 YOLO 模型的加载与推理"""

    def __init__(self, model_path: str = None):
        """
        初始化检测器

        Args:
            model_path: 训练好的 YOLO 权重文件路径。
                        默认为源码目录下的 models/best.pt
        """
        if model_path is None:
            # 默认权重路径：源码/model/best.pt
            # 实际训练权重在 训练数据/n1_gyu_det/weights/best.pt
            script_dir = Path(__file__).parent
            candidates = [
                script_dir / "models" / "best.pt",
                script_dir.parent / "训练数据" / "n1_gyu_det" / "weights" / "best.pt",
                script_dir.parent / "yolo11m.pt",
            ]
            model_path = None
            for candidate in candidates:
                if candidate.exists():
                    model_path = str(candidate.resolve())
                    break

            if model_path is None:
                raise FileNotFoundError(
                    "未找到模型权重文件！请将 best.pt 放入 源码/models/ 目录\n"
                    f"查找过的路径: {[str(c) for c in candidates]}"
                )

        print(f"[检测器] 加载模型: {model_path}", file=sys.stderr)
        self.model = YOLO(model_path)
        self.model_path = model_path
        print(f"[检测器] 模型加载成功", file=sys.stderr)

    def detect(self, image_path: str, imgsz: int = 1280, conf_threshold: float = 0.25):
        """
        对单张图片执行缺陷检测

        Args:
            image_path: 输入图片路径
            imgsz: 推理分辨率（默认 1280，和训练时保持一致）
            conf_threshold: 置信度阈值，低于此值的检测框会被过滤

        Returns:
            dict: {
                "success": True/False,
                "image_path": 原始图片路径,
                "image_size": [宽, 高],
                "num_defects": 检测到的缺陷总数,
                "defects": [
                    {
                        "class_id": 类别编号,
                        "class_name": "类别中文名",
                        "confidence": 置信度(0~1),
                        "bbox": [x1, y1, x2, y2],  # 左上角和右下角坐标
                        "bbox_center": [cx, cy],
                        "severity": "严重程度评估"
                    },
                    ...
                ],
                "summary": "检测摘要文字"
            }
        """
        if not os.path.exists(image_path):
            return {"success": False, "error": f"图片不存在: {image_path}"}

        print(f"[检测器] 开始检测: {image_path}", file=sys.stderr)

        # 执行推理
        results = self.model(image_path, imgsz=imgsz, conf=conf_threshold, verbose=False)
        result = results[0]  # 单张图片取第一个结果

        # 读取原图获取尺寸
        img = cv2.imread(image_path)
        h, w = img.shape[:2]

        # 解析检测结果
        defects = []
        if result.boxes is not None and len(result.boxes) > 0:
            boxes = result.boxes.xyxy.cpu().numpy()        # [x1, y1, x2, y2]
            confidences = result.boxes.conf.cpu().numpy()   # 置信度
            class_ids = result.boxes.cls.cpu().numpy().astype(int)  # 类别编号

            for i in range(len(boxes)):
                cls_id = class_ids[i]
                conf = float(confidences[i])
                x1, y1, x2, y2 = boxes[i].tolist()

                # 评估严重程度（置信度越高越严重，bbox越大越严重）
                bbox_area = (x2 - x1) * (y2 - y1)
                img_area = w * h
                area_ratio = bbox_area / img_area

                if conf > 0.7 or area_ratio > 0.3:
                    severity = "严重"
                elif conf > 0.5 or area_ratio > 0.1:
                    severity = "中等"
                else:
                    severity = "轻微"

                defects.append({
                    "class_id": int(cls_id),
                    "class_name": CLASS_NAMES.get(int(cls_id), f"未知类别{cls_id}"),
                    "confidence": round(float(conf), 4),
                    "bbox": [round(float(x1), 1), round(float(y1), 1), round(float(x2), 1), round(float(y2), 1)],
                    "bbox_center": [round(float((x1 + x2) / 2), 1), round(float((y1 + y2) / 2), 1)],
                    "severity": severity,
                })

        # 按置信度从高到低排序
        defects.sort(key=lambda d: d["confidence"], reverse=True)

        # 生成摘要
        summary = self._build_summary(defects)

        print(f"[检测器] 检测完成, 发现 {len(defects)} 处缺陷", file=sys.stderr)

        return {
            "success": True,
            "image_path": image_path,
            "image_size": [w, h],
            "num_defects": len(defects),
            "defects": defects,
            "summary": summary,
        }

    def draw_annotations(self, image_path: str, defects: list, output_path: str = None):
        """
        在图片上绘制检测框并保存

        Args:
            image_path: 原始图片路径
            defects: detect() 返回的 defects 列表
            output_path: 输出图片路径，默认在原图同目录生成 *_detected.jpg

        Returns:
            输出图片路径
        """
        if output_path is None:
            base = os.path.splitext(image_path)[0]
            output_path = f"{base}_detected.jpg"

        img = cv2.imread(image_path)
        for d in defects:
            x1, y1, x2, y2 = [int(v) for v in d["bbox"]]
            cls_id = d["class_id"]
            color = CLASS_COLORS.get(cls_id, (255, 255, 255))

            # 画矩形框
            cv2.rectangle(img, (x1, y1), (x2, y2), color, 3)

            # 画标签背景
            label = f"{d['class_name'].split()[0]} {d['confidence']:.2f}"
            (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.7, 2)
            cv2.rectangle(img, (x1, y1 - th - 8), (x1 + tw + 4, y1), color, -1)

            # 写标签文字
            cv2.putText(img, label, (x1 + 2, y1 - 5),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)

        cv2.imwrite(output_path, img)
        print(f"[检测器] 标注图片已保存: {output_path}", file=sys.stderr)
        return output_path

    def _build_summary(self, defects: list) -> str:
        """根据检测结果生成中文摘要"""
        if not defects:
            return "未检测到桥梁缺陷，结构状态良好。"

        # 按类别统计
        class_count = {}
        for d in defects:
            name = d["class_name"]
            class_count[name] = class_count.get(name, 0) + 1

        parts = [f"共检测到 {len(defects)} 处缺陷："]
        for name, count in class_count.items():
            parts.append(f"  · {name}: {count}处")
        parts.append("")

        # 最严重的问题
        severe = [d for d in defects if d["severity"] == "严重"]
        if severe:
            parts.append(f"严重缺陷 {len(severe)} 处，需优先处理：")
            for d in severe[:3]:
                parts.append(f"  · {d['class_name']} (置信度: {d['confidence']:.0%})")
        elif any(d["severity"] == "中等" for d in defects):
            parts.append("存在中等程度缺陷，建议安排检修。")
        else:
            parts.append("缺陷程度轻微，可继续使用并定期复查。")

        return "\n".join(parts)


# ============================================================
# 命令行入口：python detect.py <image_path>
# ============================================================
if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python detect.py <图片路径> [模型路径]")
        sys.exit(1)

    image_path = sys.argv[1]
    model_path = sys.argv[2] if len(sys.argv) > 2 else None

    detector = BridgeDefectDetector(model_path)
    result = detector.detect(image_path)

    if result["success"]:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        # 保存标注图片
        detector.draw_annotations(image_path, result["defects"])
    else:
        print(json.dumps(result, ensure_ascii=False))
