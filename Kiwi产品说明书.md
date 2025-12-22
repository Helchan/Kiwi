# Kiwi 产品说明书

## 概述

Kiwi 是一款专为 Spring 项目开发设计的 IntelliJ IDEA 插件，旨在提升开发者对 Spring 项目源码的分析和开发效率。该插件提供了一系列实用工具，帮助开发者快速理解和处理项目中的复杂代码结构。

### 插件信息

| 属性 | 值 |
|------|-----|
| 插件名称 | Kiwi |
| 插件 ID | com.euver.kiwi |
| 开发者 | euver |
| 支持 IDE 版本 | IntelliJ IDEA 2023.1+ |
| 开发语言 | Kotlin |
| JDK 版本 | 21 |

---

## 功能模块

### 1. Copy Expanded Statement（MyBatis SQL 组装）

#### 功能描述

该功能帮助开发者快速将 MyBatis Mapper XML 文件中的 SQL 语句完整组装，自动递归替换所有 `<include>` 标签引用的 SQL 片段，生成完整的可执行 SQL。

#### 使用场景

- 调试复杂 SQL 语句时，需要查看完整的 SQL 内容
- 需要将组装后的 SQL 复制到数据库客户端执行测试
- 代码审查时快速理解 SQL 的完整结构
- 分析跨多个 Mapper 文件引用的 SQL 片段

#### 触发方式

**方式一：在 XML 文件中触发**
1. 打开 MyBatis Mapper XML 文件
2. 将光标定位在 `<select>`、`<insert>`、`<update>` 或 `<delete>` 标签内的任意位置
3. 右键点击，在弹出菜单中选择 **"Copy Expanded Statement"**

**方式二：在 Java 文件中触发**
1. 打开 Mapper 接口的 Java 文件
2. 将光标定位在接口方法上
3. 右键点击，在弹出菜单中选择 **"Copy Expanded Statement"**

#### 输出结果

执行该功能后，结果会输出到以下位置：

1. **系统剪贴板** - 可直接使用 `Ctrl+V` (Windows/Linux) 或 `Cmd+V` (Mac) 粘贴
2. **Kiwi Console** - 在 IDE 底部的控制台窗口中打印详细的组装信息，包括：
   - Statement ID 和 namespace
   - 来源文件路径
   - 完整的组装后 SQL
   - 组装统计（替换的 include 数量等）
   - 未找到的 SQL 片段列表（如果有）
3. **通知消息** - 右下角显示操作结果提示

#### 通知类型

根据组装结果，会显示不同类型的通知：

| 类型 | 颜色 | 说明 |
|------|------|------|
| 成功 | 绿色 | 所有 SQL 片段都成功找到并替换 |
| 警告 | 黄色 | 部分 SQL 片段未找到，会列出未找到的片段 ID |
| 错误 | 红色 | 组装失败，如未找到 Statement 定义 |

#### 支持的特性

| 特性 | 支持状态 |
|------|----------|
| 当前文件内的 SQL 片段引用 | ✅ 支持 |
| 跨文件的 SQL 片段引用（使用完整 namespace） | ✅ 支持 |
| 嵌套的 include 标签（最多 10 层） | ✅ 支持 |
| 循环引用检测和保护 | ✅ 支持 |
| 保留原始格式（缩进、换行、空格） | ✅ 支持 |
| 保留所有 MyBatis 动态标签 | ✅ 支持 |
| 从 Java Mapper 接口方法触发 | ✅ 支持 |

#### 使用示例

**示例 1：简单 include 替换**

原始 XML：
```xml
<select id="selectById" resultType="User">
    SELECT
    <include refid="baseColumns"/>
    FROM user
    WHERE id = #{id}
</select>

<sql id="baseColumns">
    id, username, email, phone, create_time, update_time
</sql>
```

组装后：
```sql
SELECT
id, username, email, phone, create_time, update_time
FROM user
WHERE id = #{id}
```

**示例 2：跨文件引用**

