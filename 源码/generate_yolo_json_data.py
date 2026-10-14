"""
生成 YOLO JSON 分析训练数据
===========================
模拟 YOLO 检测返回的 JSON 结果，训练模型理解检测输出：
- 单图分析、批量统计、缺陷对比、趋势判断
- 自然语言 → 对 JSON 结果的理解和回答
"""

import json
import os
import random

OUTPUT_FILE = os.path.join(os.path.dirname(__file__), "training_data", "yolo_json_train.json")

# ── 模拟 YOLO 检测结果模板 ──
CLASS_NAMES = {
    0: "裂缝(Crack)",
    1: "破损(Breakage)",
    2: "蜂窝麻面(Comb)",
    3: "孔洞(Hole)",
    4: "钢筋外露(Reinforcement)",
    5: "渗水(Seepage)",
}

BRIDGE_PARTS = ["梁底", "桥墩", "桥台", "桥面铺装", "伸缩缝", "支座", "翼缘板", "腹板", "横隔板", "防撞护栏"]


def random_detection():
    """生成单条检测框"""
    cls_id = random.randint(0, 5)
    return {
        "class_id": cls_id,
        "class_name": CLASS_NAMES[cls_id],
        "confidence": round(random.uniform(0.55, 0.98), 2),
        "bbox": [round(random.uniform(0, 500), 1) for _ in range(4)],
        "location": random.choice(BRIDGE_PARTS),
        "severity": random.choice(["轻微", "中等", "严重"]),
    }


def random_image_result(img_id):
    """生成单张图片的完整检测结果"""
    n = random.randint(0, 8)
    detections = [random_detection() for _ in range(n)]
    return {
        "image_id": img_id,
        "image_name": f"bridge_{img_id:04d}.jpg",
        "date": f"2026-0{random.randint(1,6)}-{random.randint(10,28):02d}",
        "num_defects": len(detections),
        "has_crack": any(d["class_id"] == 0 for d in detections),
        "has_breakage": any(d["class_id"] == 1 for d in detections),
        "has_comb": any(d["class_id"] == 2 for d in detections),
        "has_hole": any(d["class_id"] == 3 for d in detections),
        "has_reinforcement": any(d["class_id"] == 4 for d in detections),
        "has_seepage": any(d["class_id"] == 5 for d in detections),
        "detections": detections,
    }


def make_batch(n_images=5):
    """生成一批检测结果"""
    return [random_image_result(i + 1) for i in range(n_images)]


