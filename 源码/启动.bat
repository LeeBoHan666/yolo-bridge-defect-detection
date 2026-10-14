@echo off
cd /d "%~dp0app\src"
echo ================================================
echo   Building project...
echo ================================================
del *.class 2>nul
javac --module-path "..\..\javafx-sdk\javafx-sdk-26.0.1\lib;h2-2.4.240.jar" --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base *.java
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Compilation failed! Check the error above.
    pause
    exit /b 1
)
echo.
echo ================================================
echo   Starting application...
echo   First launch: YOLO warm-up may take ~30 seconds
echo ================================================
echo.
java -Dfile.encoding=UTF-8 --module-path "..\..\javafx-sdk\javafx-sdk-26.0.1\lib;h2-2.4.240.jar" --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base -cp .;h2-2.4.240.jar Main
echo.
echo ================================================
echo   Application closed. Press any key to exit.
echo ================================================
pause