```xml
<select id="selectOrderList" resultType="Order">
    SELECT
    id, order_no, user_id, amount,
    <include refid="com.example.mapper.CommonMapper.commonFields"/>
    FROM t_order
    WHERE user_id = #{userId}
</select>
```

会自动在 `CommonMapper.xml` 中查找对应的 SQL 片段并替换。

---

### 2. Extract Method Information（提取方法信息）

#### 功能描述

该功能帮助开发者快速提取 Java 方法的基础信息，包括 HTTP 请求类型、请求路径、方法全限定名、类功能注释和方法功能注释，并输出到控制台。

#### 使用场景

- 快速了解 Controller 方法的 HTTP 请求信息
- 获取方法的完整限定名用于日志分析或文档编写
- 查看方法和类的功能注释，便于代码理解
- 分析 RESTful API 接口的路由信息

#### 触发方式

1. 打开任意 Java 文件
2. 将光标定位在方法内部或方法签名上
3. 右键点击，在弹出菜单中选择 **"Kiwi" → "Extract Method Information"**

#### 输出结果

执行该功能后，结果会输出到 **Kiwi Console** 控制台窗口，包括：

| 输出项 | 说明 |
|--------|------|
| 请求类型 | HTTP 请求方法（GET/POST/PUT/DELETE 等） |
| 请求路径 | 完整的 API 请求路径（类级别 + 方法级别） |
| 方法全限定名 | 格式：`包名.类名.方法名` |
| 类功能注释 | 类的 JavaDoc 功能描述（排除 @author 等技术标签） |
| 功能注释 | 方法的 JavaDoc 功能描述（排除 @param、@return 等技术标签） |

#### 支持的特性

| 特性 | 支持状态 |
|------|----------|
| Spring MVC 注解（@GetMapping、@PostMapping 等） | ✅ 支持 |
| JAX-RS 注解（@GET、@POST、@Path 等） | ✅ 支持 |
| @RequestMapping 的 method 属性解析 | ✅ 支持 |
| 类级别和方法级别路径组合 | ✅ 支持 |
| 重写方法的注释向上追溯 | ✅ 支持 |
| 接口实现类方法的注解继承 | ✅ 支持 |
| 排除技术性 JavaDoc 标签 | ✅ 支持 |

#### 使用示例

**示例：提取 Spring Controller 方法信息**

```java
/**
 * 用户管理控制器
 */
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    /**
     * 根据用户ID查询用户信息
     * @param id 用户ID
     * @return 用户详情
     */
    @GetMapping("/{id}")
    public User getUserById(@PathVariable Long id) {
        // ...
    }
}
```

输出结果：
```
请求类型: GET
请求路径: /api/users/{id}
方法全限定名: com.example.controller.UserController.getUserById
类功能注释: 用户管理控制器
功能注释: 根据用户ID查询用户信息
```

---

### 3. Get Top Callers Information（获取顶层调用者）

#### 功能描述

该功能帮助开发者快速查找 Java 方法或 MyBatis Statement 的所有顶层调用者（入口方法），自动递归分析调用链，找到所有最终的入口点，并输出每个入口方法的详细信息。

#### 使用场景

- 分析某个底层方法被哪些 API 入口调用
- 理解代码的影响范围，评估修改的风险
- 快速定位调用某个 MyBatis SQL 的所有 Controller 入口
- 代码审查时追溯调用链路

#### 触发方式

**方式一：在 Java 文件中触发**
1. 打开任意 Java 文件
2. 将光标定位在方法定义上，或定位在方法调用表达式上
3. 右键点击，在弹出菜单中选择 **"Kiwi" → "Get Top Callers Information"**

**方式二：在 MyBatis XML 文件中触发**
1. 打开 MyBatis Mapper XML 文件
2. 将光标定位在 `<select>`、`<insert>`、`<update>` 或 `<delete>` 标签内
3. 右键点击，在弹出菜单中选择 **"Kiwi" → "Get Top Callers Information"**

#### 输出结果

