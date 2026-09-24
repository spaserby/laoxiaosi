# Redis 本地密码持久化（需管理员权限运行）
# 背景：MSI 注册的 Redis 服务命令行是裸 "--service-run"，不读配置文件，
#       导致 CONFIG SET requirepass 重启即失。本脚本：① conf 写入 requirepass
#       ② 给服务 binPath 补上 conf 参数 ③ 重启服务生效。
$ErrorActionPreference = 'Stop'
$conf = 'D:\Redis\redis.windows-service.conf'

# ① 写入 requirepass 123456（保留被注释的原示例行）
$t = Get-Content $conf -Raw
if ($t -match '(?m)^\s*requirepass\s') {
    $t = $t -replace '(?m)^\s*requirepass.*$', 'requirepass 123456'
} else {
    $t = $t -replace '(?m)^#\s*requirepass\s+foobared', "requirepass 123456`r`n# requirepass foobared"
}
Set-Content -Path $conf -Value $t -Encoding ASCII

# ② 服务命令行补 conf（引号包裹路径防空格）
$binPath = '"D:\Redis\redis-server.exe" --service-run "D:\Redis\redis.windows-service.conf"'
& sc.exe config Redis binPath= $binPath | Out-Null

# ③ 重启服务
& sc.exe stop Redis | Out-Null
Start-Sleep -Seconds 2
& sc.exe start Redis | Out-Null
Start-Sleep -Seconds 2

# 完成标记（供外层非提权进程验证）
'DONE' | Out-File "$env:TEMP\redis-setup-ok.txt" -Encoding ascii
