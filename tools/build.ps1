$env:JAVA_HOME = "C:\Users\e\AppData\Local\Android\Tools\jdk-21.0.12.1+1"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
Set-Location "C:\Users\e\Doubao\chats\2026-10-05\new-chat\SimpleCloudPlayer"

# 验证 SDK 目录
$lines = @()
$lines += "platforms: " + ((Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\platforms" -ErrorAction SilentlyContinue).Name -join ',')
$lines += "build-tools: " + ((Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -ErrorAction SilentlyContinue).Name -join ',')
$lines += "platform-tools: " + (Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
$lines | Out-File "C:\Users\e\AppData\Local\Android\sdkcheck.txt" -Encoding utf8

# 首次构建（下载依赖）
& "C:\Users\e\AppData\Local\Android\Tools\gradle-8.7\bin\gradle.bat" assembleDebug --no-daemon --console=plain 2>&1 | Out-File "C:\Users\e\AppData\Local\Android\build1.log" -Encoding utf8
Write-Output "build exit: $LASTEXITCODE"
Get-Content "C:\Users\e\AppData\Local\Android\build1.log" -Tail 40
