# Kiwi

![Build](https://github.com/euver/Kiwi/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
Kiwi 是一款专为 Spring 项目开发设计的 IntelliJ IDEA 插件，旨在提升开发者对 Spring 项目源码的分析和开发效率。该插件提供了一系列实用工具，帮助开发者快速理解和处理项目中的复杂代码结构。
<!-- Plugin description end -->

## 功能特性

### 1. Copy Expanded Statement

快速将 MyBatis Mapper XML 中的 SQL 语句完整组装，自动替换所有 `<include>` 标签引用的 SQL 片段，一键复制到剪贴板。

**核心能力：**
- 支持当前文件内的 SQL 片段引用
- 支持跨文件的 SQL 片段引用（使用完整 namespace）
- 支持嵌套的 include 标签（最多 10 层）
- 自动检测循环引用
- 保留原始格式（缩进、换行、空格）
- 保留所有 MyBatis 动态标签

**使用场景：**
- 调试复杂 SQL 语句时，查看完整的 SQL 内容
- 将组装后的 SQL 复制到数据库客户端执行测试
- 代码审查时快速理解 SQL 的完整结构

### 2. Extract Method Information

快速提取 Java 方法的基础信息，包括 HTTP 请求类型、请求路径、方法全限定名、类功能注释和方法功能注释。

**核心能力：**
- 支持 Spring MVC 注解（@GetMapping、@PostMapping 等）
- 支持 JAX-RS 注解（@GET、@POST、@Path 等）
- 支持类级别和方法级别路径组合
- 支持重写方法的注释向上追溯
- 排除技术性 JavaDoc 标签（@param、@return 等）

**使用场景：**
- 快速了解 Controller 方法的 HTTP 请求信息
- 获取方法的完整限定名用于日志分析或文档编写
- 分析 RESTful API 接口的路由信息

### 3. Get Top Callers Information

查找 Java 方法或 MyBatis Statement 的所有顶层调用者（入口方法），自动递归分析调用链。

**核心能力：**
- 支持 Java 方法定义和方法调用表达式上触发
- 支持 MyBatis XML Statement 上触发
- 支持递归调用链分析（最大深度 50 层）
- 支持接口实现类方法调用追溯
- 后台异步执行，不阻塞 IDE

**使用场景：**
- 分析某个底层方法被哪些 API 入口调用
- 理解代码的影响范围，评估修改的风险
- 快速定位调用某个 MyBatis SQL 的所有 Controller 入口

## 使用方法

### Copy Expanded Statement

**方式一：在 XML 文件中触发**

1. 打开 MyBatis Mapper XML 文件
2. 将光标定位在 `<select>`、`<insert>`、`<update>` 或 `<delete>` 标签内
3. 右键点击，选择 **"Kiwi" → "Copy Expanded Statement"**

**方式二：在 Java 文件中触发**

1. 打开 Mapper 接口的 Java 文件
2. 将光标定位在接口方法上
3. 右键点击，选择 **"Kiwi" → "Copy Expanded Statement"**

### Extract Method Information

1. 打开任意 Java 文件
2. 将光标定位在方法内部或方法签名上
3. 右键点击，选择 **"Kiwi" → "Extract Method Information"**

### Get Top Callers Information

**方式一：在 Java 文件中触发**

1. 打开任意 Java 文件
2. 将光标定位在方法定义上，或定位在方法调用表达式上
3. 右键点击，选择 **"Kiwi" → "Get Top Callers Information"**

**方式二：在 MyBatis XML 文件中触发**

1. 打开 MyBatis Mapper XML 文件
2. 将光标定位在 `<select>`、`<insert>`、`<update>` 或 `<delete>` 标签内
3. 右键点击，选择 **"Kiwi" → "Get Top Callers Information"**

### 输出结果

执行后，结果会输出到以下位置：

- **系统剪贴板** - 可直接使用 `Ctrl+V` / `Cmd+V` 粘贴
- **Kiwi Console** - 在 IDE 底部控制台窗口显示详细组装信息
- **通知消息** - 右下角显示操作结果提示

注：Copy Expanded Statement 功能会额外将结果复制到系统剪贴板，可直接使用 `Ctrl+V` / `Cmd+V` 粘贴。

## 安装

### 从 JetBrains Marketplace 安装

1. 打开 IntelliJ IDEA
2. 进入 `Settings/Preferences` → `Plugins` → `Marketplace`
3. 搜索 **"Kiwi"**
4. 点击 `Install` 安装

### 从本地文件安装

1. 下载 [最新版本](https://github.com/euver/Kiwi/releases/latest)
2. 进入 `Settings/Preferences` → `Plugins` → ⚙️ → `Install Plugin from Disk...`
3. 选择下载的 zip 文件
4. 重启 IDE

## 环境要求

- IntelliJ IDEA 2023.1 或更高版本

## 常见问题

### 为什么有些 include 标签没有被替换？

可能原因：
1. SQL 片段不存在，请检查 `<sql id="xxx">` 标签是否已定义
2. 跨文件引用时 namespace 不正确，refid 格式应为 `namespace.fragmentId`
3. XML 文件未被索引，可尝试重启 IDEA

### 在 Java 方法上右键但没有找到对应的 Statement？

请确认：
1. Java 接口的全限定名与 XML 文件的 namespace 一致
2. 方法名与 XML 中的 Statement ID 一致
3. 对应的 XML 文件存在于项目中

### 组装后的 SQL 格式不美观？

当前版本保留原始格式，不进行格式化。SQL 格式化功能将在后续版本中提供。

## 反馈与贡献

如有问题或建议，欢迎提交 [Issue](https://github.com/euver/Kiwi/issues) 或 Pull Request。

## 许可证

与 IntelliJ Platform Plugin Template 保持一致。
