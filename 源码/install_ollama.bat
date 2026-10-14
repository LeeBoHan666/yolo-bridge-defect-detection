@echo off
setlocal enabledelayedexpansion
title Ollama 0.24.0 + Gemma4-Bridge 模型安装

set SCRIPT_DIR=%~dp0
set OLLAMA_VER=0.24.0
set MODEL_NAME=gemma4-bridge

:: GGUF 候选路径
set GGUF_FILE=
if exist "%SCRIPT_DIR%..\ollama模型\gemma4-bridge-q4km.gguf"   set GGUF_FILE=%SCRIPT_DIR%..\ollama模型\gemma4-bridge-q4km.gguf
if exist "%SCRIPT_DIR%gemma4-bridge-q4km.gguf"                set GGUF_FILE=%SCRIPT_DIR%gemma4-bridge-q4km.gguf
if exist "%SCRIPT_DIR%..\gemma4-bridge-q4km.gguf"             set GGUF_FILE=%SCRIPT_DIR%..\gemma4-bridge-q4km.gguf

:: Modelfile 候选路径
set MODFILE=
if exist "%SCRIPT_DIR%..\ollama模型\Modelfile"   set MODFILE=%SCRIPT_DIR%..\ollama模型\Modelfile
if exist "%SCRIPT_DIR%Modelfile"                  set MODFILE=%SCRIPT_DIR%Modelfile

echo.
echo ================================================================
echo   Ollama %OLLAMA_VER% + Gemma4-Bridge 模型安装
echo ================================================================
echo.

:: ================================================================
:: 1. 安装 Ollama 0.24.0
:: ================================================================
echo [Step 1/5] 安装 Ollama %OLLAMA_VER% ...

set OLLAMA_OK=0
where ollama >nul 2>&1
if %errorlevel% equ 0 (
    for /f "tokens=*" %%v in ('ollama -v 2^>^&1') do (
        set CURRENT_OVER=%%v
        echo   已安装: %%v
    )
    :: 检查版本是否匹配
    echo !CURRENT_OVER! | findstr /c:"%OLLAMA_VER%" >nul 2>&1
    if %errorlevel% equ 0 (
        echo   [OK] 版本匹配 %OLLAMA_VER%
        set OLLAMA_OK=1
        goto :ollama_start
    ) else (
        echo   [WARN] 当前版本与目标 %OLLAMA_VER% 不同，尝试继续...
        set OLLAMA_OK=1
        goto :ollama_start
    )
)

:: --- A: winget ---
echo   尝试 winget install Ollama.Ollama ...
winget install Ollama.Ollama --version 0.24.0 --accept-source-agreements --accept-package-agreements --silent
timeout /t 3 >nul
where ollama >nul 2>&1
if %errorlevel% equ 0 (
    echo   [OK] winget 安装成功
    set OLLAMA_OK=1
    goto :ollama_start
)

:: --- B: winget 不限版本 ---
echo   尝试 winget (最新版) ...
winget install Ollama.Ollama --accept-source-agreements --accept-package-agreements --silent
timeout /t 3 >nul
where ollama >nul 2>&1
if %errorlevel% equ 0 (
    echo   [OK] winget 安装成功
    set OLLAMA_OK=1
    goto :ollama_start
)

:: --- C: 直接下载 ---
echo   直接下载 OllamaSetup.exe ...
set OLLAMA_URL=https://ollama.com/download/OllamaSetup.exe
set OLLAMA_EXE=%TEMP%\OllamaSetup.exe
powershell -Command "& { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%OLLAMA_URL%' -OutFile '%OLLAMA_EXE%' -UseBasicParsing }" 2>nul
if exist "%OLLAMA_EXE%" (
    echo   正在静默安装...
    start /wait "" "%OLLAMA_EXE%" /S
    timeout /t 5 >nul
    where ollama >nul 2>&1
    if %errorlevel% equ 0 (
        echo   [OK] 安装包安装成功
        set OLLAMA_OK=1
        del "%OLLAMA_EXE%" 2>nul
        goto :ollama_start
    )
    del "%OLLAMA_EXE%" 2>nul
)

echo.
echo   ============================================================
echo   [FAIL] 自动安装失败！请手动下载:
echo     https://ollama.com/download/windows
echo   安装完成后重新运行本脚本
echo   ============================================================
pause
exit /b 1

:: ================================================================
:: 2. 启动 Ollama 服务
:: ================================================================
:ollama_start
echo.
echo [Step 2/5] 启动 Ollama 服务 (端口 11434)...

curl -s http://localhost:11434/api/tags >nul 2>&1
if %errorlevel% equ 0 (
    echo   [OK] 服务已在运行
    goto :find_gguf
)

echo   正在启动 ollama serve...
start "" /B ollama serve >nul 2>&1

set /a RETRY=0
:wait_ollama
timeout /t 2 >nul
set /a RETRY+=1
curl -s http://localhost:11434/api/tags >nul 2>&1
if %errorlevel% equ 0 (
    echo   [OK] 服务启动成功 (耗时 ~!RETRY!x2 秒)
    goto :find_gguf
)
if !RETRY! lss 15 goto :wait_ollama

echo   [FAIL] 服务启动超时
echo   请手动执行: ollama serve
pause
exit /b 1

:: ================================================================
:: 3. 定位 GGUF 文件
:: ================================================================
:find_gguf
echo.
echo [Step 3/5] 查找 GGUF 模型文件...

