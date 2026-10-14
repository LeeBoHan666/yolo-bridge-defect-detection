"""
生成 Text-to-SQL 训练数据 (~1500条)
基于 bridge 数据库 users + defects 两张表
输出 Alpaca 格式 JSON，用于 QLoRA 微调

用法: python generate_sql_data.py
"""

import json
import os

SCHEMA_DESC = (
    "数据库中有两张表：\n"
    "1. users (id INT, username VARCHAR(50), password VARCHAR(100), role VARCHAR(20)) — 用户账户表\n"
    "2. defects (id INT, image_path VARCHAR(500), result_path VARCHAR(500), defect_count INT, "
    "defect_types VARCHAR(500), detail_json TEXT, ai_analysis TEXT, created_at TIMESTAMP) — 缺陷检测记录表\n"
    "缺陷类型6种: Crack(裂缝), Breakage(破损), Comb(蜂窝), Hole(孔洞), Reinforcement(钢筋外露), Seepage(渗水)\n"
    "严重程度在detail_json中以severity字段存储: 轻微/中等/严重\n"
    "密码使用SHA-256哈希存储。时间函数使用H2 MySQL兼容语法。"
)

CN = ["裂缝", "破损", "蜂窝", "孔洞", "钢筋外露", "渗水"]
EN = ["Crack", "Breakage", "Comb", "Hole", "Reinforcement", "Seepage"]
SEV = ["严重", "中等", "轻微"]
TIMES = [
    ("今天", "CURRENT_DATE"),
    ("昨天", "DATEADD('DAY', -1, CURRENT_DATE)"),
    ("最近3天", "DATEADD('DAY', -3, CURRENT_TIMESTAMP)"),
    ("最近7天", "DATEADD('DAY', -7, CURRENT_TIMESTAMP)"),
    ("最近一周", "DATEADD('DAY', -7, CURRENT_TIMESTAMP)"),
    ("最近14天", "DATEADD('DAY', -14, CURRENT_TIMESTAMP)"),
    ("最近一个月", "DATEADD('DAY', -30, CURRENT_TIMESTAMP)"),
    ("最近30天", "DATEADD('DAY', -30, CURRENT_TIMESTAMP)"),
]
LIMITS = [1, 3, 5, 10, 20, 50, 100]

pairs = []

def add(q, sql):
    pairs.append((q.strip(), sql.strip()))

# ===== 1. COUNT 查询 =====
for cn, en in zip(CN, EN):
    add(f"数据库里有多少条{cn}记录", f"SELECT COUNT(*) FROM defects WHERE defect_types LIKE '%{en}%'")
    add(f"统计{cn}缺陷的出现次数", f"SELECT COUNT(*) FROM defects WHERE defect_types LIKE '%{en}%'")
    add(f"{cn}出现了多少次", f"SELECT COUNT(*) FROM defects WHERE defect_types LIKE '%{en}%'")
    for tw, tw_sql in TIMES:
        add(f"{tw}有多少条{cn}记录", f"SELECT COUNT(*) FROM defects WHERE created_at > {tw_sql} AND defect_types LIKE '%{en}%'")
        add(f"统计{tw}的{cn}缺陷数量", f"SELECT COUNT(*) FROM defects WHERE created_at > {tw_sql} AND defect_types LIKE '%{en}%'")
    for sev in SEV:
        add(f"{cn}中{sev}等级有多少", f"SELECT COUNT(*) FROM defects WHERE defect_types LIKE '%{en}%' AND detail_json LIKE '%{sev}%'")
        add(f"统计{cn}的{sev}情况", f"SELECT COUNT(*) FROM defects WHERE defect_types LIKE '%{en}%' AND detail_json LIKE '%{sev}%'")

