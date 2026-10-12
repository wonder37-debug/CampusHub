<#
.SYNOPSIS
    CampusHub 本地开发数据库重建脚本（仅 Windows 本地开发使用）

.DESCRIPTION
    删除并重建本地开发数据库 campushub。

    连接目标硬编码为 127.0.0.1:3306，数据库名硬编码为 campushub，
    不提供任何参数修改主机、端口或数据库名，从入口上排除误连远程/生产库的可能。

    删除前的校验链，任一失败立即终止，绝不执行 DROP DATABASE：
      1. mysql 客户端可用（只接受 PATH 中的可执行文件）；
      2. 运行期复核硬编码目标仍为 127.0.0.1:3306/campushub；
      3. MySQL 连接成功，且服务端身份被确认为本机 MySQL
         （端口一致 + MySQL/MariaDB 版本 + 服务端主机名等于本机计算机名；
          回环/通配监听地址不作为本机依据）；
      4. 用户输入 YES 明确确认。

    mysql 客户端的进程退出码是唯一失败判据：连接失败、查询失败、
    重建失败都会在这里被识别为错误并向上抛出，不会被静默吞掉。

    密码通过 MYSQL_PWD 环境变量下发给 mysql 客户端，不出现在命令行参数中；
    所有对外输出（含 mysql 客户端吐出的 stderr）在打印前都会剔除密码原文。

.PARAMETER DbUser
    本地 MySQL 用户名，默认 root

.PARAMETER DbPassword
    本地 MySQL 密码。未显式传入时依次尝试环境变量 DB_PASSWORD、交互式输入。
    通过命令行传入的密码可能进入 shell 历史记录，建议优先使用环境变量或交互式输入。

.EXAMPLE
    .\rebuild-local-db.ps1

    优先通过环境变量 DB_PASSWORD 或交互式输入提供密码，避免密码进入命令行历史。

.NOTES
    此脚本会删除 campushub 中的全部数据，仅限本地开发环境。
    重建后重启 Spring Boot 应用，init_schema.sql 会自动完成建表和默认数据初始化。
#>
[CmdletBinding()]
param(
    [string]$DbUser = 'root',
    [string]$DbPassword = ''
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------------
# 硬编码目标：有意不接受参数覆盖，也不从环境变量/配置文件读取
# ------------------------------------------------------------------
$script:TargetHost = '127.0.0.1'
$script:TargetPort = 3306
$script:TargetDatabase = 'campushub'
$script:DbUser = $DbUser
$script:DbPassword = $DbPassword
$script:MySqlExe = ''

# 数据库名只允许出现在这里；下面所有 SQL 都以它为字面量插值，
# 因此在运行期必须再次确认它仍是安全的裸标识符。
$script:AllowedDatabaseNamePattern = '^[A-Za-z0-9_]+$'

function Remove-Secret {
    <# 剔除输出中的密码原文，避免写入控制台或日志 #>
    param(
        [Parameter()]
        [AllowEmptyString()]
        [string]$Text
    )
    if ([string]::IsNullOrEmpty($Text)) { return '' }
    if ([string]::IsNullOrEmpty($script:DbPassword)) { return $Text }
    return $Text.Replace($script:DbPassword, '***')
}

function Write-Fail {
    <# 统一的失败出口：脱敏输出后以非零码终止，调用点之后的所有逻辑都不会执行 #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )
    Write-Host "[ERROR] $(Remove-Secret -Text $Message)" -ForegroundColor Red
    exit 1
}

function Invoke-MySqlQuery {
    <#
    .SYNOPSIS
        通过 mysql 客户端执行一条 SQL，返回输出行数组。
    .DESCRIPTION
        以进程退出码作为唯一失败判据：退出码非零即抛出，调用方必须显式处理。
        stderr 通过 2>&1 合并进输出，打印前统一脱敏。
        密码走 MYSQL_PWD 环境变量，执行完立即恢复原值，不残留给后续子进程。
    #>
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Sql
    )

    $mysqlArgs = @(
        '-h', $script:TargetHost
        '-P', [string]$script:TargetPort
        '-u', $script:DbUser
        '-N', '-B'
        '-e', $Sql
    )

    $previousErrorAction = $ErrorActionPreference
    $previousMysqlPwd = $env:MYSQL_PWD
    $output = @()
    $exitCode = 0
    try {
        # 原生命令写 stderr 时，$ErrorActionPreference='Stop' 会把它升级成终止性错误，
        # 这里临时降级为 Continue，让退出码成为唯一失败判据
        $ErrorActionPreference = 'Continue'
        $env:MYSQL_PWD = $script:DbPassword
        $output = @(& $script:MySqlExe @mysqlArgs 2>&1)
        $exitCode = $LASTEXITCODE
    } catch {
        throw [System.InvalidOperationException]::new("调用 mysql 客户端失败: $($_.Exception.Message)")
    } finally {
        $ErrorActionPreference = $previousErrorAction
        if ($null -eq $previousMysqlPwd) {
            Remove-Item -Path 'Env:\MYSQL_PWD' -ErrorAction SilentlyContinue
        } else {
            $env:MYSQL_PWD = $previousMysqlPwd
        }
    }

    if ($exitCode -ne 0) {
        $detail = (($output | ForEach-Object { [string]$_ }) -join ' ').Trim()
        if (-not $detail) { $detail = 'mysql 客户端未输出错误详情' }
        throw [System.InvalidOperationException]::new(
            "mysql 命令执行失败（退出码 $exitCode）：$(Remove-Secret -Text $detail)")
    }

    return @($output | Where-Object { $null -ne $_ -and "$_".Trim() -ne '' } | ForEach-Object { [string]$_ })
}