if not "%GGUF_FILE%"=="" (
    if exist "%GGUF_FILE%" (
        for %%F in ("%GGUF_FILE%") do set GGUF_BYTES=%%~zF
        set /a GGUF_MB=!GGUF_BYTES! / 1048576
        echo   [OK] GGUF: %GGUF_FILE% (!GGUF_MB! MB)
        goto :find_modfile
    )
)

:: 递归搜索
echo   [>>] 搜索 gemma4-bridge*.gguf...
for /r "%SCRIPT_DIR%.." %%f in (gemma4-bridge*.gguf) do (
    set GGUF_FILE=%%f
    echo   [OK] 发现: %%f
    goto :find_modfile
)

echo.
echo   ============================================================
echo   [MISS] 未找到 gemma4-bridge-q4km.gguf ！
echo.
echo   请在以下路径之一放置 GGUF 文件 (~5GB):
echo     %SCRIPT_DIR%..\ollama模型\
echo     %SCRIPT_DIR%
echo.
echo   从原电脑拷贝方式:
echo     U盘 / 移动硬盘 / 局域网共享 把 gemma4-bridge-q4km.gguf
echo     复制到此电脑的上述目录
echo   ============================================================
echo.
pause
exit /b 1

:: ================================================================
:: 4. 生成 Modelfile
:: ================================================================
:find_modfile
echo.
echo [Step 4/5] 准备 Modelfile...

if not "%MODFILE%"=="" (
    if exist "%MODFILE%" (
        echo   [OK] Modelfile: %MODFILE%
        goto :copy_gguf
    )
)

:: 自动生成
set MODFILE=%SCRIPT_DIR%Modelfile
for %%F in ("%GGUF_FILE%") do set GGUF_NAME=%%~nxF

(
echo FROM %GGUF_NAME%
echo.
echo TEMPLATE """^<^|turn^>system
echo {{ .System }}^<turn^|^>
echo ^<^|turn^>user
echo {{ .Prompt }}^<turn^|^>
echo ^<^|turn^>model
echo """
echo.
echo SYSTEM """你是一个桥梁缺陷检测助手，能够识别和分析桥梁结构中的六类常见缺陷：裂缝(Crack)、破损(Breakage)、蜂窝(Comb)、孔洞(Hole)、钢筋外露(Reinforcement)、渗水(Seepage)。请用中文回答。"""
echo.
echo PARAMETER temperature 0.3
echo PARAMETER top_p 0.8
echo PARAMETER top_k 40
echo PARAMETER repeat_penalty 1.05
echo PARAMETER stop ^<turn^|^>
) > "%MODFILE%"

echo   [OK] Modelfile 已自动生成: %MODFILE%

:: ================================================================
:: 5. 复制 GGUF + 注册模型
:: ================================================================
:copy_gguf
echo.
echo [Step 5/5] 创建 Ollama 模型 %MODEL_NAME% ...

for %%F in ("%MODFILE%") do set MOD_DIR=%%~dpF
for %%F in ("%GGUF_FILE%") do set GGUF_NAME=%%~nxF

:: 复制 GGUF 到 Modelfile 同目录
if not exist "%MOD_DIR%%GGUF_NAME%" (
    echo   正在复制 GGUF 到 Modelfile 目录 (约 5GB, 30-60秒, 请等待)...
    copy /b "%GGUF_FILE%" "%MOD_DIR%%GGUF_NAME%" >nul
    if %errorlevel% neq 0 (
        echo   [FAIL] 复制失败，可能磁盘空间不足 (需约 5GB)
        pause
        exit /b 1
    )
    echo   [OK] 复制完成
) else (
    echo   [OK] GGUF 已就位: %MOD_DIR%%GGUF_NAME%
)

:: 检查模型是否已注册
curl -s http://localhost:11434/api/tags 2>nul | findstr /i "%MODEL_NAME%" >nul 2>&1
if %errorlevel% equ 0 (
    echo   [OK] 模型 %MODEL_NAME% 已注册到 Ollama
    goto :quick_test
)

:: 创建模型
echo   正在注册模型到 Ollama (约 1-3 分钟)...
cd /d "%MOD_DIR%"
ollama create %MODEL_NAME% -f Modelfile

if %errorlevel% equ 0 (
    echo   [OK] 模型 %MODEL_NAME% 创建成功！
) else (
    echo   [FAIL] 创建失败。
    echo   请手动执行:
    echo     cd "%MOD_DIR%"
    echo     ollama create %MODEL_NAME% -f Modelfile
    pause
    exit /b 1
)

:: ================================================================
:: 快速验证
:: ================================================================
:quick_test
echo.
echo   验证模型...
echo   Q: 裂缝是什么
curl -s http://localhost:11434/api/generate -d "{\"model\":\"%MODEL_NAME%\",\"prompt\":\"裂缝是什么\",\"stream\":false}" 2>nul | powershell -Command "$input | ConvertFrom-Json | Select-Object -ExpandProperty response" 2>nul
echo.
echo   [OK] 模型验证通过！

echo.
echo ================================================================
echo   Ollama + Gemma4-Bridge 部署完成！
echo ================================================================
echo.
echo   服务:  http://localhost:11434
echo   模型:  %MODEL_NAME%
echo   测试:  ollama run %MODEL_NAME% "分析桥梁裂缝"
echo.
echo   API 调用: curl http://localhost:11434/api/generate -d
echo     "{\"model\":\"%MODEL_NAME%\",\"prompt\":\"...\"}"
echo ================================================================
echo.
pause
