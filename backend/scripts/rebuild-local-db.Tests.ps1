<#
.SYNOPSIS
    rebuild-local-db.ps1 的回归测试（Windows 本地开发环境）

.DESCRIPTION
    通过 PATH 影子方式用假 mysql 客户端替换真实 mysql.exe，在不连接任何真实数据库的
    前提下验证 rebuild-local-db.ps1 的行为。假客户端会把收到的命令行原样记录到日志，
    测试据此断言实际下发的 SQL。

    覆盖：
      * 各类校验失败（非 MySQL、连接失败、端口不符、无法确认本机、核对库失败）→ 立即终止且不删除
      * 重建命令自身失败 → 立即终止
      * 确认未通过 → 不删除数据库
      * 目标数据库不可通过参数指定
      * 正常重建路径的 SQL 形态（反引号转义、字符集、固定目标）
      * 密码不泄露到输出与日志

    已知限制（重要）：
      真实脚本的删除确认用 Read-Host 从 stdin 读取。无人值守环境下无法把 YES 稳定送进
      这个提示——假客户端派生的 cmd.exe 会先吃掉继承的 stdin。因此"确认通过之后"的场景
      改用一份仅把确认行替换为自动确认的副本来执行；副本与正式脚本的差异会被逐行断言，
      确保只有这一行不同。删除确认交互本身未在无人值守环境下验证。

    整个测试过程不会连接真实 MySQL，也不会删除任何真实数据库。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File backend\scripts\rebuild-local-db.Tests.ps1
    pwsh -NoProfile -ExecutionPolicy Bypass -File backend\scripts\rebuild-local-db.Tests.ps1

.NOTES
    退出码 0 表示全部通过，1 表示存在失败用例。
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$script:TestsRoot = $PSScriptRoot
$script:ScriptUnderTest = Join-Path $TestsRoot 'rebuild-local-db.ps1'
$script:StubDir = Join-Path ([System.IO.Path]::GetTempPath()) ("campushub-stub-" + [Guid]::NewGuid().ToString('N'))
$script:PowerShellHost = 'powershell.exe'
$script:OriginalPath = $env:PATH

$script:Passed = 0
$script:Failed = 0
$script:Failures = New-Object System.Collections.Generic.List[string]

function Write-TestHead {
    param([string]$Text)
    Write-Host "`n=== $Text ===" -ForegroundColor Cyan
}

function Assert-That {
    param(
        [string]$Name,
        $Condition,
        [string]$Detail = ''
    )
    # 条件表达式必须收敛为单个布尔值；否则说明断言本身写错了，按失败处理并给出线索
    $ok = $false
    $typeNote = ''
    if ($Condition -is [bool]) {
        $ok = $Condition
    } else {
        $typeNote = " [condition type: $($Condition.GetType().FullName)]"
    }
    if ($ok) {
        $script:Passed++
        Write-Host "  [PASS] $Name" -ForegroundColor Green
    } else {
        $script:Failed++
        $script:Failures.Add("$Name$(if ($Detail) { " -- $Detail" })$typeNote")
        Write-Host "  [FAIL] $Name$(if ($Detail) { " -- $Detail" })$typeNote" -ForegroundColor Red
    }
}