执行该功能后，结果会输出到 **Kiwi Console** 控制台窗口，包括：

| 输出项 | 说明 |
|--------|------|
| 源方法 | 被分析的起始方法全限定名 |
| 顶层调用者数量 | 找到的入口方法总数 |
| 方法全限定名 | 每个顶层调用者的完整方法路径 |
| 请求类型 | HTTP 请求方法（GET/POST/PUT/DELETE 等） |
| 请求路径 | 完整的 API 请求路径 |
| 类功能注释 | 类的 JavaDoc 功能描述 |
| 功能注释 | 方法的 JavaDoc 功能描述 |

#### 支持的特性

| 特性 | 支持状态 |
|------|----------|
| Java 方法定义上触发 | ✅ 支持 |
| 方法调用表达式上触发（分析被调用方法） | ✅ 支持 |
| MyBatis XML Statement 上触发 | ✅ 支持 |
| 递归调用链分析（最大深度 50 层） | ✅ 支持 |
| 接口实现类方法调用追溯 | ✅ 支持 |
| 循环引用检测和保护 | ✅ 支持 |
| 后台异步执行（不阻塞 IDE） | ✅ 支持 |

#### 使用示例

**示例：从 Service 方法查找 Controller 入口**

假设有如下代码结构：

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    @Autowired
    private OrderService orderService;
    
    /**
     * 查询订单详情
     */
    @GetMapping("/{id}")
    public Order getOrder(@PathVariable Long id) {
        return orderService.getOrderById(id);  // 调用 Service
    }
}

@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    
    @Override
    public Order getOrderById(Long id) {
        return orderMapper.selectById(id);  // 调用 Mapper
    }
}
```

在 `orderMapper.selectById(id)` 上右键选择 "Get Top Callers Information"，输出结果：
```
========== 顶层调用者分析结果 ==========
时间: 2024-12-22 10:30:00
源方法: com.example.mapper.OrderMapper.selectById
------------------------------------------
共找到 1 个顶层调用者:

【1】com.example.controller.OrderController.getOrder
    请求类型: GET
    请求路径: /api/orders/{id}
    类功能注释: (无)
    功能注释: 查询订单详情

==========================================
```

---

### 4. 更多功能（规划中）

以下功能已在规划中，将在后续版本中实现：

| 功能 | 描述 | 状态 |
|------|------|------|
| SQL 格式化 | 提供多种 SQL 格式化风格 | 规划中 |
| 结果预览窗口 | 在复制前预览组装结果 | 规划中 |
| 批量处理 | 支持选择多个 Statement 批量组装 | 规划中 |
| 智能感知 | 为 refid 属性提供自动补全 | 规划中 |
| 代码检查 | 检测未使用的 SQL 片段和无效引用 | 规划中 |
| Spring Bean 分析 | 分析 Spring Bean 依赖关系 | 规划中 |
| 配置文件分析 | 分析 Spring 配置文件关系 | 规划中 |

---

## 技术架构

### 整体架构

Kiwi 采用 DDD（领域驱动设计）分层架构，各层职责清晰，核心业务逻辑与技术实现解耦，便于维护和扩展：

```
┌─────────────────────────────────────────────────────────────┐
│                   Presentation Layer                        │
│                     （表示层/接口层）                          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              AssembleSqlAction                       │   │
│  │         处理用户右键菜单触发事件和 UI 交互              │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Application Layer                         │
│                      （应用层）                               │
│  ┌─────────────────────────────────────────────────────┐   │
│  │            ExpandStatementUseCase                    │   │
│  │       用例编排，协调领域服务完成业务流程                 │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Domain Layer                            │
│                      （领域层）                               │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ Domain Services                                       │  │
│  │  ┌────────────────────────┐ ┌──────────────────────┐ │  │
│  │  │StatementExpanderService│ │SqlFragmentResolver   │ │  │
│  │  │   核心展开服务（可复用） │ │  片段解析器接口       │ │  │
│  │  └────────────────────────┘ └──────────────────────┘ │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ Domain Models                                         │  │
│  │  ┌──────────────┐ ┌───────────────┐ ┌──────────────┐ │  │
│  │  │ExpandContext │ │StatementInfo  │ │AssemblyResult│ │  │
│  │  │  展开上下文   │ │ Statement信息 │ │  组装结果     │ │  │
│  │  └──────────────┘ └───────────────┘ └──────────────┘ │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  Infrastructure Layer                       │
│                     （基础设施层）                             │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐    │
│  │SqlFragment   │ │MapperIndex   │ │MyBatisXmlParser  │    │
│  │ResolverImpl  │ │  Service     │ │  XML 解析器      │    │
│  │ 片段解析实现  │ │ 文件索引     │ │                  │    │
│  └──────────────┘ └──────────────┘ └──────────────────┘    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │    ConsoleOutputService  │  NotificationService       │   │
│  │       控制台输出         │      通知管理              │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### DDD 分层说明

