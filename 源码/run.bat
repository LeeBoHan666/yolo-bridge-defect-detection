chcp 65001 >nul
@echo off
cd /d "%~dp0app\src"
title 桥梁缺陷检测系统

echo ================================================================
echo   桥梁缺陷智能检测系统
echo   YOLO11n + Gemma4-Bridge (Ollama) + JavaFX 26.0.1
echo ================================================================
echo.

:: ---- 检查 Java ----
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java 未安装或未加入 PATH
    echo 请先运行 ..\setup.bat 安装 JDK 26.0.1
    pause
    exit /b 1
)

:: ---- 检查 JavaFX ----
if not exist "..\..\javafx-sdk\javafx-sdk-26.0.1\lib\javafx.base.jar" (
    if exist "..\..\javafx-sdk.zip" (
        echo [>>] 正在解压 JavaFX SDK...
        powershell -Command "Expand-Archive -Path '..\..\javafx-sdk.zip' -DestinationPath '..\..\' -Force"
    ) else (
        echo [ERROR] JavaFX SDK 26.0.1 未找到！
        echo 请从原电脑拷贝 javafx-sdk.zip 到源码目录
        pause
        exit /b 1
    )
)

:: ---- 编译 Java 源码 ----
echo 正在编译 Java 源码...
set JAVAFX_PATH=..\..\javafx-sdk\javafx-sdk-26.0.1\lib
del *.class 2>nul

javac --module-path "%JAVAFX_PATH%;h2-2.4.240.jar" --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base *.java
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] 编译失败！请确认 JDK 26 和 JavaFX 26.0.1 已安装
    echo 运行 ..\setup.bat 自动安装环境
    pause
    exit /b 1
)

echo 编译完成，正在启动...
echo.

:: ---- 启动 ----
java -Dfile.encoding=UTF-8 --module-path "%JAVAFX_PATH%;h2-2.4.240.jar" --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base -cp .;h2-2.4.240.jar Main

echo.
echo 程序已退出，按任意键关闭...
pause