Write-Host "`n=== CampusHub 本地数据库重建 ===" -ForegroundColor Cyan
Write-Host "目标: $($script:TargetHost):$($script:TargetPort)/$($script:TargetDatabase)" -ForegroundColor Yellow

# ------------------------------------------------------------------
# 0. 运行期复核硬编码目标：防止后续维护误把主机/端口/库名改成别的值
# ------------------------------------------------------------------
if ($script:TargetHost -ne '127.0.0.1') {
    Write-Fail "内部校验失败：连接主机只能是 127.0.0.1，当前为 '$($script:TargetHost)'"
}
if ($script:TargetPort -ne 3306) {
    Write-Fail "内部校验失败：连接端口只能是 3306，当前为 $($script:TargetPort)"
}
if ($script:TargetDatabase -notmatch $script:AllowedDatabaseNamePattern) {
    Write-Fail "内部校验失败：数据库名只能是字母/数字/下划线，当前为 '$($script:TargetDatabase)'"
}
if ($script:TargetDatabase -ne 'campushub') {
    Write-Fail "内部校验失败：数据库名只能是 campushub，当前为 '$($script:TargetDatabase)'"
}
if ([string]::IsNullOrWhiteSpace($script:DbUser)) {
    Write-Fail '内部校验失败：MySQL 用户名不能为空'
}

# ------------------------------------------------------------------
# 1. 解析密码：参数 > 环境变量 DB_PASSWORD > 交互式输入
# ------------------------------------------------------------------
if (-not $script:DbPassword) {
    $script:DbPassword = $env:DB_PASSWORD
}
if (-not $script:DbPassword) {
    $securePassword = Read-Host "请输入本地 MySQL 用户 '$($script:DbUser)' 的密码（无密码直接回车）" -AsSecureString
    $script:DbPassword = [System.Net.NetworkCredential]::new('', $securePassword).Password
}

# ------------------------------------------------------------------
# 2. mysql 客户端可用性
# ------------------------------------------------------------------
# PATH 中可能同时存在多个 mysql（例如本机装了多个 MySQL 实例），只取第一个命中的可执行文件
$script:MySqlExe = (Get-Command mysql -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1).Source
if (-not $script:MySqlExe) {
    Write-Fail '未找到 mysql 客户端，请确认本机已安装 MySQL 且 mysql.exe 在 PATH 中'
}

# ------------------------------------------------------------------
# 3. 连接 MySQL 并核对服务端身份
# ------------------------------------------------------------------
Write-Host "`n[1/3] 连接 MySQL 并核对服务端身份..." -ForegroundColor Cyan
try {
    $versionRows = Invoke-MySqlQuery -Sql 'SELECT VERSION();'
} catch {
    Write-Fail "无法连接到 $($script:TargetHost):$($script:TargetPort)（用户 $($script:DbUser)）：$($_.Exception.Message)"
}
$serverVersion = ($versionRows | Select-Object -First 1)
if (-not $serverVersion) {
    Write-Fail '未能读取 MySQL 服务端版本号'
}

try {
    $identityRows = Invoke-MySqlQuery -Sql 'SELECT @@port, @@hostname, @@bind_address, @@version_comment;'
} catch {
    Write-Fail "无法查询 MySQL 服务端身份：$($_.Exception.Message)"
}
$identity = @("$($identityRows | Select-Object -First 1)" -split "`t")
if ($identity.Count -lt 4) {
    Write-Fail "MySQL 服务端身份信息不完整：$($identityRows -join ' ')"
}

$serverPort = $identity[0].Trim()
$serverHostname = $identity[1].Trim()
$serverBindAddress = $identity[2].Trim()
$serverComment = $identity[3].Trim()

# 3a. 端口必须与连接目标一致，排除连到另一实例的可能
if ($serverPort -ne [string]$script:TargetPort) {
    Write-Fail "服务端端口为 $serverPort，与连接目标 $($script:TargetPort) 不一致，终止"
}