| 层级 | 职责 | 核心原则 |
|------|------|----------|
| **表示层 (Presentation)** | 处理用户交互，触发应用服务 | 只负责 UI 逻辑，不包含业务逻辑 |
| **应用层 (Application)** | 编排用例流程，协调领域服务 | 薄层设计，不包含业务规则 |
| **领域层 (Domain)** | 核心业务逻辑，可复用的展开能力 | 纯业务逻辑，不依赖技术框架 |
| **基础设施层 (Infrastructure)** | 技术实现，文件解析、索引等 | 实现领域层定义的接口 |

### 项目结构

```
src/main/kotlin/com/euver/kiwi/
├── action/                              # 表示层 - 用户交互
│   ├── AssembleSqlAction.kt             # MyBatis SQL 组装 Action
│   ├── ExtractMethodInfoAction.kt       # 提取方法信息 Action
│   └── FindTopCallerAction.kt           # 查找顶层调用者 Action ⭐
├── application/                         # 应用层 - 用例编排
│   └── ExpandStatementUseCase.kt        # 展开 Statement 用例（统一入口）
├── domain/                              # 领域层 - 核心业务逻辑
│   ├── model/
│   │   ├── ExpandContext.kt             # 展开上下文（跟踪状态和结果）
│   │   └── MethodInfo.kt                # 方法信息模型 ⭐
│   └── service/
│       ├── MethodInfoExtractorService.kt # 方法信息提取服务
│       ├── SqlFragmentResolver.kt       # 片段解析器接口（依赖倒置）
│       ├── StatementExpanderService.kt  # 核心展开服务（可复用）
│       └── TopCallerFinderService.kt    # 顶层调用者查找服务 ⭐
├── infrastructure/                      # 基础设施层 - 技术实现
│   └── resolver/
│       └── SqlFragmentResolverImpl.kt   # 片段解析器实现
├── model/                               # 共享数据模型
│   ├── StatementInfo.kt                 # Statement 信息模型
│   ├── SqlFragmentInfo.kt               # SQL 片段信息模型
│   └── AssemblyResult.kt                # 组装结果模型
├── parser/
│   └── MyBatisXmlParser.kt              # XML 解析器
└── service/                             # 基础服务
    ├── MapperIndexService.kt            # Mapper 索引服务
    ├── ConsoleOutputService.kt          # 控制台输出服务
    ├── NotificationService.kt           # 通知服务
    ├── SqlAssembler.kt                  # SQL 组装服务（已迁移到领域层）
    └── MyBatisSqlToolWindowFactory.kt   # 控制台窗口工厂
```

### 核心组件说明

#### StatementExpanderService（领域层核心服务）
- **职责**：Statement 展开的核心业务逻辑
- **特点**：**可复用组件**，其他功能模块可直接使用
- **功能**：
  - 递归解析 include 标签
  - 处理跨文件引用
  - 检测循环引用
  - 收集未找到的片段信息

