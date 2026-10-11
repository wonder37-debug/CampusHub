<#
.SYNOPSIS
    CampusHub 本地开发数据库重建脚本

.DESCRIPTION
    删除并重建本地开发数据库 campushub，仅限 localhost 使用。
    固定连接 127.0.0.1:3306，核对 MySQL 服务端身份和预期数据库名称。
    任一校验失败、连接异常或命令执行失败时立即终止，绝不继续执行删除操作。

.PARAMETER DbName
    要重建的数据库名称，默认 campushub

.PARAMETER DbUser
    MySQL 用户名，默认 root

.PARAMETER DbPassword
    MySQL 密码，通过参数或环境变量 DB_PASSWORD 传入

.EXAMPLE
    .\rebuild-local-db.ps1
    .\rebuild-local-db.ps1 -DbPassword "yourpassword"

.NOTES
    此脚本会删除指定数据库中的所有数据，仅用于本地开发环境。
    重建后重启 Spring Boot 应用，init_schema.sql 会自动完成建表。
#>
[CmdletBinding()]
param(
    [string]$DbName = 'campushub',
    [string]$DbUser = 'root',
    [string]$DbPassword = ''
)

$ErrorActionPreference = 'Stop'

if (-not $DbPassword) {
    $DbPassword = $env:DB_PASSWORD
    if (-not $DbPassword) {
        $DbPassword = Read-Host "请输入 MySQL root 密码" -AsSecureString
        $DbPassword = [System.Net.NetworkCredential]::new('', $DbPassword).Password
    }
}

$mysqlHost = '127.0.0.1'
$mysqlPort = 3306

Write-Host "`n=== CampusHub 本地数据库重建 ===" -ForegroundColor Cyan
Write-Host "目标: ${mysqlHost}:${mysqlPort}/${DbName}" -ForegroundColor Yellow

# --- 1. 检查 mysql 客户端是否可用 ---
$mysqlExe = Get-Command mysql -ErrorAction SilentlyContinue
if (-not $mysqlExe) {
    Write-Host "[ERROR] 未找到 mysql 客户端，请确保 MySQL 已安装并在 PATH 中" -ForegroundColor Red
    exit 1
}

# --- 2. 测试连接并验证 MySQL 服务端身份 ---
Write-Host "`n[1/4] 测试 MySQL 连接..." -ForegroundColor Cyan
try {
    $versionOutput = & mysql -h $mysqlHost -P $mysqlPort -u $DbUser -p"$DbPassword" -N -B -e "SELECT VERSION();" 2>&1
    $versionLine = $versionOutput | Where-Object { $_ -match '^\d+\.' }
    if ($LASTEXITCODE -ne 0 -or -not $versionLine) {
        Write-Host "[ERROR] 无法连接到 ${mysqlHost}:${mysqlPort}，请检查 MySQL 服务是否运行" -ForegroundColor Red
        Write-Host $versionOutput
        exit 1
    }
    if ($versionLine -notmatch 'MySQL|MariaDB') {
        $srvType = & mysql -h $mysqlHost -P $mysqlPort -u $DbUser -p"$DbPassword" -N -B -e "SELECT @@version_comment;" 2>$null
        if ($srvType -notmatch 'MySQL') {
            Write-Host "[ERROR] 服务端不是 MySQL（检测到: $srvType），终止" -ForegroundColor Red
            exit 1
        }
    }
    Write-Host "  MySQL 版本: $versionLine" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] 连接异常: $_" -ForegroundColor Red
    exit 1
}

# --- 3. 验证目标主机确为本机 ---
Write-Host "`n[2/4] 验证连接目标为本机..." -ForegroundColor Cyan
try {
    $hostnameOutput = & mysql -h $mysqlHost -P $mysqlPort -u $DbUser -p"$DbPassword" -N -B -e "SELECT @@hostname, @@bind_address;" 2>&1
    Write-Host "  服务端 hostname/bind_address: $hostnameOutput" -ForegroundColor Gray
} catch {
    Write-Host "[ERROR] 无法查询服务端身份: $_" -ForegroundColor Red
    exit 1
}
# 连接已固定为 127.0.0.1，此处仅输出信息供用户核对

# --- 4. 核对目标数据库名称 ---
Write-Host "`n[3/4] 核对目标数据库..." -ForegroundColor Cyan
try {
    $dbExists = & mysql -h $mysqlHost -P $mysqlPort -u $DbUser -p"$DbPassword" -N -B -e "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '$DbName';" 2>&1
    if ($dbExists -and $dbExists.ToString().Trim() -eq $DbName) {
        $tableCount = & mysql -h $mysqlHost -P $mysqlPort -u $DbUser -p"$DbPassword" -N -B -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$DbName';" 2>$null
        Write-Host "  数据库 '$DbName' 存在，当前有 $tableCount 张表" -ForegroundColor Yellow
    } else {
        Write-Host "  数据库 '$DbName' 不存在，将直接创建" -ForegroundColor Yellow
    }
} catch {
    Write-Host "[ERROR] 核对数据库失败: $_" -ForegroundColor Red
    exit 1
}

# --- 5. 要求用户明确确认 ---
Write-Host "`n[4/4] 确认删除..." -ForegroundColor Cyan
Write-Host "  警告: 即将删除数据库 '$DbName' 中的所有数据！" -ForegroundColor Red
Write-Host "  目标: ${mysqlHost}:${mysqlPort}/${DbName}" -ForegroundColor Red
Write-Host "  此操作不可逆！" -ForegroundColor Red
$confirm = Read-Host "`n输入 'YES' 确认删除并重建数据库"
if ($confirm -ne 'YES') {
    Write-Host "用户未确认，操作已取消" -ForegroundColor Yellow
    exit 0
}

# --- 6. 执行重建 ---
Write-Host "`n开始重建数据库..." -ForegroundColor Cyan
$sql = "DROP DATABASE IF EXISTS \`"$DbName\`"; CREATE DATABASE \`"$DbName\`" DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
try {
    $result = & mysql -h $mysqlHost -P $mysqlPort -u $DbUser -p"$DbPassword" -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] 执行重建失败: $result" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "[ERROR] 执行异常: $_" -ForegroundColor Red
    exit 1
}

# --- 7. 验证重建结果 ---
Write-Host "`n验证重建结果..." -ForegroundColor Cyan
try {
    $verifyOutput = & mysql -h $mysqlHost -P $mysqlPort -u $DbUser -p"$DbPassword" -N -B -e "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '$DbName';" 2>&1
    if ($verifyOutput -and $verifyOutput.ToString().Trim() -eq $DbName) {
        Write-Host "  数据库 '$DbName' 已成功重建（空库）" -ForegroundColor Green
    } else {
        Write-Host "[ERROR] 重建验证失败：数据库不存在" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "[ERROR] 验证异常: $_" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== 重建完成 ===" -ForegroundColor Green
Write-Host "重启 Spring Boot 应用后，init_schema.sql 会自动完成建表和数据初始化。" -ForegroundColor Cyan
Write-Host "命令: cd backend; ./mvnw spring-boot:run" -ForegroundColor Gray