# ------------------------------------------------------------------
# 假 mysql 客户端
#   mysql.cmd  —— 原样记录命令行后转交 mysql.ps1（< NUL 避免抢占被测脚本的 stdin）
#   mysql.ps1  —— 按 CAMPUSHUB_STUB_* 环境变量应答
# ------------------------------------------------------------------
function New-MySqlStub {
    param([string]$Directory)

    New-Item -ItemType Directory -Path $Directory -Force | Out-Null

    # 两个文件都保持纯 ASCII，避免 PS 5.1 无 BOM 时的编码问题
    $cmd = @'
@echo off
if defined CAMPUSHUB_STUB_RAW_LOG >>"%CAMPUSHUB_STUB_RAW_LOG%" echo %*
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dpn0.ps1" %* < NUL
'@
    Set-Content -LiteralPath (Join-Path $Directory 'mysql.cmd') -Value $cmd -Encoding ASCII

    # 刻意不用 param 块：否则 -e 会被 PowerShell 当成命名参数而绑定失败，
    # 所有参数都会落在自动变量 $args 中
    $ps1 = @'
$log = $env:CAMPUSHUB_STUB_LOG
if ($log) {
    Add-Content -LiteralPath $log -Value ("ARGS: " + ($args -join ' '))
}

$sql = ''
for ($i = 0; $i -lt ($args.Count - 1); $i++) {
    if ($args[$i] -eq '-e') { $sql = $args[$i + 1] }
}

$failPattern = $env:CAMPUSHUB_STUB_FAIL_PATTERN
if ($failPattern -and $sql -match $failPattern) {
    [Console]::Error.WriteLine("stub mysql: simulated failure for SQL: $sql")
    exit 1
}

switch -Regex ($sql) {
    '^\s*SELECT VERSION\(\)' {
        if ($env:CAMPUSHUB_STUB_VERSION) { $env:CAMPUSHUB_STUB_VERSION } else { '8.0.36' }
        exit 0
    }
    '@@port' {
        $port = if ($env:CAMPUSHUB_STUB_PORT) { $env:CAMPUSHUB_STUB_PORT } else { '3306' }
        $hostname = if ($env:CAMPUSHUB_STUB_HOSTNAME) { $env:CAMPUSHUB_STUB_HOSTNAME } else { $env:COMPUTERNAME }
        $bind = if ($env:CAMPUSHUB_STUB_BIND_ADDRESS) { $env:CAMPUSHUB_STUB_BIND_ADDRESS } else { '*' }
        $comment = if ($env:CAMPUSHUB_STUB_VERSION_COMMENT) { $env:CAMPUSHUB_STUB_VERSION_COMMENT } else { 'MySQL Community Server - GPL' }
        "$port`t$hostname`t$bind`t$comment"
        exit 0
    }
    'information_schema\.SCHEMATA' {
        # 已执行过 CREATE DATABASE 之后视为库已存在，供重建后的验证查询使用
        $marker = $env:CAMPUSHUB_STUB_STATE_FILE
        if ($marker -and (Test-Path -LiteralPath $marker)) { 'campushub'; exit 0 }
        if ($env:CAMPUSHUB_STUB_DB_EXISTS -eq '0') { exit 0 }
        'campushub'
        exit 0
    }
    'information_schema\.TABLES' {
        if ($env:CAMPUSHUB_STUB_TABLE_COUNT) { $env:CAMPUSHUB_STUB_TABLE_COUNT } else { '12' }
        exit 0
    }
    default {
        $marker = $env:CAMPUSHUB_STUB_STATE_FILE
        if ($marker -and $sql -match 'CREATE DATABASE') {
            Set-Content -LiteralPath $marker -Value 'created' -ErrorAction SilentlyContinue
        }
        exit 0
    }
}
'@
    Set-Content -LiteralPath (Join-Path $Directory 'mysql.ps1') -Value $ps1 -Encoding ASCII
}

function Assert-StubIsResolved {
    <# 安全闸：任何可能触达 DROP 的场景之前都必须确认 mysql 解析到测试桩 #>
    $resolved = (Get-Command mysql -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1).Source
    Assert-That -Name 'mysql 仍解析到测试桩（未误连真实客户端）' -Condition ($resolved -like "$($script:StubDir)*") -Detail "resolved=$resolved"
    return ($resolved -like "$($script:StubDir)*")
}

function Invoke-RebuildScript {
    param(
        [hashtable]$StubEnv = @{},
        [string]$Confirmation = '',
        [string[]]$Arguments = @(),
        [string]$Password = 'SuperSecretLocalPwd999',
        [string]$ScriptPath = $script:ScriptUnderTest
    )

    $stdinFile = Join-Path $script:StubDir 'stdin.txt'
    $stdoutFile = Join-Path $script:StubDir 'stdout.txt'
    $stderrFile = Join-Path $script:StubDir 'stderr.txt'
    Set-Content -LiteralPath $stdinFile -Value "$Confirmation`n" -Encoding ASCII -NoNewline
    Remove-Item -LiteralPath $stdoutFile, $stderrFile -ErrorAction SilentlyContinue

    $saved = @{}
    foreach ($key in $StubEnv.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        Set-Item -Path ("Env:" + $key) -Value $StubEnv[$key]
    }

    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath)
    if ($Password) { $argList += @('-DbPassword', $Password) }
    $argList += $Arguments

    $exitCode = -1
    try {
        $proc = Start-Process -FilePath $script:PowerShellHost -ArgumentList $argList `
            -NoNewWindow -Wait -PassThru `
            -RedirectStandardInput $stdinFile `
            -RedirectStandardOutput $stdoutFile `
            -RedirectStandardError $stderrFile
        $exitCode = $proc.ExitCode
    } finally {
        foreach ($key in $StubEnv.Keys) {
            if ($null -eq $saved[$key]) {
                Remove-Item -Path ("Env:" + $key) -ErrorAction SilentlyContinue
            } else {
                Set-Item -Path ("Env:" + $key) -Value $saved[$key]
            }
        }
    }

    $stdout = if (Test-Path -LiteralPath $stdoutFile) { Get-Content -LiteralPath $stdoutFile -Raw } else { '' }
    $stderr = if (Test-Path -LiteralPath $stderrFile) { Get-Content -LiteralPath $stderrFile -Raw } else { '' }

    return [pscustomobject]@{
        ExitCode = $exitCode
        StdOut   = $stdout
        StdErr   = $stderr
        Combined = "$stdout`n$stderr"
    }
}

