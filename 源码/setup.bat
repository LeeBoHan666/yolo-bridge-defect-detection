chcp 65001 >nul
@echo off
setlocal enabledelayedexpansion
title 桥梁缺陷检测系统 - 完整环境安装

set SCRIPT_DIR=%~dp0

echo.
echo ================================================================
echo     桥梁缺陷智能检测系统 — 完整环境安装
echo ================================================================
echo.
echo   目标版本（与本机开发环境一致）:
echo     Java JDK:  26.0.1 (Oracle)
echo     Python:    3.13.13
echo     CUDA:      13.2
echo     Ollama:    0.24.0
echo     PyTorch:   2.12.0
echo     JavaFX:    26.0.1
echo ================================================================
echo.
echo   本脚本将自动完成:
echo     [1/5] Java JDK 26.0.1
echo     [2/5] Python 3.13.13 + 依赖库
echo     [3/5] CUDA 环境检测
echo     [4/5] Ollama 0.24.0 + 模型部署
echo     [5/5] JavaFX 26.0.1 + H2 组件解压
echo ================================================================
echo.

:: 管理员权限提示
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [!] 如安装失败，请右键 - 以管理员身份运行
    echo.
)

:: ================================================================
:: [1] Java JDK 26.0.1 — Oracle 官方版
:: ================================================================
echo ================================================================
echo [1/5] 安装 Java JDK 26.0.1 (Oracle)
echo ================================================================
echo.

set JDK_READY=0

where java >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=3 delims= " %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JVER=%%v
    set JVER=!JVER:"=!
    echo   已安装: Java !JVER!

    for /f "tokens=1 delims=." %%a in ("!JVER!") do set JMAJOR=%%a
    if !JMAJOR! geq 26 (
        set JDK_READY=1
        echo   [OK] JDK !JMAJOR! ^>= 26，满足要求
        goto :jdk_done
    ) else (
        echo   [WARN] JDK 版本过旧 (!JMAJOR!)，需要 26+，开始升级...
    )
) else (
    echo   未检测到 Java，开始安装 JDK 26.0.1...
)

:: 尝试 winget 安装 JDK 26
winget install Oracle.JDK.26 --accept-source-agreements --accept-package-agreements --silent
if %errorlevel% equ 0 (
    echo   [OK] JDK 26 安装成功
    echo   [!] 安装后请重启终端使 JAVA_HOME 和 PATH 生效
    set JDK_READY=1
    goto :jdk_done
)

:: winget 备选: Oracle.Java.26
winget install Oracle.Java.26 --accept-source-agreements --accept-package-agreements --silent
if %errorlevel% equ 0 (
    echo   [OK] JDK 26 安装成功
    set JDK_READY=1
    goto :jdk_done
)

:: winget 再备选: Temurin 26
winget install EclipseAdoptium.Temurin.26.JDK --accept-source-agreements --accept-package-agreements --silent
if %errorlevel% equ 0 (
    echo   [OK] JDK 26 (Temurin) 安装成功
    set JDK_READY=1
    goto :jdk_done
)

:: 全部失败 → 手动下载
echo.
echo   ============================================================
echo   [FAIL] 自动安装失败！请手动下载 JDK 26.0.1:
echo     Oracle: https://www.oracle.com/java/technologies/downloads/
echo             选择 JDK 26 ^> Windows x64 Installer
echo     Temurin: https://adoptium.net/download/
echo             选择 Version 26 ^> x64 ^> JDK
echo     安装后重启终端，重新运行 setup.bat
echo   ============================================================
echo.
pause
exit /b 1

:jdk_done
echo.

:: ================================================================
:: [2] Python 3.13.13 + 依赖库
:: ================================================================
echo ================================================================
echo [2/5] 安装 Python 3.13.13 + 全部依赖库
echo ================================================================
echo.

set PY_READY=0

where python >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=2 delims= " %%v in ('python --version 2^>^&1') do set PYVER=%%v
    echo   已安装: Python !PYVER!

    for /f "tokens=1-2 delims=." %%a in ("!PYVER!") do (
        set PYMAJOR=%%a
        set PYMINOR=%%b
    )
    :: 3.10 ~ 3.13 都兼容
    if !PYMAJOR! equ 3 if !PYMINOR! geq 10 if !PYMINOR! leq 13 (
        set PY_READY=1
        echo   [OK] Python !PYVER! 兼容
        goto :py_install_pkgs
    )
    echo   [WARN] Python !PYVER! 可能有问题，尝试继续...
    set PY_READY=1
    goto :py_install_pkgs
)

