# JavaFX 桌面端源码

本目录包含 JavaFX 桌面应用和由 Java 调用的 Python 脚本。项目根目录的[使用说明](../../../使用说明.md)包含启动步骤。

## 主要文件

| 文件 | 作用 |
|---|---|
| Main.java | JavaFX 程序入口 |
| FX.java | 主界面和页面导航 |
| YoloDetector.java | 启动 Python 推理脚本并读取检测结果 |
| yolo_infer.py | 使用 Ultralytics 执行单图推理；当前固定 CPU 和 1280 输入尺寸 |
| OllamaChat.java、llm_backend.py | Ollama 对话和图片分析调用 |
| DBManager.java | H2 嵌入式数据库操作 |
| CameraScene.java | 网络摄像头界面 |
| SettingsScene.java | 应用设置、用户管理及相关页面 |

JavaFX 默认检测权重为本目录下的 best.pt。运行时输出和配置保存在 aioutput/，H2 数据库在运行后生成。请勿将个人图片、聊天记录、数据库或密钥作为公开示例提交。