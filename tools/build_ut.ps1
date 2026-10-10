$env:JAVA_HOME = "C:\Users\e\AppData\Local\Android\Tools\jdk-21.0.12.1+1"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
Set-Location "C:\Users\e\Doubao\chats\2026-10-05\new-chat\SimpleCloudPlayer"
& "C:\Users\e\AppData\Local\Android\Tools\gradle-8.7\bin\gradle.bat" testDebugUnitTest --no-daemon --console=plain 2>&1 | Out-File "C:\Users\e\AppData\Local\Android\ut.log" -Encoding utf8
Write-Output "ut exit: $LASTEXITCODE"
Get-Content "C:\Users\e\AppData\Local\Android\ut.log" -Tail 40