:: 未安装 → winget
echo   正在安装 Python 3.13...
winget install Python.Python.3.13 --accept-source-agreements --accept-package-agreements --silent
if %errorlevel% equ 0 (
    echo   [OK] Python 3.13 安装成功
    echo.
    echo   ============================================================
    echo   [!] 请关闭此窗口，重新双击 setup.bat 继续安装依赖库
    echo       (Python 环境变量在重启终端后才会生效)
    echo   ============================================================
    pause
    exit /b 0
) else (
    echo   ============================================================
    echo   [FAIL] 自动安装失败！请手动下载:
    echo     https://www.python.org/downloads/release/python-31313/
    echo     安装时务必勾选 "Add Python to PATH"
    echo     安装后重启终端，重新运行 setup.bat
    echo   ============================================================
    pause
    exit /b 1
)

:py_install_pkgs
echo.
echo   [>>] 开始安装 Python 依赖 (首次约 3-8 分钟)...

:: 确定 pip
where pip >nul 2>&1
if %errorlevel% neq 0 (set PIPCMD=python -m pip) else (set PIPCMD=pip)

:: 升级 pip 到 26.1.1
echo   1/4 升级 pip...
%PIPCMD% install --upgrade pip==26.1.1 -i https://pypi.tuna.tsinghua.edu.cn/simple --trusted-host pypi.tuna.tsinghua.edu.cn >nul 2>&1

:: PyTorch 2.12.0 (CUDA 13.2 版)
echo   2/4 安装 PyTorch 2.12.0 + torchvision 0.27.0 (~3GB)...
%PIPCMD% install torch==2.12.0 torchvision==0.27.0 --index-url https://download.pytorch.org/whl/cu132
if %errorlevel% neq 0 (
    echo   [RETRY] PyTorch 官方源失败，尝试镜像...
    %PIPCMD% install torch==2.12.0 torchvision==0.27.0 -i https://pypi.tuna.tsinghua.edu.cn/simple --trusted-host pypi.tuna.tsinghua.edu.cn
)

:: 核心依赖 (与开发环境完全一致)
echo   3/4 安装核心检测依赖...
%PIPCMD% install ultralytics==8.4.54 opencv-python==4.13.0.92 Pillow==12.2.0 requests==2.34.2 numpy==2.4.6 -i https://pypi.tuna.tsinghua.edu.cn/simple --trusted-host pypi.tuna.tsinghua.edu.cn
if %errorlevel% neq 0 (
    %PIPCMD% install ultralytics==8.4.54 opencv-python==4.13.0.92 Pillow==12.2.0 requests==2.34.2 numpy==2.4.6
)

:: QLoRA 训练依赖 (如需在新电脑也训练)
echo   4/4 安装训练依赖 (QLoRA 微调用)...
%PIPCMD% install transformers==5.5.0 peft==0.19.1 bitsandbytes==0.49.2 accelerate==1.13.0 datasets==4.3.0 -i https://pypi.tuna.tsinghua.edu.cn/simple --trusted-host pypi.tuna.tsinghua.edu.cn
if %errorlevel% neq 0 (
    %PIPCMD% install transformers==5.5.0 peft==0.19.1 bitsandbytes==0.49.2 accelerate==1.13.0 datasets==4.3.0
)

echo.
echo   [OK] 全部 Python 依赖安装完成
echo.

:: ================================================================
:: [3] CUDA 环境检测
:: ================================================================
echo ================================================================
echo [3/5] 检测 CUDA 环境
echo ================================================================
echo.

nvidia-smi >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=*" %%v in ('nvidia-smi --query-gpu=name,driver_version --format=csv,noheader 2^>^&1') do echo   GPU: %%v
) else (
    echo   [WARN] 未检测到 NVIDIA 显卡或驱动
    echo   YOLO 推理将使用 CPU 模式 (速度较慢)
)

nvcc --version >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=5 delims= " %%v in ('nvcc --version 2^>^&1 ^| findstr /i "release"') do (
        set CUDA_VER=%%v
        set CUDA_VER=!CUDA_VER:,=!
    )
    echo   CUDA: !CUDA_VER!
) else (
    echo   CUDA Toolkit: 未独立安装 (PyTorch 自带 CUDA 运行时，不影响使用)
)
echo.