#### MethodInfoExtractorService（领域层核心服务）
- **职责**：方法基础信息提取的核心业务逻辑
- **功能**：
  - 提取 HTTP 请求方法类型和请求路径
  - 解析 Spring MVC 和 JAX-RS 注解
  - 组合类级别和方法级别路径
  - 提取功能性 JavaDoc 注释（排除技术标签）
  - 支持重写方法的注释向上追溯

#### TopCallerFinderService（领域层核心服务）⭐
- **职责**：顶层调用者查找的核心业务逻辑
- **特点**：**异步执行**，不阻塞 IDE 主线程
- **功能**：
  - 递归分析方法调用链
  - 向上追溯找到所有入口方法（无调用者的方法）
  - 支持接口实现类的调用追溯
  - 检测循环引用，防止无限递归
  - 最大递归深度 50 层

#### ExpandStatementUseCase（应用层用例）
- **职责**：编排展开 Statement 的完整流程
- **功能**：
  - 提供统一入口，支持多种触发方式
  - 协调领域服务和基础设施层
  - 支持从 XML 标签、Mapper 方法等多种方式触发

#### SqlFragmentResolver（领域层接口）
- **职责**：定义 SQL 片段解析能力
- **设计**：依赖倒置原则，由基础设施层实现

#### AssembleSqlAction（表示层）
- **职责**：处理 MyBatis SQL 组装的用户右键菜单触发事件
- **功能**：
  - 判断当前文件类型（XML 或 Java）
  - 根据光标位置确定目标 Statement
  - 调用应用层服务并输出结果

#### ExtractMethodInfoAction（表示层）
- **职责**：处理提取方法信息的用户右键菜单触发事件
- **功能**：
  - 判断光标是否在 Java 方法上
  - 调用 MethodInfoExtractorService 提取方法信息
  - 将结果输出到 Kiwi Console

#### FindTopCallerAction（表示层）⭐
- **职责**：处理查找顶层调用者的用户右键菜单触发事件
- **功能**：
  - 支持 Java 文件和 MyBatis XML 文件触发
  - 识别方法调用表达式，分析被调用方法
  - 调用 TopCallerFinderService 查找顶层调用者
  - 为每个顶层调用者提取 MethodInfo 并输出

#### MapperIndexService
- **职责**：Mapper 文件索引管理
- **功能**：
  - 构建 namespace 到文件的映射缓存
  - 支持项目级别的文件索引
  - 提供 Mapper 文件快速查找能力

#### MyBatisXmlParser
- **职责**：XML 文件解析
- **功能**：
  - 解析 Mapper XML 文件结构
  - 提取 Statement 和 SQL 片段信息
  - 保留原始格式

#### ConsoleOutputService
- **职责**：控制台输出管理
- **功能**：
  - 创建和管理 Kiwi Console 窗口
  - 格式化输出组装结果
  - 处理剪贴板复制操作

#### NotificationService
- **职责**：用户通知管理
- **功能**：
  - 显示成功/警告/错误通知
  - 根据结果类型选择通知级别

### 关键技术实现

#### 1. PSI 系统使用
- 使用 `XmlFile` 和 `XmlTag` 解析 XML 结构
- 通过 `PsiTreeUtil` 查找父级标签
- 使用 `PsiJavaFile` 和 `PsiMethod` 解析 Java 接口

#### 2. 正则表达式解析
```kotlin
val includePattern = Regex("""<include\s+refid\s*=\s*["']([^"']+)["']\s*/?>""")
```
- 支持自闭合和非自闭合两种格式
- 正则表达式预编译，提高匹配效率

#### 3. 递归算法
- 从后向前替换，避免位置偏移
- 维护处理栈，检测循环引用
- 深度限制（最大 10 层），防止栈溢出

#### 4. 文件索引
- 使用 `FileTypeIndex` 查找所有 XML 文件
- 构建 namespace 到文件的映射缓存
- 项目级别服务，支持多个模块

---

## 详细设计

### 数据模型

#### StatementInfo
存储 MyBatis Statement 的基本信息：

