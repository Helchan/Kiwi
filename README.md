# Kiwi

![Build](https://github.com/euver/Kiwi/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

<!-- Plugin description -->
Kiwi 是一款专为 Spring 项目开发设计的 IntelliJ IDEA 插件，旨在提升开发者对 Spring 项目源码的分析和开发效率。该插件提供了一系列实用工具，帮助开发者快速理解和处理项目中的复杂代码结构。
<!-- Plugin description end -->

## 插件信息

- **当前版本**: 0.0.100
- **支持 IDE**: IntelliJ IDEA 2023.1+
- **开发语言**: Kotlin 2.2.21
- **JDK 版本**: 21
- **Gradle 版本**: 9.2.1

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

查找 Java 方法或 MyBatis Statement/SQL 片段的所有顶层调用者（入口方法），自动追溯调用链，找到所有最终的 API 入口点。

**核心能力：**
- 支持 Java 方法定义和方法调用表达式上触发
- 支持 MyBatis XML Statement 上触发
- **支持 SQL 片段（`<sql>` 标签）和 `<include>` refid 上触发**
- 使用广度优先搜索（BFS）算法递归分析调用链
- 最大深度限制 50 层（MAX_DEPTH = 50）
- 支持接口/父类方法调用追溯
- 支持 Lambda 表达式调用追溯（追踪到声明位置）
- 支持方法引用调用追溯（Class::method）
- 智能过滤函数式接口方法（Consumer.accept、Function.apply 等）
- 自动排除测试代码，只搜索生产代码
- 后台异步执行，不阻塞 IDE
- **TreeTable 层级展示结果，支持展开/折叠**
- **Excel 导出功能，支持单元格合并**

**性能优化：**
- 方法键缓存：避免重复计算方法签名
- 生产代码范围缓存：缓存搜索范围提高效率
- 批量查找：在单个 ReadAction 中完成所有查找操作
- 并发安全：使用 ConcurrentHashMap 保证线程安全

**异常处理：**
- Dumb 模式等待：在索引未完成时自动等待
- 取消操作支持：通过 ProgressManager 响应用户取消

**函数式接口方法过滤列表：**
- java.util.function: Consumer.accept、BiConsumer.accept、Function.apply、BiFunction.apply、Supplier.get、Predicate.test、BiPredicate.test、UnaryOperator.apply、BinaryOperator.apply
- java.lang: Runnable.run
- java.util.concurrent: Callable.call
- Java Streams: Stream.forEach、Stream.map、Stream.filter、Stream.flatMap
- Spring 框架: TransactionCallback.doInTransaction、RowMapper.mapRow、ResultSetExtractor.extractData

**使用场景：**
- 调用链分析：分析某个底层方法被哪些 API 入口调用
- 代码依赖关系梳理：理解代码的影响范围，评估修改的风险
- 函数式编程调用追溯：追踪 Lambda 和方法引用的实际调用位置
- 快速定位调用某个 MyBatis SQL 的所有 Controller 入口
- **SQL 片段影响分析：查找引用某个 SQL 片段的所有入口**

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

**方式二：在 MyBatis XML 文件中触发（Statement）**

1. 打开 MyBatis Mapper XML 文件
2. 将光标定位在 `<select>`、`<insert>`、`<update>` 或 `<delete>` 标签内
3. 右键点击，选择 **"Kiwi" → "Get Top Callers Information"**

**方式三：在 SQL 片段上触发**

1. 打开 MyBatis Mapper XML 文件
2. 将光标定位在 `<sql>` 标签内，或 `<include>` 标签的 refid 属性值上
3. 右键点击，选择 **"Kiwi" → "Get Top Callers Information"**
4. 系统会自动查找所有引用该 SQL 片段的 Statement，并分析这些 Statement 的顶层调用者

### 输出结果

执行后，结果会输出到以下位置：

- **Kiwi Console** - 在 IDE 底部控制台窗口显示详细信息
- **TreeTable 弹窗** - 以层级结构展示顶层调用者信息（Get Top Callers Information 功能）
- **通知消息** - 右下角显示操作结果提示

注：Copy Expanded Statement 功能会额外将结果复制到系统剪贴板，可直接使用 `Ctrl+V` / `Cmd+V` 粘贴。

**TreeTable 交互功能：**
- 展开/折叠节点查看详细信息
- 右键菜单支持跳转到源码、跳转到 XML、Copy Expanded Statement、复制
- 表头点击排序（支持类型、请求路径、方法列）
- 默认排序规则：API 优先、路径升序、方法名升序
- Excel 导出功能，支持单元格合并

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

- **IntelliJ IDEA**: 2023.1 或更高版本
- **JDK**: 构建需要 JDK 21

## 构建插件

如果你需要从源码构建插件：

```bash
# 设置 JDK 21
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home

# 清理并构建插件
./gradlew clean buildPlugin
```

构建产物位于：`build/distributions/Kiwi-0.0.100.zip`

## 项目结构

```
Kiwi/
├── src/main/kotlin/com/euver/kiwi/
│   ├── action/                              # 表示层 - 用户交互
│   │   ├── AssembleSqlAction.kt             # MyBatis SQL 组装 Action
│   │   ├── ExtractMethodInfoAction.kt       # 提取方法信息 Action
│   │   └── FindTopCallerAction.kt           # 查找顶层调用者 Action
│   ├── application/                         # 应用层 - 用例编排
│   │   └── ExpandStatementUseCase.kt        # 展开 Statement 用例
│   ├── domain/                              # 领域层 - 核心业务逻辑
│   │   ├── model/
│   │   │   ├── ExpandContext.kt             # 展开上下文
│   │   │   ├── MethodInfo.kt                # 方法信息模型
│   │   │   └── TopCallerWithStatement.kt    # 顶层调用者与 Statement 关联
│   │   └── service/
│   │       ├── MethodInfoExtractorService.kt # 方法信息提取服务
│   │       ├── SqlFragmentResolver.kt       # 片段解析器接口
│   │       ├── SqlFragmentUsageFinderService.kt # SQL 片段使用查找服务
│   │       ├── StatementExpanderService.kt  # 核心展开服务
│   │       └── TopCallerFinderService.kt    # 顶层调用者查找服务
│   ├── infrastructure/                      # 基础设施层
│   │   └── resolver/
│   │       └── SqlFragmentResolverImpl.kt   # 片段解析器实现
│   ├── model/                               # 共享数据模型
│   │   ├── AssemblyResult.kt                # 组装结果
│   │   ├── SqlFragmentInfo.kt               # SQL 片段信息
│   │   └── StatementInfo.kt                 # Statement 信息
│   ├── parser/
│   │   └── MyBatisXmlParser.kt              # XML 解析器
│   └── service/                             # 基础服务与 UI 组件
│       ├── ConsoleOutputService.kt          # 控制台输出服务
│       ├── MapperIndexService.kt            # Mapper 索引服务
│       ├── MyBatisSqlToolWindowFactory.kt   # 控制台窗口工厂
│       ├── NotificationService.kt           # 通知服务
│       ├── SqlAssembler.kt                  # SQL 组装服务
│       ├── TopCallersTableDialog.kt         # 表格弹窗（基础版）
│       └── TopCallersTreeTableDialog.kt     # TreeTable 弹窗
├── src/main/resources/META-INF/
│   └── plugin.xml                           # 插件配置文件
├── build.gradle.kts                         # Gradle 构建配置
├── gradle.properties                        # Gradle 属性配置
├── CHANGELOG.md                             # 版本变更日志
├── Kiwi产品说明书.md                      # 详细产品文档
└── README.md                                # 项目说明文档
```

## 技术架构

Kiwi 采用 **DDD（领域驱动设计）分层架构**，各层职责清晰：

- **表示层 (Presentation)**: 处理用户交互，触发应用服务
- **应用层 (Application)**: 编排用例流程，协调领域服务
- **领域层 (Domain)**: 核心业务逻辑，可复用的展开能力
- **基础设施层 (Infrastructure)**: 技术实现，文件解析、索引等

详细架构说明请查看 [Kiwi产品说明书.md](./Kiwi产品说明书.md)。

## 依赖项

| 依赖 | 版本 |
|------|------|
| IntelliJ Platform | 2023.1+ |
| Kotlin | 2.2.21 |
| Gradle | 9.2.1 |
| JDK | 21 |
| Apache POI | 5.2.5 |
| IntelliJ Platform Gradle Plugin | 2.10.5 |
| Gradle Changelog Plugin | 2.5.0 |
| Gradle Kover Plugin | 0.9.3 |
| JUnit | 4.13.2 |

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

## 版本历史

### v0.0.100 (当前版本)
- Get Top Callers Information 功能与 IDEA 原生 CallerMethodsTreeStructure 完全对齐
- 新增 Javadoc 引用过滤和类型关联性检查
- 增强异常处理，优化用户体验

更多版本信息请查看 [CHANGELOG.md](./CHANGELOG.md)。

## 反馈与贡献

如有问题或建议，欢迎提交 [Issue](https://github.com/euver/Kiwi/issues) 或 Pull Request。

## 许可证

与 IntelliJ Platform Plugin Template 保持一致。