# 3b. 服务端必须是 MySQL / MariaDB，排除 3306 上跑着的其他服务
if (($serverVersion -notmatch 'mysql|mariadb') -and ($serverComment -notmatch 'mysql|mariadb')) {
    Write-Fail "服务端不是 MySQL（版本: $serverVersion，备注: $serverComment），终止"
}

# 3c. 服务端身份必须能确认为本机：@@hostname 必须等于本机计算机名。
#     监听地址（回环或通配）不能作为本机依据：127.0.0.1:3306 可能是 SSH 隧道或
#     容器端口映射的入口，其背后的 MySQL 报告的 bind_address 与真实本机实例无从
#     区分，唯有主机名一致才足以确认目标在本机；无法确认时立即终止。
if ($serverHostname -ine $env:COMPUTERNAME) {
    Write-Fail "无法确认服务端运行在本机（服务端 hostname: $serverHostname，本机计算机名: $env:COMPUTERNAME，bind_address: $serverBindAddress），终止"
}

Write-Host "  服务端身份: MySQL $serverVersion / hostname=$serverHostname / bind_address=$serverBindAddress" -ForegroundColor Green

# ------------------------------------------------------------------
# 4. 核对目标数据库
# ------------------------------------------------------------------
Write-Host "`n[2/3] 核对目标数据库..." -ForegroundColor Cyan
try {
    $schemaRows = Invoke-MySqlQuery -Sql "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '$($script:TargetDatabase)';"
    $schemaExists = (@($schemaRows | Where-Object { $_ -ieq $script:TargetDatabase }).Count -gt 0)
} catch {
    Write-Fail "核对目标数据库失败：$($_.Exception.Message)"
}

if ($schemaExists) {
    try {
        $tableRows = Invoke-MySqlQuery -Sql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$($script:TargetDatabase)';"
        $tableCount = ($tableRows | Select-Object -First 1)
    } catch {
        Write-Fail "统计目标数据库表数量失败：$($_.Exception.Message)"
    }
    Write-Host "  数据库 '$($script:TargetDatabase)' 已存在，当前 $tableCount 张表" -ForegroundColor Yellow
} else {
    Write-Host "  数据库 '$($script:TargetDatabase)' 不存在，将直接创建" -ForegroundColor Yellow
}

# ------------------------------------------------------------------
# 5. 要求用户明确确认：只有输入 YES 才继续
# ------------------------------------------------------------------
Write-Host "`n[3/3] 确认删除" -ForegroundColor Cyan
Write-Host "  警告: 即将删除数据库 '$($script:TargetDatabase)' 中的所有数据！" -ForegroundColor Red
Write-Host "  目标: $($script:TargetHost):$($script:TargetPort)/$($script:TargetDatabase)" -ForegroundColor Red
Write-Host "  此操作不可逆！" -ForegroundColor Red
$confirmation = Read-Host "输入 'YES' 确认删除并重建数据库"
if ($confirmation -ne 'YES') {
    Write-Host '用户未确认，操作已取消' -ForegroundColor Yellow
    exit 0
}

# ------------------------------------------------------------------
# 6. 执行重建：MySQL 标识符用反引号包裹（PowerShell 中写作成 `` 转义）
# ------------------------------------------------------------------
Write-Host "`n开始重建数据库..." -ForegroundColor Cyan
$rebuildSql = "DROP DATABASE IF EXISTS ``$($script:TargetDatabase)``; CREATE DATABASE ``$($script:TargetDatabase)`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
try {
    Invoke-MySqlQuery -Sql $rebuildSql | Out-Null
} catch {
    Write-Fail "执行重建失败：$($_.Exception.Message)"
}

# ------------------------------------------------------------------
# 7. 验证重建结果
# ------------------------------------------------------------------
Write-Host "`n验证重建结果..." -ForegroundColor Cyan
try {
    $verifyRows = Invoke-MySqlQuery -Sql "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '$($script:TargetDatabase)';"
    $verified = (@($verifyRows | Where-Object { $_ -ieq $script:TargetDatabase }).Count -gt 0)
} catch {
    Write-Fail "验证重建结果失败：$($_.Exception.Message)"
}
if (-not $verified) {
    Write-Fail "重建验证失败：数据库 '$($script:TargetDatabase)' 不存在"
}

Write-Host "  数据库 '$($script:TargetDatabase)' 已成功重建（空库）" -ForegroundColor Green
Write-Host "`n=== 重建完成 ===" -ForegroundColor Green
Write-Host '重启 Spring Boot 应用后，init_schema.sql 会自动完成建表和默认数据初始化。' -ForegroundColor Cyan
Write-Host '命令: cd backend; ./mvnw spring-boot:run' -ForegroundColor Gray
