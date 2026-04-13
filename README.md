# CampusHub 校园互助服务平台

## 项目简介
CampusHub 是一个统一的校园互助服务平台。大学校园中，学生之间存在大量日常互助需求，例如快递代取、学习辅导、二手物品交换、活动组队等。目前这些需求散落在微信群、QQ群、贴吧等平台，导致信息碎片化、匹配效率低、且缺乏信任机制。CampusHub 旨在解决这些痛点，让学生能够方便地发布需求、发现匹配、建立信任、完成协作。

## 技术栈选型
本项目采用单体大仓库 (Monorepo) 架构进行前后端分离组织，底层开发环境基于 Windows 11 与 WSL (Ubuntu) 原生 Linux 文件系统。

* **前端工程 (Frontend)**:
    * 核心框架：Vue 3 
    * 构建工具：Vite
    * 语言规范：TypeScript
    * 运行环境：Node.js 22 LTS
    * 状态与路由：Vue Router 4, Pinia
* **后端工程 (Backend)**:
    * 核心框架：Spring Boot 3.5.0
    * 语言规范：Java 21 (AWS Corretto 分发版 LTS)
    * 构建工具：Maven (配置 Maven Wrapper 并锁定 LF 换行符)
    * 持久层框架：MyBatis
* **数据库引擎 (Database)**:
    * 日常开发与生产：MySQL
    * 持续集成测试环境：H2 内存数据库 (In-memory Database)
* **研发运维 (DevOps)**:
    * 持续集成 (CI)：GitHub Actions (使用 `dorny/paths-filter` 实现前后端物理隔离与按需构建，防范 PR 死锁)
    * 代码审查 (Code Review)：CodeRabbit AI (采用 Advice-Only 建议模式进行自动化审查与拦截)
    * 版本控制：Git / GitHub (严格落实 GitFlow 基于 Pull Request 的分支保护模型)

## 团队成员
* **需求负责人兼组长**: 郑嘉鸿 
* **架构负责人**: 汪一航 
* **开发负责人**: 何刘磊 
* **测试负责人**: 杨宗乔 
