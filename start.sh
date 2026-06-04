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

: > "$BACKEND_LOG"
: > "$FRONTEND_LOG"

cleanup() {
  if [[ -n "${BACKEND_PID:-}" ]] && kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
    kill "$BACKEND_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "${FRONTEND_PID:-}" ]] && kill -0 "$FRONTEND_PID" >/dev/null 2>&1; then
    kill "$FRONTEND_PID" >/dev/null 2>&1 || true
  fi
}

trap cleanup INT TERM EXIT

echo "正在启动后端..."
if command -v cmd.exe >/dev/null 2>&1; then
  (cd "$BACKEND_DIR" && cmd.exe /c mvnw.cmd spring-boot:run) > "$BACKEND_LOG" 2>&1 &
else
  (cd "$BACKEND_DIR" && bash ./mvnw spring-boot:run) > "$BACKEND_LOG" 2>&1 &
fi
BACKEND_PID=$!

echo "正在启动前端..."
(cd "$FRONTEND_DIR" && npm run dev -- --host 0.0.0.0 --port 5173) > "$FRONTEND_LOG" 2>&1 &
FRONTEND_PID=$!

echo "后端日志：$BACKEND_LOG"
echo "前端日志：$FRONTEND_LOG"
echo "前端地址：http://127.0.0.1:5173"
echo "后端地址：http://127.0.0.1:8080"
echo "按 Ctrl+C 停止两个服务。"

wait -n "$BACKEND_PID" "$FRONTEND_PID" || true
echo "检测到某个服务退出，正在停止另一个服务..."
cleanup