function Get-StubRawLog {
    $path = [Environment]::GetEnvironmentVariable('CAMPUSHUB_STUB_RAW_LOG', 'Process')
    if (-not $path -or -not (Test-Path -LiteralPath $path)) { return '' }
    return (Get-Content -LiteralPath $path -Raw)
}

function Reset-StubLogs {
    foreach ($name in @('CAMPUSHUB_STUB_RAW_LOG', 'CAMPUSHUB_STUB_LOG', 'CAMPUSHUB_STUB_STATE_FILE')) {
        $path = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ($path -and (Test-Path -LiteralPath $path)) {
            Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue
        }
    }
}

function New-AutoConfirmCopy {
    <#
    .SYNOPSIS
        生成一份只把删除确认替换成自动确认的脚本副本。
    .DESCRIPTION
        正式脚本用 Read-Host 从 stdin 读确认，无人值守环境下送不进去（假客户端派生的
        cmd.exe 会先吃掉继承的 stdin）。用这个仅差一行的副本验证"确认通过之后"的重建路径。
        调用方必须断言副本与正式脚本只有这一行不同。
    #>
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $source = [System.IO.File]::ReadAllText($script:ScriptUnderTest, [System.Text.Encoding]::UTF8)
    $pattern = '(?m)^(\s*)\$confirmation = Read-Host .*$'
    if ($source -notmatch $pattern) {
        throw '未在正式脚本中找到删除确认行，无法生成自动确认副本'
    }
    $copyText = [regex]::Replace($source, $pattern, '$1$confirmation = ''YES'' # TEST-AUTO-CONFIRM')

    $copyPath = Join-Path $script:StubDir 'rebuild-local-db.autoconfirm.ps1'
    [System.IO.File]::WriteAllText($copyPath, $copyText, (New-Object System.Text.UTF8Encoding($true)))

    # 逐行比对：只允许确认行不同
    $srcLines = [System.IO.File]::ReadAllLines($script:ScriptUnderTest, [System.Text.Encoding]::UTF8)
    $copyLines = [System.IO.File]::ReadAllLines($copyPath, [System.Text.Encoding]::UTF8)
    $diffCount = 0
    if ($srcLines.Count -eq $copyLines.Count) {
        for ($i = 0; $i -lt $srcLines.Count; $i++) {
            if ($srcLines[$i] -ne $copyLines[$i]) { $diffCount++ }
        }
    } else {
        $diffCount = -1
    }
    Assert-That -Name '自动确认副本与正式脚本仅差确认行' -Condition ($diffCount -eq 1) -Detail "diffLines=$diffCount"
    Assert-That -Name '自动确认副本不含删除确认提示' -Condition ($copyText -notmatch '\$confirmation = Read-Host')
    return $copyPath
}