add("数据库里有多少条检测记录", "SELECT COUNT(*) FROM defects")
add("一共检测了多少张图片", "SELECT COUNT(*) FROM defects")
add("总共有多少用户", "SELECT COUNT(*) FROM users")
add("管理员有几个", "SELECT COUNT(*) FROM users WHERE role='admin'")
add("普通用户有多少个", "SELECT COUNT(*) FROM users WHERE role='user'")
add("今天有多少条检测", "SELECT COUNT(*) FROM defects WHERE DATE(created_at)=CURRENT_DATE")
add("昨天检测了多少张图", "SELECT COUNT(*) FROM defects WHERE DATE(created_at)=DATEADD('DAY',-1,CURRENT_DATE)")
add("最近一周新增多少记录", "SELECT COUNT(*) FROM defects WHERE created_at>DATEADD('DAY',-7,CURRENT_TIMESTAMP)")
add("最近一个月检测了多少次", "SELECT COUNT(*) FROM defects WHERE created_at>DATEADD('DAY',-30,CURRENT_TIMESTAMP)")
add("缺陷大于5的记录数", "SELECT COUNT(*) FROM defects WHERE defect_count>5")
add("缺陷小于等于3的记录", "SELECT COUNT(*) FROM defects WHERE defect_count<=3")
add("没有缺陷的图片有多少", "SELECT COUNT(*) FROM defects WHERE defect_count=0")
add("有严重缺陷的记录有多少", "SELECT COUNT(*) FROM defects WHERE detail_json LIKE '%严重%'")
add("中等缺陷数量", "SELECT COUNT(*) FROM defects WHERE detail_json LIKE '%中等%'")
add("轻微缺陷统计", "SELECT COUNT(*) FROM defects WHERE detail_json LIKE '%轻微%'")
add("缺陷总数是多少", "SELECT SUM(defect_count) FROM defects")
add("平均每张图几个缺陷", "SELECT ROUND(AVG(defect_count),2) FROM defects")
add("最多的缺陷数量", "SELECT MAX(defect_count) FROM defects")
add("最少检测到几个缺陷", "SELECT MIN(defect_count) FROM defects")
add("已分析的记录有多少", "SELECT COUNT(*) FROM defects WHERE ai_analysis IS NOT NULL")
add("未分析的记录数", "SELECT COUNT(*) FROM defects WHERE ai_analysis IS NULL")
for tw, tw_sql in TIMES:
    for sev in SEV:
        add(f"{tw}{sev}缺陷数量", f"SELECT COUNT(*) FROM defects WHERE created_at>{tw_sql} AND detail_json LIKE '%{sev}%'")