```kotlin
data class StatementInfo(
    val statementId: String,      // Statement ID (如 selectById)
    val namespace: String,         // 命名空间
    val type: String,             // 类型 (select/insert/update/delete)
    val content: String,          // 原始内容
    val sourceFile: XmlFile       // 来源文件
)
```

#### SqlFragmentInfo
存储 SQL 片段信息：

```kotlin
data class SqlFragmentInfo(
    val fragmentId: String,       // 片段 ID
    val namespace: String,        // 命名空间
    val content: String,          // 片段内容
    val sourceFile: XmlFile       // 来源文件
)
```

#### AssemblyResult
存储组装结果：

```kotlin
data class AssemblyResult(
    val assembledSql: String,              // 组装后的 SQL
    val statementInfo: StatementInfo,      // 原始 Statement 信息
    val replacedIncludeCount: Int,         // 替换的 include 数量
    val missingFragments: List<...>,       // 未找到的片段列表
    val circularReferences: List<...>      // 循环引用列表
)
```

### 处理流程

#### XML 文件组装流程

```
用户右键点击
      │
      ▼
检查光标位置是否在 Statement 标签内
      │
      ├─ 否 ──→ 禁用菜单项
      │
      ▼ 是
提取 Statement 信息
      │
      ▼
#### 调用 ExpandStatementUseCase（应用层入口）
      │
      ▼
调用 StatementExpanderService.expand()
      │
      ▼
递归处理 include 标签
      │
      ├─ 检测循环引用 ──→ 记录并跳过
      │
      ├─ 查找 SQL 片段
      │     ├─ 当前文件查找
      │     └─ 跨文件查找（通过 MapperIndexService）
      │
      ▼
生成 AssemblyResult
      │
      ├─ 输出到 Kiwi Console
      ├─ 显示通知消息
      └─ 复制到剪贴板
```

#### Java 文件组装流程

```
用户右键点击
      │
      ▼
检查光标位置是否在接口方法上
      │
      ├─ 否 ──→ 禁用菜单项
      │
      ▼ 是
获取接口全限定名（namespace）和方法名（statementId）
      │
      ▼
通过 MapperIndexService 查找对应的 XML 文件
      │
      ├─ 未找到 ──→ 显示错误通知
      │
      ▼ 找到
在 XML 文件中查找对应的 Statement
      │
      ├─ 未找到 ──→ 显示错误通知 + 控制台输出
      │
      ▼ 找到
#### 调用 ExpandStatementUseCase（应用层入口）
      │
      ├─ 未找到 ──→ 显示错误通知 + 控制台输出
      │
      ▼ 找到
调用 StatementExpanderService.expand()
```

### 循环引用检测

使用栈结构检测循环引用：

```kotlin
// 检测循环引用
val refKey = "$targetNamespace.$fragmentId"
if (refKey in processingStack) {
    // 构建完整的循环引用链路
    val cyclePath = processingStack.subList(cycleStartIndex, processingStack.size)
    circularReferences.add(CircularReferenceInfo(cyclePath))
    continue  // 跳过此 include，保留原标签
}

// 将当前引用加入栈
processingStack.add(refKey)
// 递归处理...
// 处理完成后从栈中移除
processingStack.remove(refKey)
```

---

## 性能优化

### 已实现的优化

| 优化项 | 说明 |
|--------|------|
| 懒加载 | 仅在用户触发时才开始解析和组装 |
| 索引缓存 | 缓存 namespace 到文件的映射，避免重复扫描 |
| 正则缓存 | 正则表达式预编译，提高匹配效率 |
| 递归深度限制 | 防止深度过大导致的性能问题 |
| 从后向前替换 | 避免字符串位置偏移带来的性能损耗 |

### 性能指标

| 指标 | 参考值 |
|------|--------|
| 单个 Statement 组装时间 | < 100ms（正常场景） |
| 索引构建时间 | < 1s（约 100 个 Mapper 文件） |
| 内存占用 | < 10MB（索引缓存） |

---

## 安装与配置

