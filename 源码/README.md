# 源码目录说明

完整项目介绍和运行步骤请从项目根目录查看：

- [项目首页](../README.md)
- [项目介绍](../项目介绍.md)
- [使用说明](../使用说明.md)

## 主要入口

- app/src/Main.java：JavaFX 桌面程序入口。
- app/src/YoloDetector.java 与 app/src/yolo_infer.py：桌面端检测调用链。
- app.py 与 detect.py：独立 Python 命令行检测流程。
- llm/：命令行端 Ollama 调用和提示词相关代码。
- requirements.txt：Python 依赖清单。
- run.bat：Windows JavaFX 桌面端启动脚本。

桌面端和命令行端的模型默认路径不同。具体说明见项目根目录的《使用说明》。