:: ================================================================
:: [4] Ollama 0.24.0 + 模型
:: ================================================================
echo ================================================================
echo [4/5] 安装 Ollama 0.24.0 + 部署模型
echo ================================================================
echo.

if exist "%SCRIPT_DIR%install_ollama.bat" (
    echo   [>>] 调用 Ollama 安装脚本...
    call "%SCRIPT_DIR%install_ollama.bat"
) else (
    echo   [WARN] 未找到 install_ollama.bat，跳过 Ollama 安装
    echo   可单独运行 install_ollama.bat 完成模型部署
)
echo.

:: ================================================================
:: [5] JavaFX 26.0.1 + H2 + YOLO 模型 检查
:: ================================================================
echo ================================================================
echo [5/5] 检查 Java 组件
echo ================================================================
echo.

:: JavaFX 26.0.1
if exist "%SCRIPT_DIR%javafx-sdk\javafx-sdk-26.0.1\lib\javafx.base.jar" (
    echo   [OK] JavaFX SDK 26.0.1
) else if exist "%SCRIPT_DIR%javafx-sdk.zip" (
    echo   [>>] 解压 JavaFX SDK 26.0.1...
    powershell -Command "Expand-Archive -Path '%SCRIPT_DIR%javafx-sdk.zip' -DestinationPath '%SCRIPT_DIR%' -Force" >nul 2>&1
    if exist "%SCRIPT_DIR%javafx-sdk\javafx-sdk-26.0.1\lib\javafx.base.jar" (
        echo   [OK] JavaFX SDK 26.0.1 解压完成
    ) else (
        echo   [FAIL] 解压失败，请手动解压 javafx-sdk.zip
    )
) else (
    echo   [MISS] javafx-sdk.zip — 请从原电脑拷贝
)

:: H2 数据库
if exist "%SCRIPT_DIR%app\src\h2-2.4.240.jar" (
    echo   [OK] H2 Database 2.4.240
) else (
    echo   [MISS] h2-2.4.240.jar — 请从原电脑拷贝
)

:: YOLO best.pt
if exist "%SCRIPT_DIR%..\3090-4\train-49\weights\best.pt" (
    echo   [OK] YOLO best.pt (train-49)
) else if exist "%SCRIPT_DIR%models\best.pt" (
    echo   [OK] YOLO best.pt
) else if exist "%SCRIPT_DIR%app\src\best.pt" (
    echo   [OK] YOLO best.pt
) else (
    echo   [MISS] best.pt — 请从原电脑拷贝
)

:: 核心脚本
if exist "%SCRIPT_DIR%detect.py"        (echo   [OK] detect.py)       else (echo   [MISS] detect.py)
if exist "%SCRIPT_DIR%app.py"           (echo   [OK] app.py)          else (echo   [MISS] app.py)
if exist "%SCRIPT_DIR%llm\ollama_chat.py"     (echo   [OK] llm/ollama_chat.py)    else (echo   [MISS] llm/ollama_chat.py)
if exist "%SCRIPT_DIR%llm\bridge_standards.py"(echo   [OK] llm/bridge_standards.py) else (echo   [MISS] llm/bridge_standards.py)
if exist "%SCRIPT_DIR%app\src\yolo_infer.py"  (echo   [OK] app/src/yolo_infer.py) else (echo   [MISS] app/src/yolo_infer.py)
if exist "%SCRIPT_DIR%app\src\llm_backend.py" (echo   [OK] app/src/llm_backend.py) else (echo   [MISS] app/src/llm_backend.py)

echo.

:: ================================================================
:: 完成
:: ================================================================
echo ================================================================
echo   全部安装完成！
echo ================================================================
echo.
echo   版本对齐确认:
echo     Java:   26.0.1  (Oracle)
echo     Python: 3.13.13
echo     CUDA:   13.2
echo     Ollama: 0.24.0
echo     PyTorch:2.12.0
echo.
echo   启动方式:
echo     [GUI]   双击 run.bat
echo     [命令行] python app.py --image 图片路径
echo     [测试]   python detect.py 图片路径
echo.
echo   首次 YOLO 加载约需 10-20 秒，请耐心等待
echo ================================================================
echo.
pause
