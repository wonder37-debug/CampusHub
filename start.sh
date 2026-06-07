#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_LOG="$ROOT_DIR/backend.log"
FRONTEND_LOG="$ROOT_DIR/frontend.log"

if ! command -v npm >/dev/null 2>&1; then
  echo "未找到 npm，请先安装 Node.js。"
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "未找到 java，请先安装 JDK 21。"
  exit 1
fi

# 在 WSL / Git Bash 里 netstat 可能是 Linux 版本，输出格式不同
# 优先用 Windows 原生的 netstat.exe，确保字段对齐
# netstat.exe 输出带 \r，用 tr 去掉避免 awk 把 \r 粘到 PID 末尾
_netstat() {
  if command -v netstat.exe >/dev/null 2>&1; then
    netstat.exe -ano | tr -d '\r'
  else
    netstat -ano | tr -d '\r'
  fi
}

clear_port() {
  local port="$1"
  local pids
  pids="$(_netstat 2>/dev/null | awk -v port=":${port}$" '$1=="TCP" && $2 ~ port && $4=="LISTENING" {print $5}' | sort -u || true)"

  if [[ -z "$pids" ]]; then
    return 0
  fi

  echo "检测到 ${port} 端口被占用，正在清理旧进程 (PID: ${pids}) ..."
  for pid in $pids; do
    [[ -z "$pid" || "$pid" == "0" ]] && continue
    taskkill.exe /PID "$pid" /F >/dev/null 2>&1 || kill "$pid" >/dev/null 2>&1 || true
  done

  # 验证端口是否真的释放了
  sleep 1
  if _netstat 2>/dev/null | awk -v port=":${port}$" '$1=="TCP" && $2 ~ port && $4=="LISTENING"' | grep -q .; then
    echo "警告：${port} 端口清理失败，可能仍有进程占用。请手动检查。"
  fi
}

: > "$BACKEND_LOG"
: > "$FRONTEND_LOG"

cleanup() {
  echo "正在停止服务..."
  local pids
  # 杀掉 8080 上所有 LISTENING 进程（后端）
  pids="$(_netstat 2>/dev/null | awk '$1=="TCP" && $2 ~ /:8080$/ && $4=="LISTENING" {print $5}' | sort -u || true)"
  for pid in $pids; do
    [[ -z "$pid" || "$pid" == "0" ]] && continue
    taskkill.exe /PID "$pid" /F >/dev/null 2>&1 || kill "$pid" >/dev/null 2>&1 || true
  done
  # 杀掉 5173 上所有 LISTENING 进程（前端）
  pids="$(_netstat 2>/dev/null | awk '$1=="TCP" && $2 ~ /:5173$/ && $4=="LISTENING" {print $5}' | sort -u || true)"
  for pid in $pids; do
    [[ -z "$pid" || "$pid" == "0" ]] && continue
    taskkill.exe /PID "$pid" /F >/dev/null 2>&1 || kill "$pid" >/dev/null 2>&1 || true
  done
}

print_failure_reason() {
  if grep -q "Port 8080 was already in use" "$BACKEND_LOG" 2>/dev/null; then
    echo "后端启动失败：8080 端口仍被占用。"
    return 0
  fi

  if grep -q "APPLICATION FAILED TO START" "$BACKEND_LOG" 2>/dev/null; then
    echo "后端启动失败：请查看 backend.log。"
    return 0
  fi

  if grep -q "VITE" "$FRONTEND_LOG" 2>/dev/null && grep -q "ready in" "$FRONTEND_LOG" 2>/dev/null; then
    echo "前端已启动成功。"
  fi
}

trap cleanup INT TERM EXIT

clear_port 8080
clear_port 5173

echo "正在启动后端..."
if command -v cmd.exe >/dev/null 2>&1; then
  (cd "$BACKEND_DIR" && cmd.exe /c mvnw.cmd spring-boot:run) > "$BACKEND_LOG" 2>&1 &
else
  (cd "$BACKEND_DIR" && bash ./mvnw spring-boot:run) > "$BACKEND_LOG" 2>&1 &
fi
BACKEND_PID=$!

echo "正在启动前端..."
(cd "$FRONTEND_DIR" && npm run dev -- --host 0.0.0.0 --port 5173 --strictPort) > "$FRONTEND_LOG" 2>&1 &
FRONTEND_PID=$!

echo "后端日志：$BACKEND_LOG"
echo "前端日志：$FRONTEND_LOG"
echo "前端地址：http://127.0.0.1:5173"
echo "后端地址：http://127.0.0.1:8080"
echo "按 Ctrl+C 停止两个服务。"

wait -n "$BACKEND_PID" "$FRONTEND_PID" || true
print_failure_reason
echo "检测到某个服务退出，正在停止另一个服务..."
cleanup