# ── 训练样本生成 ──
def generate():
    samples = []
    system_msg = (
        "你是桥梁缺陷检测智能助手。用户会提供 YOLO 模型检测桥梁缺陷后返回的 JSON 数据，"
        "你需要理解 JSON 结构，提取关键信息，用自然语言回答用户关于检测结果的问题。"
        "回答要准确、有数据支撑，引用具体的图片编号、缺陷类型、置信度和位置。"
    )

    for _ in range(200):
        batch = make_batch(random.randint(3, 8))
        batch_json = json.dumps(batch, ensure_ascii=False, indent=2)

        # ── 场景1: 统计各类缺陷数量 ──
        total = sum(img["num_defects"] for img in batch)
        crack_n = sum(1 for img in batch for d in img["detections"] if d["class_id"] == 0)
        breakage_n = sum(1 for img in batch for d in img["detections"] if d["class_id"] == 1)
        comb_n = sum(1 for img in batch for d in img["detections"] if d["class_id"] == 2)
        hole_n = sum(1 for img in batch for d in img["detections"] if d["class_id"] == 3)
        reinf_n = sum(1 for img in batch for d in img["detections"] if d["class_id"] == 4)
        seep_n = sum(1 for img in batch for d in img["detections"] if d["class_id"] == 5)
        samples.append({
            "system": system_msg,
            "instruction": f"统计这批检测结果中各类缺陷的数量：\n{batch_json}",
            "output": (
                f"这批共 {len(batch)} 张图片，检测到 {total} 处缺陷。分类统计："
                f"裂缝 {crack_n} 处、破损 {breakage_n} 处、蜂窝麻面 {comb_n} 处、"
                f"孔洞 {hole_n} 处、钢筋外露 {reinf_n} 处、渗水 {seep_n} 处。"
            ),
        })

        # ── 场景2: 最严重缺陷 ──
        all_dets = [(img["image_name"], d) for img in batch for d in img["detections"]]
        if all_dets:
            worst = max(all_dets, key=lambda x: x[1]["confidence"])
            samples.append({
                "system": system_msg,
                "instruction": f"这批图片里最严重的缺陷是哪个？\n{batch_json}",
                "output": (
                    f"置信度最高的是 {worst[0]} 中的{worst[1]['class_name']}，"
                    f"置信度 {worst[1]['confidence']}，位于{worst[1]['location']}，"
                    f"严重程度: {worst[1]['severity']}。"
                ),
            })

        # ── 场景3: 筛选特定缺陷 ──
        crack_imgs = [img["image_name"] for img in batch if img["has_crack"]]
        samples.append({
            "system": system_msg,
            "instruction": f"哪些图片有裂缝缺陷？\n{batch_json}",
            "output": (
                f"共 {len(crack_imgs)} 张图片检测到裂缝: {', '.join(crack_imgs) if crack_imgs else '无'}。"
            ),
        })

        # ── 场景4: 高风险告警 ──
        high_risk = [img["image_name"] for img in batch
                     if img["has_reinforcement"] and img["has_seepage"]]
        samples.append({
            "system": system_msg,
            "instruction": f"分析这批检测结果，有没有需要立即处理的高风险情况？\n{batch_json}",
            "output": (
                f"{'⚠ 有' + str(len(high_risk)) + '处高风险: ' + ', '.join(high_risk) + ' 同时存在钢筋外露和渗水，需立即处理。'
                 if high_risk else
                 '这批检测结果中未发现钢筋外露+渗水共存的紧急情况，按常规流程处理。'}"
            ),
        })

        # ── 场景5: 钢筋外露严重程度排序 ──
        reinf_dets = [(img["image_name"], d) for img in batch
                      for d in img["detections"] if d["class_id"] == 4]
        reinf_dets.sort(key=lambda x: x[1]["confidence"], reverse=True)
        samples.append({
            "system": system_msg,
            "instruction": f"把所有钢筋外露按严重程度排个序。\n{batch_json}",
            "output": (
                f"钢筋外露共 {len(reinf_dets)} 处，按置信度从高到低: " +
                "; ".join(f"{name} {d['confidence']} ({d['location']}, {d['severity']})"
                          for name, d in reinf_dets[:5]) +
                ("。" if len(reinf_dets) <= 5 else f" ...等共{len(reinf_dets)}处。")
            ),
        })

        # ── 场景6: 对比两张图 ──
        if len(batch) >= 2:
            a, b = batch[0], batch[1]
            samples.append({
                "system": system_msg,
                "instruction": (
                    f"对比 {a['image_name']} 和 {b['image_name']} 的检测结果，"
                    f"哪个更严重？\n{json.dumps([a, b], ensure_ascii=False, indent=2)}"
                ),
                "output": (
                    f"{a['image_name']} 检测到 {a['num_defects']} 处缺陷，"
                    f"{b['image_name']} 检测到 {b['num_defects']} 处缺陷。"
                    f"{'前者' if a['num_defects'] > b['num_defects'] else '后者' if b['num_defects'] > a['num_defects'] else '两者数量相同'}"
                    f"更严重，建议优先复查。"
                ),
            })

        # ── 场景7: 按日期的检测趋势 ──
        dates = sorted(set(img["date"] for img in batch))
        samples.append({
            "system": system_msg,
            "instruction": f"按日期汇总每天的检测总量。\n{batch_json}",
            "output": (
                "按日期统计: " +
                "; ".join(f"{d}: {sum(img['num_defects'] for img in batch if img['date'] == d)} 处"
                          for d in dates) +
                "。"
            ),
        })

    # 去重（相同 instruction 只保留一条）
    seen = set()
    unique = []
    for s in samples:
        key = s["instruction"]
        if key not in seen:
            seen.add(key)
            unique.append(s)

    os.makedirs(os.path.dirname(OUTPUT_FILE), exist_ok=True)
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(unique, f, ensure_ascii=False, indent=2)

    print(f"[OK] YOLO JSON analysis data: {len(unique)} samples -> {OUTPUT_FILE}")


if __name__ == "__main__":
    generate()