# ===== 2. 简单 SELECT =====
for cn, en in zip(CN, EN):
    add(f"查询所有{cn}记录", f"SELECT * FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"列出包含{cn}的检测", f"SELECT * FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"显示{cn}缺陷的图片", f"SELECT image_path, defect_count, created_at FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"有哪些图片有{cn}", f"SELECT image_path, created_at FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"找出所有{cn}", f"SELECT image_path, defect_count, defect_types FROM defects WHERE defect_types LIKE '%{en}%'")
    add(f"看看{cn}的情况", f"SELECT * FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"帮我查{cn}缺陷", f"SELECT * FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")

add("显示所有检测记录", "SELECT * FROM defects ORDER BY created_at DESC")
add("列出全部检测结果", "SELECT * FROM defects ORDER BY created_at DESC")
add("查看用户列表", "SELECT id, username, role FROM users ORDER BY id")
add("列出所有管理员", "SELECT username FROM users WHERE role='admin'")
add("查询普通用户", "SELECT username FROM users WHERE role='user'")
add("显示所有图片路径", "SELECT DISTINCT image_path FROM defects")
add("查询所有检测日期", "SELECT id, created_at FROM defects ORDER BY created_at DESC")

# ===== 3. [时间] × [缺陷类型] =====
for cn, en in zip(CN, EN):
    for tw, tw_sql in TIMES:
        add(f"{tw}的{cn}记录", f"SELECT * FROM defects WHERE created_at>{tw_sql} AND defect_types LIKE '%{en}%' ORDER BY created_at DESC")
        add(f"{tw}内检测到{cn}的图片", f"SELECT image_path, defect_count, created_at FROM defects WHERE created_at>{tw_sql} AND defect_types LIKE '%{en}%' ORDER BY created_at DESC")
        add(f"查询{tw}{cn}的检测结果", f"SELECT * FROM defects WHERE created_at>{tw_sql} AND defect_types LIKE '%{en}%' ORDER BY created_at DESC")

# ===== 4. [缺陷类型] × [严重程度] =====
for cn, en in zip(CN, EN):
    for sev in SEV:
        add(f"{cn}的{sev}记录", f"SELECT * FROM defects WHERE defect_types LIKE '%{en}%' AND detail_json LIKE '%{sev}%' ORDER BY created_at DESC")
        add(f"有哪些{cn}达到{sev}", f"SELECT image_path, defect_count, created_at FROM defects WHERE defect_types LIKE '%{en}%' AND detail_json LIKE '%{sev}%'")
        add(f"查询{sev}程度的{cn}", f"SELECT * FROM defects WHERE defect_types LIKE '%{en}%' AND detail_json LIKE '%{sev}%' ORDER BY created_at DESC")
        for tw, tw_sql in TIMES[:4]:
            add(f"{tw}{cn}中{sev}的记录", f"SELECT * FROM defects WHERE created_at>{tw_sql} AND defect_types LIKE '%{en}%' AND detail_json LIKE '%{sev}%' ORDER BY created_at DESC")

# ===== 5. 两两组合缺陷 =====
for i in range(len(CN)):
    for j in range(i+1, len(CN)):
        cn1, en1 = CN[i], EN[i]
        cn2, en2 = CN[j], EN[j]
        add(f"同时有{cn1}和{cn2}的图片", f"SELECT * FROM defects WHERE defect_types LIKE '%{en1}%' AND defect_types LIKE '%{en2}%' ORDER BY created_at DESC")
        add(f"查询{cn1}和{cn2}共同出现的记录", f"SELECT image_path, defect_count, created_at FROM defects WHERE defect_types LIKE '%{en1}%' AND defect_types LIKE '%{en2}%'")
        add(f"{cn1}和{cn2}同时出现的有几条", f"SELECT COUNT(*) FROM defects WHERE defect_types LIKE '%{en1}%' AND defect_types LIKE '%{en2}%'")

# ===== 6. [时间] × [严重程度] =====
for tw, tw_sql in TIMES:
    for sev in SEV:
        add(f"{tw}{sev}缺陷", f"SELECT * FROM defects WHERE created_at>{tw_sql} AND detail_json LIKE '%{sev}%' ORDER BY created_at DESC")

# ===== 7. SORT + LIMIT =====
for lim in LIMITS:
    add(f"最近{lim}条记录", f"SELECT * FROM defects ORDER BY created_at DESC LIMIT {lim}")
    add(f"显示前{lim}条", f"SELECT * FROM defects ORDER BY created_at DESC LIMIT {lim}")
    add(f"列出最近{lim}次检测", f"SELECT image_path, defect_count, defect_types, created_at FROM defects ORDER BY created_at DESC LIMIT {lim}")
    add(f"缺陷最多的{lim}张图", f"SELECT image_path, defect_count, defect_types FROM defects ORDER BY defect_count DESC LIMIT {lim}")
    add(f"缺陷数排名前{lim}", f"SELECT * FROM defects ORDER BY defect_count DESC LIMIT {lim}")
    add(f"缺陷最少的{lim}张图", f"SELECT image_path, defect_count, defect_types FROM defects ORDER BY defect_count ASC LIMIT {lim}")

for cn, en in zip(CN, EN):
    for lim in [3, 5, 10, 20]:
        add(f"最近{lim}条{cn}记录", f"SELECT * FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC LIMIT {lim}")
        add(f"显示{cn}最近{lim}次检测", f"SELECT image_path, defect_count, created_at FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC LIMIT {lim}")

# ===== 8. 聚合统计 =====
for cn, en in zip(CN, EN):
    add(f"{cn}的缺陷总数", f"SELECT SUM(defect_count) FROM defects WHERE defect_types LIKE '%{en}%'")
    add(f"{cn}平均缺陷数", f"SELECT AVG(defect_count) FROM defects WHERE defect_types LIKE '%{en}%'")
    add(f"{cn}最多一张图几个缺陷", f"SELECT MAX(defect_count) FROM defects WHERE defect_types LIKE '%{en}%'")
    add(f"{cn}最少几个缺陷", f"SELECT MIN(defect_count) FROM defects WHERE defect_types LIKE '%{en}%'")

add("按缺陷类型统计", "SELECT defect_types, COUNT(*) FROM defects GROUP BY defect_types ORDER BY COUNT(*) DESC")
add("每种缺陷分别有多少条", "SELECT defect_types, COUNT(*) as cnt FROM defects GROUP BY defect_types ORDER BY cnt DESC")
add("按天统计检测量", "SELECT DATE(created_at) as dt, COUNT(*) FROM defects GROUP BY DATE(created_at) ORDER BY dt DESC")
add("按严重程度分组统计", "SELECT '严重' as s, COUNT(*) FROM defects WHERE detail_json LIKE '%严重%' UNION ALL SELECT '中等', COUNT(*) FROM defects WHERE detail_json LIKE '%中等%' UNION ALL SELECT '轻微', COUNT(*) FROM defects WHERE detail_json LIKE '%轻微%'")
add("哪种缺陷最常见", "SELECT defect_types, COUNT(*) as cnt FROM defects GROUP BY defect_types ORDER BY cnt DESC LIMIT 1")
add("各角色用户人数", "SELECT role, COUNT(*) FROM users GROUP BY role")

# ===== 9. INSERT / UPDATE / DELETE =====
add("添加用户test密码123456", "INSERT INTO users (username,password,role) VALUES ('test',HASH('SHA256','123456'),'user')")
add("创建管理员manager", "INSERT INTO users (username,password,role) VALUES ('manager',HASH('SHA256','manager123'),'admin')")
add("新增检测bridge_01.jpg有裂缝和渗水", "INSERT INTO defects (image_path,defect_count,defect_types) VALUES ('bridge_01.jpg',2,'Crack, Seepage')")
add("录入检测test.png发现3处破损", "INSERT INTO defects (image_path,defect_count,defect_types) VALUES ('test.png',3,'Breakage')")
add("把用户test提升为管理员", "UPDATE users SET role='admin' WHERE username='test'")
add("更新用户111的密码为newpass", "UPDATE users SET password=HASH('SHA256','newpass') WHERE username='111'")
add("修改记录id=1的AI分析", "UPDATE defects SET ai_analysis='<新内容>' WHERE id=1")
add("把id=3的缺陷数改为8", "UPDATE defects SET defect_count=8 WHERE id=3")
add("删除用户guest", "DELETE FROM users WHERE username='guest'")
add("删除id=5的检测记录", "DELETE FROM defects WHERE id=5")
add("清空所有检测记录", "DELETE FROM defects")
add("删除3天前的记录", "DELETE FROM defects WHERE created_at<DATEADD('DAY',-3,CURRENT_TIMESTAMP)")
for cn, en in zip(CN, EN):
    add(f"删除所有{cn}记录", f"DELETE FROM defects WHERE defect_types LIKE '%{en}%'")

# ===== 10. 排除/NOT 查询 =====
for cn, en in zip(CN, EN):
    add(f"不包含{cn}的记录", f"SELECT * FROM defects WHERE defect_types NOT LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"除了{cn}之外的所有缺陷", f"SELECT * FROM defects WHERE defect_types NOT LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"排除{cn}的记录", f"SELECT * FROM defects WHERE defect_types NOT LIKE '%{en}%'")
    add(f"最近一周排除{cn}", f"SELECT * FROM defects WHERE created_at>DATEADD('DAY',-7,CURRENT_TIMESTAMP) AND defect_types NOT LIKE '%{en}%' ORDER BY created_at DESC")

# ===== 11. NULL 查询 =====
add("未做AI分析的记录", "SELECT * FROM defects WHERE ai_analysis IS NULL ORDER BY created_at DESC")
add("已分析的记录", "SELECT * FROM defects WHERE ai_analysis IS NOT NULL")
add("哪些图还没分析", "SELECT image_path, created_at FROM defects WHERE ai_analysis IS NULL")
add("最近一周未分析的", "SELECT * FROM defects WHERE ai_analysis IS NULL AND created_at>DATEADD('DAY',-7,CURRENT_TIMESTAMP)")
add("最近一个月已分析的严重缺陷", "SELECT * FROM defects WHERE ai_analysis IS NOT NULL AND detail_json LIKE '%严重%' AND created_at>DATEADD('DAY',-30,CURRENT_TIMESTAMP)")

# ===== 12. 多条件复合 =====
for cn, en in zip(CN, EN):
    for sev in SEV:
        for tw, tw_sql in TIMES[:3]:
            add(f"{tw}{cn}{sev}的数量", f"SELECT COUNT(*) FROM defects WHERE created_at>{tw_sql} AND defect_types LIKE '%{en}%' AND detail_json LIKE '%{sev}%'")

# ===== 13. JOIN 查询 =====
add("列出检测及对应用户", "SELECT d.*,u.username FROM defects d LEFT JOIN users u ON d.id=u.id ORDER BY d.created_at DESC")
add("每个用户检测了多少", "SELECT u.username,COUNT(d.id) FROM users u LEFT JOIN defects d ON u.id=d.id GROUP BY u.username ORDER BY COUNT(d.id) DESC")
add("谁检测的最多", "SELECT u.username,COUNT(d.id) as cnt FROM users u JOIN defects d ON u.id=d.id GROUP BY u.username ORDER BY cnt DESC LIMIT 1")
add("管理员检测了哪些图", "SELECT d.* FROM defects d JOIN users u ON d.id=u.id WHERE u.role='admin' ORDER BY d.created_at DESC")
add("每用户的最后检测时间", "SELECT u.username,MAX(d.created_at) FROM users u LEFT JOIN defects d ON u.id=d.id GROUP BY u.username")

# ===== 14. DDL / 表结构 =====
add("defects表有哪些字段", "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='DEFECTS'")
add("users表的结构", "SELECT COLUMN_NAME,TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='USERS'")
add("数据库里有哪些表", "SHOW TABLES")

# ===== 15. 用户管理 =====
add("有哪些角色类型", "SELECT DISTINCT role FROM users")
add("ID大于1的用户", "SELECT * FROM users WHERE id>1")
add("查看admin用户信息", "SELECT * FROM users WHERE username='admin'")
add("查询用户111的角色", "SELECT role FROM users WHERE username='111'")
add("有哪些人是管理员", "SELECT username FROM users WHERE role='admin'")

# ===== 16. 数据分析查询 =====
add("缺陷类型占比", "SELECT defect_types,COUNT(*),ROUND(COUNT(*)*100.0/(SELECT COUNT(*) FROM defects),2) as pct FROM defects GROUP BY defect_types ORDER BY COUNT(*) DESC")
add("严重缺陷占多少比例", "SELECT ROUND(COUNT(*)*100.0/(SELECT COUNT(*) FROM defects),2) FROM defects WHERE detail_json LIKE '%严重%'")
add("缺陷数量随时间变化", "SELECT DATE(created_at) as dt,COUNT(*),SUM(defect_count) FROM defects GROUP BY DATE(created_at) ORDER BY dt")
add("最近一个月缺陷类型分布", "SELECT defect_types,COUNT(*) FROM defects WHERE created_at>DATEADD('DAY',-30,CURRENT_TIMESTAMP) GROUP BY defect_types ORDER BY COUNT(*) DESC")
add("哪种缺陷最容易严重", "SELECT defect_types,COUNT(*) FROM defects WHERE detail_json LIKE '%严重%' GROUP BY defect_types ORDER BY COUNT(*) DESC")
add("对比裂缝和破损的次数", "SELECT 'Crack' as t,COUNT(*) FROM defects WHERE defect_types LIKE '%Crack%' UNION ALL SELECT 'Breakage',COUNT(*) FROM defects WHERE defect_types LIKE '%Breakage%'")

# ===== 17. BETWEEN / 范围 =====
for cn, en in zip(CN, EN):
    add(f"缺陷数3到10之间的{cn}", f"SELECT * FROM defects WHERE defect_count BETWEEN 3 AND 10 AND defect_types LIKE '%{en}%' ORDER BY defect_count DESC")
    add(f"{cn}且缺陷数大于5", f"SELECT * FROM defects WHERE defect_count>5 AND defect_types LIKE '%{en}%' ORDER BY defect_count DESC")
    add(f"缺陷很多且包含{cn}", f"SELECT * FROM defects WHERE defect_count>=9 AND defect_types LIKE '%{en}%' ORDER BY defect_count DESC")

add("缺陷数5到10之间的记录", "SELECT * FROM defects WHERE defect_count BETWEEN 5 AND 10 ORDER BY defect_count DESC")
add("查询2025年6月的记录", "SELECT * FROM defects WHERE created_at BETWEEN '2025-06-01' AND '2025-06-30'")
add("3天前到今天的记录", "SELECT * FROM defects WHERE created_at BETWEEN DATEADD('DAY',-3,CURRENT_TIMESTAMP) AND CURRENT_TIMESTAMP ORDER BY created_at DESC")

# ===== 18. 自然语言变体 =====
for cn, en in zip(CN, EN):
    add(f"查一下{cn}", f"SELECT * FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"帮我看看{cn}缺陷", f"SELECT * FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"告诉我{cn}的检测结果", f"SELECT image_path, defect_count, created_at FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"{cn}的情况怎么样", f"SELECT * FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"有哪些{cn}问题", f"SELECT image_path, defect_count, defect_types FROM defects WHERE defect_types LIKE '%{en}%'")
    add(f"带{cn}的图片多吗", f"SELECT COUNT(*) FROM defects WHERE defect_types LIKE '%{en}%'")

# ===== 19. 多字段筛选变体 =====
fields_list = [
    "id, image_path, defect_count, defect_types, created_at",
    "image_path, defect_count, defect_types",
    "image_path, defect_count, created_at",
    "id, defect_count, defect_types, detail_json",
    "image_path, defect_types, ai_analysis, created_at",
]
field_descs = ["基本信息", "图片和缺陷", "图片和时间", "详细信息", "分析和时间"]
for cn, en in zip(CN, EN):
    for fl, fd in zip(fields_list, field_descs):
        add(f"查询{cn}的{fd}", f"SELECT {fl} FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")

# ===== 20. HAVING 查询 =====
add("出现超过50次的缺陷类型", "SELECT defect_types,COUNT(*) as cnt FROM defects GROUP BY defect_types HAVING COUNT(*)>50 ORDER BY cnt DESC")
add("缺陷数总和超过100的类型", "SELECT defect_types,SUM(defect_count) as total FROM defects GROUP BY defect_types HAVING SUM(defect_count)>100 ORDER BY total DESC")
add("平均缺陷数大于3的类型", "SELECT defect_types,AVG(defect_count) as avg FROM defects GROUP BY defect_types HAVING AVG(defect_count)>3")

# ===== 21. CASE WHEN =====
add("查询并标注是否严重", "SELECT id,image_path,defect_count,CASE WHEN detail_json LIKE '%严重%' THEN '是' ELSE '否' END as is_severe FROM defects ORDER BY created_at DESC")

# ===== 22. 复杂逻辑组合 =====
add("裂缝中严重的最远10条", "SELECT * FROM defects WHERE defect_types LIKE '%Crack%' AND detail_json LIKE '%严重%' ORDER BY created_at DESC LIMIT 10")
add("破损或钢筋外露最近一周的", "SELECT * FROM defects WHERE (defect_types LIKE '%Breakage%' OR defect_types LIKE '%Reinforcement%') AND created_at>DATEADD('DAY',-7,CURRENT_TIMESTAMP) ORDER BY created_at DESC")
add("有分析且严重的记录", "SELECT * FROM defects WHERE ai_analysis IS NOT NULL AND detail_json LIKE '%严重%' ORDER BY created_at DESC")

# ===== 23. 子查询 =====
add("查出比平均缺陷数多的记录", "SELECT * FROM defects WHERE defect_count>(SELECT AVG(defect_count) FROM defects) ORDER BY defect_count DESC")
add("缺陷数大于今天平均值的记录", "SELECT * FROM defects WHERE defect_count>(SELECT AVG(defect_count) FROM defects WHERE DATE(created_at)=CURRENT_DATE)")
add("检测量最高那天的所有记录", "SELECT * FROM defects WHERE DATE(created_at)=(SELECT DATE(created_at) FROM defects GROUP BY DATE(created_at) ORDER BY COUNT(*) DESC LIMIT 1)")
for cn, en in zip(CN, EN):
    add(f"比{cn}平均缺陷数多的记录", f"SELECT * FROM defects WHERE defect_count>(SELECT AVG(defect_count) FROM defects WHERE defect_types LIKE '%{en}%') AND defect_types LIKE '%{en}%' ORDER BY defect_count DESC")

# ===== 24. 更多时间变体 =====
add("上个月的检测记录", "SELECT * FROM defects WHERE MONTH(created_at)=MONTH(DATEADD('MONTH',-1,CURRENT_DATE)) AND YEAR(created_at)=YEAR(DATEADD('MONTH',-1,CURRENT_DATE)) ORDER BY created_at DESC")
add("本月检测了多少", "SELECT COUNT(*) FROM defects WHERE MONTH(created_at)=MONTH(CURRENT_DATE) AND YEAR(created_at)=YEAR(CURRENT_DATE)")
add("上月检测了多少", "SELECT COUNT(*) FROM defects WHERE MONTH(created_at)=MONTH(DATEADD('MONTH',-1,CURRENT_DATE))")
add("按月份汇总", "SELECT YEAR(created_at),MONTH(created_at),COUNT(*),SUM(defect_count) FROM defects GROUP BY YEAR(created_at),MONTH(created_at) ORDER BY YEAR(created_at),MONTH(created_at)")
add("每天各检测了多少张", "SELECT DATE(created_at) as dt,COUNT(*) FROM defects GROUP BY DATE(created_at) ORDER BY dt")
add("今天和昨天的记录", "SELECT * FROM defects WHERE DATE(created_at)>=DATEADD('DAY',-1,CURRENT_DATE) ORDER BY created_at DESC")
add("最近24小时新增", "SELECT * FROM defects WHERE created_at>DATEADD('HOUR',-24,CURRENT_TIMESTAMP) ORDER BY created_at DESC")
add("本周的检测记录", "SELECT * FROM defects WHERE WEEK(created_at)=WEEK(CURRENT_DATE) ORDER BY created_at DESC")

# ===== 25. 更多排序变体 =====
for lim in [15, 25, 30, 50]:
    add(f"缺陷最多的前{lim}条记录", f"SELECT image_path,defect_count,defect_types FROM defects ORDER BY defect_count DESC LIMIT {lim}")
    add(f"显示最新的{lim}个结果", f"SELECT * FROM defects ORDER BY created_at DESC LIMIT {lim}")
    add(f"查询最近{lim}条", f"SELECT * FROM defects ORDER BY created_at DESC LIMIT {lim}")

# ===== 26. LIKE 模式变体 =====
add("图片路径包含bridge的记录", "SELECT * FROM defects WHERE image_path LIKE '%bridge%'")
add("路径以test开头的记录", "SELECT * FROM defects WHERE image_path LIKE 'test%'")
add("图片是jpg格式的", "SELECT * FROM defects WHERE image_path LIKE '%.jpg'")
add("图片是png格式的", "SELECT * FROM defects WHERE image_path LIKE '%.png'")

# ===== 27. 组合条件更多变体 =====
add("裂缝或破损的严重记录", "SELECT * FROM defects WHERE (defect_types LIKE '%Crack%' OR defect_types LIKE '%Breakage%') AND detail_json LIKE '%严重%' ORDER BY created_at DESC")
add("渗水且未分析的记录", "SELECT * FROM defects WHERE defect_types LIKE '%Seepage%' AND ai_analysis IS NULL")
add("已分析且有严重缺陷的", "SELECT * FROM defects WHERE ai_analysis IS NOT NULL AND detail_json LIKE '%严重%'")
add("未分析且缺陷数大于3的", "SELECT * FROM defects WHERE ai_analysis IS NULL AND defect_count>3")
add("蜂窝或孔洞且数量大于5", "SELECT * FROM defects WHERE (defect_types LIKE '%Comb%' OR defect_types LIKE '%Hole%') AND defect_count>5")

# ===== 28. 更多数据操作 =====
add("批量更新未分析的为已检查", "UPDATE defects SET ai_analysis='已检查' WHERE ai_analysis IS NULL")
add("把所有严重缺陷标记为紧急", "UPDATE defects SET ai_analysis='紧急维修' WHERE detail_json LIKE '%严重%'")
add("归档30天前的记录", "DELETE FROM defects WHERE created_at<DATEADD('DAY',-30,CURRENT_TIMESTAMP)")
add("复制id=1的记录", "INSERT INTO defects (image_path,result_path,defect_count,defect_types,detail_json,ai_analysis) SELECT image_path,result_path,defect_count,defect_types,detail_json,ai_analysis FROM defects WHERE id=1")
add("重置用户123的密码", "UPDATE users SET password=HASH('SHA256','123456') WHERE username='123'")
add("新建测试用户tester", "INSERT INTO users (username,password,role) VALUES ('tester',HASH('SHA256','test123'),'user')")
add("删除用户123", "DELETE FROM users WHERE username='123'")

# ===== 29. 更多聚合分析 =====
add("各缺陷类型的最大最小平均缺陷数", "SELECT defect_types,MAX(defect_count),MIN(defect_count),AVG(defect_count) FROM defects GROUP BY defect_types ORDER BY AVG(defect_count) DESC")
add("每周检测趋势", "SELECT WEEK(created_at),COUNT(*) FROM defects GROUP BY WEEK(created_at) ORDER BY WEEK(created_at)")
add("缺陷数量分布统计", "SELECT defect_count,COUNT(*) FROM defects GROUP BY defect_count ORDER BY defect_count")
add("分析覆盖率", "SELECT ROUND(COUNT(*)*100.0/(SELECT COUNT(*) FROM defects),2) FROM defects WHERE ai_analysis IS NOT NULL")
add("管理员的检测量", "SELECT u.username,COUNT(d.id) FROM users u LEFT JOIN defects d ON u.id=d.id WHERE u.role='admin' GROUP BY u.username")
add("普通用户的活跃度", "SELECT u.username,COUNT(d.id) FROM users u LEFT JOIN defects d ON u.id=d.id WHERE u.role='user' GROUP BY u.username ORDER BY COUNT(d.id) DESC")

# ===== 30. 安全相关 =====
add("检查有没有SQL注入风险的用户名", "SELECT username FROM users WHERE username LIKE '%\"%' OR username LIKE \"%'%\" OR username LIKE '%;%'")
add("统计密码非哈希格式的用户", "SELECT username FROM users WHERE LENGTH(password)!=64")
add("最近创建的用户", "SELECT username,role FROM users ORDER BY id DESC LIMIT 5")

# ===== 31. 导出/备份相关 =====
add("导出所有检测记录为CSV", "SELECT * FROM defects ORDER BY created_at DESC")
add("导出裂缝检测汇总", "SELECT image_path,defect_count,detail_json,created_at FROM defects WHERE defect_types LIKE '%Crack%' ORDER BY created_at DESC")
add("生成月度报告数据", "SELECT defect_types,COUNT(*),SUM(defect_count),AVG(defect_count) FROM defects WHERE created_at>DATEADD('DAY',-30,CURRENT_TIMESTAMP) GROUP BY defect_types")
add("汇总所有严重缺陷信息", "SELECT image_path,defect_types,detail_json,created_at FROM defects WHERE detail_json LIKE '%严重%' ORDER BY created_at DESC")

# ===== 32. 多表更多变体 =====
add("统计各用户各缺陷类型的检测量", "SELECT u.username,d.defect_types,COUNT(*) FROM users u JOIN defects d ON u.id=d.id GROUP BY u.username,d.defect_types ORDER BY u.username,COUNT(*) DESC")
add("哪些用户没做过检测", "SELECT u.username FROM users u LEFT JOIN defects d ON u.id=d.id WHERE d.id IS NULL")
add("admin检测的缺陷类型汇总", "SELECT d.defect_types,COUNT(*) FROM defects d JOIN users u ON d.id=u.id WHERE u.username='admin' GROUP BY d.defect_types ORDER BY COUNT(*) DESC")
add("检测量最高的用户检测了什么", "SELECT d.* FROM defects d JOIN (SELECT u.id FROM users u JOIN defects d2 ON u.id=d2.id GROUP BY u.id ORDER BY COUNT(*) DESC LIMIT 1) top ON d.id=top.id ORDER BY d.created_at DESC")

# ===== 33. EXCEPT / 差集概念 =====
add("只有裂缝没有其他缺陷的图", "SELECT * FROM defects WHERE defect_types='Crack'")
add("纯裂缝（不含其他类型）", "SELECT * FROM defects WHERE defect_types='%Crack%' AND defect_types NOT LIKE '%,%'")
for cn, en in zip(CN, EN):
    add(f"只检测到{cn}一种缺陷", f"SELECT * FROM defects WHERE defect_types='{en}'")

# ===== 34. 自然语言更多表达 =====
for cn, en in zip(CN, EN):
    add(f"{cn}的问题严不严重", f"SELECT detail_json FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY created_at DESC")
    add(f"哪些图{cn}最厉害", f"SELECT image_path,defect_count,detail_json FROM defects WHERE defect_types LIKE '%{en}%' ORDER BY defect_count DESC LIMIT 5")
    add(f"最近{cn}有没有恶化趋势", f"SELECT DATE(created_at),SUM(defect_count) FROM defects WHERE defect_types LIKE '%{en}%' GROUP BY DATE(created_at) ORDER BY DATE(created_at) DESC")
    add(f"统计一下{cn}缺陷", f"SELECT COUNT(*),SUM(defect_count),AVG(defect_count),MAX(defect_count) FROM defects WHERE defect_types LIKE '%{en}%'")

# ===== 35. 实用运维查询 =====
add("数据库用了多少存储空间", "SELECT TABLE_NAME,STORAGE_SIZE FROM INFORMATION_SCHEMA.TABLE_STORAGE")
add("defects表行数估算", "SELECT COUNT(*) FROM defects")
add("最早的检测是什么时候", "SELECT MIN(created_at) FROM defects")
add("最新的检测是什么时候", "SELECT MAX(created_at) FROM defects")
add("检测时间跨度有多少天", "SELECT DATEDIFF('DAY',MIN(created_at),MAX(created_at)) FROM defects")
add("每类缺陷的置信度分布", "SELECT defect_types,detail_json FROM defects")
add("查询缺陷数异常的记录（离群值）", "SELECT * FROM defects WHERE defect_count>(SELECT AVG(defect_count)+2*STDDEV(defect_count) FROM defects)")

# ===== 去重 =====
seen = set()
unique = []
for q, sql in pairs:
    if q not in seen:
        seen.add(q)
        unique.append((q, sql))

print(f"共生成 {len(unique)} 条 Text-to-SQL 训练对")

# ===== 输出 Alpaca JSON =====
data = []
for q, sql in unique:
    data.append({
        "instruction": q,
        "input": "",
        "output": sql,
        "system": f"你是SQL查询助手。只输出SQL语句，不输出任何解释。{SCHEMA_DESC}",
    })

out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "training_data")
os.makedirs(out_dir, exist_ok=True)
out_path = os.path.join(out_dir, "sql_train.json")
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print(f"已保存: {out_path}")