### 安装方式

#### 方式一：从 JetBrains Marketplace 安装
1. 打开 IntelliJ IDEA
2. 进入 `Settings/Preferences` → `Plugins` → `Marketplace`
3. 搜索 "Kiwi"
4. 点击 `Install` 安装

#### 方式二：从本地文件安装
1. 下载 Kiwi 插件的 zip 文件
2. 进入 `Settings/Preferences` → `Plugins` → ⚙️ → `Install Plugin from Disk...`
3. 选择下载的 zip 文件
4. 重启 IDE

### 构建插件

```bash
# 设置 JDK 21
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home

# 构建插件
./gradlew clean buildPlugin
```

构建产物位于：`build/distributions/Kiwi-x.x.x.zip`

---

## 常见问题

### Q1: 为什么有些 include 标签没有被替换？

**可能原因：**
1. SQL 片段确实不存在，请检查 `<sql id="xxx">` 标签是否定义
2. 跨文件引用时 namespace 不正确，请确认 refid 格式为 `namespace.fragmentId`
3. XML 文件未被索引，可以尝试重启 IDEA 或清空缓存

### Q2: 如何查看详细的调试信息？

打开 IDEA 的日志文件：
- Help → Show Log in Finder/Explorer
- 搜索 "MyBatis SQL 组装" 相关的日志输出

### Q3: 组装后的 SQL 格式不美观怎么办？

当前版本保留原始格式，不进行格式化。后续版本会提供独立的 SQL 格式化组件。

### Q4: 在 Java 方法上右键但没有找到对应的 Statement？

请确认：
1. Java 接口的全限定名与 XML 文件的 namespace 一致
2. 方法名与 XML 中的 Statement ID 一致
3. 对应的 XML 文件存在于项目中

---

## 版本历史

### v0.0.42（当前版本）
- **新增「获取顶层调用者」功能**：在 Java 方法或 MyBatis XML Statement 上右键可查找所有顶层调用者
- 新增 `FindTopCallerAction` 表示层组件
- 新增 `TopCallerFinderService` 领域层核心服务
- 支持 Java 方法定义和方法调用表达式上触发
- 支持 MyBatis XML Statement 上触发
- 支持递归调用链分析，最大深度 50 层
- 支持接口实现类方法调用追溯
- `ConsoleOutputService` 新增 `outputTopCallersInfo` 方法

### v0.0.31
- **新增「提取方法信息」功能**：在 Java 方法上右键可提取 HTTP 请求类型、请求路径、全限定名和功能注释
- 新增 `ExtractMethodInfoAction` 表示层组件
- 新增 `MethodInfoExtractorService` 领域层核心服务
- 新增 `MethodInfo` 领域模型
- 支持 Spring MVC 和 JAX-RS 注解解析
- 支持重写方法的注释向上追溯
- `ConsoleOutputService` 新增 `outputMethodInfo` 方法

### v0.0.27
- **DDD 架构重构**：按领域驱动设计规范重构代码结构
- 新增领域层核心服务 `StatementExpanderService`（可复用）
- 新增应用层用例 `ExpandStatementUseCase`（统一入口）
- 新增领域模型 `ExpandContext`（展开上下文）
- 实现依赖倒置：`SqlFragmentResolver` 接口与实现分离

### v0.0.1
- 实现 Copy Expanded Statement 功能
- 支持 XML 文件和 Java Mapper 接口触发
- 支持跨文件 SQL 片段引用
- 支持循环引用检测
- 集成 Kiwi Console 控制台输出

---

## 技术依赖

| 依赖 | 版本 |
|------|------|
| IntelliJ Platform | 2023.1+ |
| Kotlin | 2.2.21 |
| Gradle | 9.2.1 |
| JDK | 21 |

---

## 贡献与反馈

如有问题或建议，欢迎提交 Issue 或 Pull Request。

**项目仓库**：https://github.com/euver/Kiwi

---

## 许可证

与 IntelliJ Platform Plugin Template 保持一致。
