- # AGENTS.md

  This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

  ## Language Mandate

  **必须使用中文回复**：在与用户的任何对话、代码解释、错误分析或总结中，永远使用简体中文。

  **专有名词例外**：仅在输出代码片段、技术框架名称、特定变量名或终端命令时保留英文，禁止强行翻译技术词汇。

  ## Project Overview

  This is a full-stack campus management application called CampusHub, structured as a Monorepo hosted on Nanjing University GitLab (`git.nju.edu.cn/cumpushub/sec-ii-2026`).

  - **Backend**: Spring Boot 3.5.0 with Java 21, MyBatis for database access, MySQL as the production database, H2 for tests
  - **Frontend**: Vue 3.5 + TypeScript 6 + Vite 8, Pinia 3 for state management, Vue Router 4 for routing

  ## Build Commands

  ### Backend

  ```bash
  cd backend
  ./mvnw clean package -DskipTests    # 不运行测试，直接构建
  ./mvnw clean package                 # 构建并运行测试
  ./mvnw test                          # 仅运行测试
  ./mvnw spring-boot:run               # 本地启动应用
  ```

  ### Frontend

  ```bash
  cd frontend
  npm ci                   # 安装依赖（严格遵循 package-lock.json）
  npm run build            # 类型检查 + 生产环境构建（vue-tsc -b && vite build）
  npm run dev              # 启动开发服务器
  npm run preview          # 预览生产构建
  ```

  ## Architecture

  ### Backend Structure

  - Package: `com.campushub.backend`
  - 使用 Lombok 减少样板代码
  - 测试环境使用 H2 内存数据库
  - 生产环境使用 MySQL，需在 `backend/src/main/resources/application.properties` 中配置连接信息

  ### Frontend Structure

  - 入口文件：`src/main.ts`
  - 使用 Vue 3 SFC `<script setup>` 语法
  - Vue Router 负责路由，Pinia 负责状态管理

  ### Repository Structure

  ```
  sec-ii-2026/
  ├── .gitlab-ci.yml          # 父流水线，按路径变更路由触发
  ├── .gitlab/
  │   ├── backend.yml         # 后端子流水线
  │   └── frontend.yml        # 前端子流水线
  ├── backend/                # Spring Boot 后端
  │   ├── mvnw                # Maven Wrapper（可直接使用）
  │   ├── pom.xml
  │   └── src/
  └── frontend/               # Vue 3 前端
      ├── package.json
      └── src/
  ```

  ## CI/CD

  ### Pipeline Architecture

  使用 GitLab Parent-Child Pipeline 架构：

  - 根目录 `.gitlab-ci.yml` 作为父流水线，根据路径变更路由触发子流水线
  - 仅 `backend/**` 变更时触发后端构建
  - 仅 `frontend/**` 变更时触发前端构建

  ### CI Environments

  - Backend: `maven:3.9.6-eclipse-temurin-21`，执行 `mvn clean package -DskipTests -B`
  - Frontend: `node:20`（降级至 LTS 版本以规避 Node 22 npm exit handler 缺陷），执行 `npm ci && npm run build`

  