try {
    if (-not (Test-Path -LiteralPath $script:ScriptUnderTest)) {
        throw "找不到被测脚本: $($script:ScriptUnderTest)"
    }

    Write-TestHead '准备假 mysql 客户端'
    New-MySqlStub -Directory $script:StubDir
    $env:PATH = "$($script:StubDir);$env:PATH"
    Assert-StubIsResolved | Out-Null

    $env:CAMPUSHUB_STUB_RAW_LOG = Join-Path $script:StubDir 'raw.log'
    $env:CAMPUSHUB_STUB_LOG = Join-Path $script:StubDir 'parsed.log'
    $env:CAMPUSHUB_STUB_STATE_FILE = Join-Path $script:StubDir 'db-state.marker'

    # ------------------------------------------------------------
    Write-TestHead '校验失败：服务端不是 MySQL'
    Reset-StubLogs
    $r = Invoke-RebuildScript -StubEnv @{
        CAMPUSHUB_STUB_VERSION         = 'PostgreSQL 16.1'
        CAMPUSHUB_STUB_VERSION_COMMENT = 'PostgreSQL 16.1'
    } -Confirmation 'YES'
    Assert-That -Name '退出码非零' -Condition ($r.ExitCode -ne 0) -Detail "exit=$($r.ExitCode)"
    Assert-That -Name '输出包含 [ERROR]' -Condition ($r.Combined -match '\[ERROR\]')
    Assert-That -Name '未执行任何 DROP DATABASE' -Condition ((Get-StubRawLog) -notmatch 'DROP DATABASE')

    # ------------------------------------------------------------
    Write-TestHead '校验失败：连接被拒绝（mysql 退出码非零）'
    Reset-StubLogs
    $r = Invoke-RebuildScript -StubEnv @{ CAMPUSHUB_STUB_FAIL_PATTERN = 'SELECT VERSION' } -Confirmation 'YES'
    Assert-That -Name '退出码非零' -Condition ($r.ExitCode -ne 0) -Detail "exit=$($r.ExitCode)"
    Assert-That -Name '输出包含 [ERROR]' -Condition ($r.Combined -match '\[ERROR\]')
    Assert-That -Name '未执行任何 DROP DATABASE' -Condition ((Get-StubRawLog) -notmatch 'DROP DATABASE')

    # ------------------------------------------------------------
    Write-TestHead '校验失败：服务端端口与连接目标不一致'
    Reset-StubLogs
    $r = Invoke-RebuildScript -StubEnv @{ CAMPUSHUB_STUB_PORT = '3307' } -Confirmation 'YES'
    Assert-That -Name '退出码非零' -Condition ($r.ExitCode -ne 0) -Detail "exit=$($r.ExitCode)"
    Assert-That -Name '输出包含 [ERROR]' -Condition ($r.Combined -match '\[ERROR\]')
    Assert-That -Name '未执行任何 DROP DATABASE' -Condition ((Get-StubRawLog) -notmatch 'DROP DATABASE')

    # ------------------------------------------------------------
    Write-TestHead '校验失败：无法确认服务端运行在本机'
    Reset-StubLogs
    $r = Invoke-RebuildScript -StubEnv @{
        CAMPUSHUB_STUB_HOSTNAME     = 'db-prod-01'
        CAMPUSHUB_STUB_BIND_ADDRESS = '10.0.0.5'
    } -Confirmation 'YES'
    Assert-That -Name '退出码非零' -Condition ($r.ExitCode -ne 0) -Detail "exit=$($r.ExitCode)"
    Assert-That -Name '输出包含 [ERROR]' -Condition ($r.Combined -match '\[ERROR\]')
    Assert-That -Name '未执行任何 DROP DATABASE' -Condition ((Get-StubRawLog) -notmatch 'DROP DATABASE')

    # ------------------------------------------------------------
    Write-TestHead '校验失败：核对目标数据库阶段查询失败'
    Reset-StubLogs
    $r = Invoke-RebuildScript -StubEnv @{ CAMPUSHUB_STUB_FAIL_PATTERN = 'information_schema\.SCHEMATA' } -Confirmation 'YES'
    Assert-That -Name '退出码非零' -Condition ($r.ExitCode -ne 0) -Detail "exit=$($r.ExitCode)"
    Assert-That -Name '输出包含 [ERROR]' -Condition ($r.Combined -match '\[ERROR\]')
    Assert-That -Name '未执行任何 DROP DATABASE' -Condition ((Get-StubRawLog) -notmatch 'DROP DATABASE')

    # ------------------------------------------------------------
    Write-TestHead '确认未通过：不删除数据库'
    Reset-StubLogs
    $r = Invoke-RebuildScript -Confirmation 'no'
    Assert-That -Name '退出码为 0（正常取消，非错误）' -Condition ($r.ExitCode -eq 0) -Detail "exit=$($r.ExitCode)"
    Assert-That -Name '未执行任何 DROP DATABASE' -Condition ((Get-StubRawLog) -notmatch 'DROP DATABASE')
    Assert-That -Name '未出现 [ERROR]' -Condition (-not ($r.Combined -match '\[ERROR\]'))

    # ------------------------------------------------------------
    Write-TestHead '目标不可通过参数指定'
    Reset-StubLogs
    $r = Invoke-RebuildScript -Arguments @('-DbName', 'some_other_db') -Confirmation 'YES'
    Assert-That -Name '传入 -DbName 会被参数绑定拒绝（退出码非零）' -Condition ($r.ExitCode -ne 0) -Detail "exit=$($r.ExitCode)"
    Assert-That -Name '脚本体未执行，未触达 mysql' -Condition ((Get-StubRawLog) -eq '')

    # ------------------------------------------------------------
    # 以下场景会真正走到 DROP DATABASE，全部使用假客户端 + 自动确认副本
    Write-TestHead '正常重建：校验通过且确认通过'
    $autoConfirm = New-AutoConfirmCopy
    Reset-StubLogs
    $r = Invoke-RebuildScript -ScriptPath $autoConfirm
    Assert-That -Name '退出码为 0' -Condition ($r.ExitCode -eq 0) -Detail "exit=$($r.ExitCode); out=$($r.StdOut)"
    $raw = Get-StubRawLog
    Assert-That -Name '恰好执行一次 DROP DATABASE' -Condition ([regex]::Matches($raw, 'DROP DATABASE').Count -eq 1) -Detail "raw=$raw"
    Assert-That -Name '恰好执行一次 CREATE DATABASE' -Condition ([regex]::Matches($raw, 'CREATE DATABASE').Count -eq 1)
    Assert-That -Name 'SQL 用反引号包裹标识符（PowerShell `` 转义正确）' -Condition ($raw -match 'DROP DATABASE IF EXISTS `campushub`')
    Assert-That -Name 'SQL 未用双引号包裹标识符' -Condition ($raw -notmatch '"campushub"')
    Assert-That -Name '建库语句指定 utf8mb4 / utf8mb4_unicode_ci' -Condition ($raw -match 'DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
    Assert-That -Name '连接目标固定为 127.0.0.1:3306' -Condition ($raw -match '-h 127\.0\.0\.1 -P 3306')
    Assert-That -Name '重建后执行了结果验证查询' -Condition (([regex]::Matches($raw, 'information_schema\.SCHEMATA').Count) -ge 2)

    # ------------------------------------------------------------
    Write-TestHead '密码不泄露到输出与日志'
    $leakProbe = 'SuperSecretLocalPwd999'
    Assert-That -Name 'stdout/stderr 不含密码' -Condition (-not ($r.Combined.Contains($leakProbe))) -Detail "combined=$($r.Combined)"
    Assert-That -Name '下发到 mysql 的命令行不含密码' -Condition ((Get-StubRawLog) -notmatch [regex]::Escape($leakProbe))

    # ------------------------------------------------------------
    Write-TestHead '重建命令自身执行失败'
    Reset-StubLogs
    $r = Invoke-RebuildScript -ScriptPath $autoConfirm -StubEnv @{ CAMPUSHUB_STUB_FAIL_PATTERN = 'DROP DATABASE' }
    Assert-That -Name '退出码非零' -Condition ($r.ExitCode -ne 0) -Detail "exit=$($r.ExitCode)"
    Assert-That -Name '输出包含 [ERROR]' -Condition ($r.Combined -match '\[ERROR\]')

    # ------------------------------------------------------------
    Write-TestHead '边界：容器/端口映射场景（hostname 非本机名但监听回环）'
    Reset-StubLogs
    $r = Invoke-RebuildScript -ScriptPath $autoConfirm -StubEnv @{
        CAMPUSHUB_STUB_HOSTNAME     = 'a1b2c3d4e5f6'
        CAMPUSHUB_STUB_BIND_ADDRESS = '0.0.0.0'
    }
    Assert-That -Name '本地容器 MySQL 允许重建（退出码为 0）' -Condition ($r.ExitCode -eq 0) -Detail "exit=$($r.ExitCode); out=$($r.StdOut)"
    Assert-That -Name '已执行 DROP DATABASE' -Condition ((Get-StubRawLog) -match 'DROP DATABASE')

    # ------------------------------------------------------------
    Write-TestHead '边界：目标数据库不存在时直接创建'
    Reset-StubLogs
    $r = Invoke-RebuildScript -ScriptPath $autoConfirm -StubEnv @{ CAMPUSHUB_STUB_DB_EXISTS = '0' }
    Assert-That -Name '退出码为 0' -Condition ($r.ExitCode -eq 0) -Detail "exit=$($r.ExitCode); out=$($r.StdOut)"
    Assert-That -Name '未查询表数量即直接创建' -Condition ((Get-StubRawLog) -notmatch 'information_schema\.TABLES')
    Assert-That -Name '已执行 DROP DATABASE IF EXISTS' -Condition ((Get-StubRawLog) -match 'DROP DATABASE IF EXISTS')
} finally {
    $env:PATH = $script:OriginalPath
    if ($script:StubDir -and (Test-Path -LiteralPath $script:StubDir)) {
        Remove-Item -LiteralPath $script:StubDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
if ($script:Failed -eq 0) {
    Write-Host "=== 全部通过: $($script:Passed) 项断言 ===" -ForegroundColor Green
    exit 0
}

Write-Host "=== 存在失败: $($script:Failed)/$($script:Passed + $script:Failed) 项断言未通过 ===" -ForegroundColor Red
foreach ($failure in $script:Failures) {
    Write-Host "  - $failure" -ForegroundColor Red
}
exit 1
