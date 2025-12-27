# Changelog

## [0.1.7]
### Added
- 添加 plugin.xml 中的 description 标签，满足 JetBrains 插件市场上传要求：
  - 描述以拉丁字符开头，超过 40 个字符
  - 包含插件三大核心功能介绍
  - 包含技术亮点说明（DDD 架构、BFS 算法、Lambda 追溯等）

## [0.1.6]
### Changed
- 将 Excel 导出库从 Apache POI 替换为 FastExcel，插件体积从 16MB 降至约 400KB
- 重构 Excel 导出功能，采用 DDD 架构：
  - 新增 `ExcelExporter` 接口（领域层）
  - 新增 `FastExcelExporter` 实现（基础设施层）
  - 便于后续切换不同的 Excel 导出实现

## [0.1.5]
- 优化注释提取逻辑：注释中某行包含 @ 则整行不取

## [0.1.4]
- 修复类功能注释和方法功能注释可能包含 @ 标签内容的问题：
  - 重构 getFunctionCommentFromDoc() 方法，只提取纯描述部分
  - 新增 removeAtTagContent() 方法，过滤文本中间的 @ 标签内容
  - 移除非技术性标签的处理逻辑，完全排除所有 @ 标签
  - 清理不再使用的 PsiDocTag import

## [0.1.3]
- Get Top Callers Information 功能进一步增强匿名类/未知类处理：
  - 扩展匿名类检测范围：不仅检查 PsiAnonymousClass，还检查 qualifiedName 为 null 的情况
  - 方法重命名：getEnclosingMethodOfAnonymousClass → getEnclosingMethodOfAnonymousOrUnknownClass
  - 优化函数式接口方法检测：使用 findDeepestSuperMethods() 检查实际父方法
  - 修复 Consumer.accept 等场景下显示为 ".UnknownClass.accept" 的问题
  - 支持消息处理框架（如 IMessageProcessor）中的调用链追溯

## [0.1.2]
- Get Top Callers Information 功能新增匿名内部类调用链追溯支持：
  - 新增匿名内部类检测逻辑：当方法在匿名类中时，自动向上追溯到包含该匿名类定义的外部方法
  - 新增 getEnclosingMethodOfAnonymousClass() 方法，与 IDEA 原生 Hierarchy 行为完全一致
  - 修复 Future/Callable 场景下显示为 ".UnknownClass.call" 的问题
  - 优化匿名类方法信息显示：格式为 "Anonymous in methodName() in ClassName"
  - 更新 MethodInfoExtractorService 支持匿名类的包名、类名、全限定名提取
  - 支持的调用链场景新增：匿名内部类调用（追踪到包含匿名类定义的外部方法）

## [0.1.1]
- 插件名称从 "Kiwi" 全面更名为 "Kiwin"
  - 更新插件显示名称和 ID
  - 更新所有包名（com.euver.kiwi → com.euver.kiwin）
  - 更新右键菜单组名称
  - 更新控制台窗口名称（Kiwi Console → Kiwin Console）
  - 更新图标文件（kiwi.svg → kiwin.svg）
  - 更新所有文档和配置文件中的引用

## [0.1.0]
- 版本号调整至 0.1.0

## [0.0.100]
- Get Top Callers Information 功能与 IDEA 原生 CallerMethodsTreeStructure 完全对齐：
  - 新增 Javadoc 引用过滤：跳过 Javadoc 中的引用（与原生 Hierarchy PsiUtil.isInsideJavadocComment 一致）
  - 新增类型关联性检查：使用 areClassesRelated/areClassesDirectlyRelated 过滤不相关类的引用
  - 新增 InheritanceUtil.isInheritorOrSelf 继承关系检查
  - 增强异常处理：在 findAllCallersInternal 和 findLambdaDeclarationCallersInternal 中捕获 IndexNotReadyException，避免卡住
  - 所有搜索方法统一处理 ProcessCanceledException，确保用户可随时取消
  - 更新类文档注释，明确与 IDEA 原生 CallerMethodsTreeStructure 的一致性

## [0.0.99]
- Get Top Callers Information 功能完全对齐 IDEA 原生 Hierarchy 实现：
  - 使用 findDeepestSuperMethods() 替代 findSuperMethods()，查找最深层父方法，与原生 Hierarchy 一致
  - 将整个 BFS 搜索过程包装在单个 runReadActionInSmartMode 中，减少多次调用开销
  - 所有内部方法重命名为 xxxInternal，明确在 ReadAction 上下文中调用
  - 彻底避免 IndexNotReadyException，与 IDEA 原生 Hierarchy 性能和准确性完全一致
  - 精简代码结构，从 372 行减少到 350 行

## [0.0.98]
- Get Top Callers Information 功能索引模式处理优化：
  - 使用 runReadActionInSmartMode 替代 ReadAction.compute，完全避免 IndexNotReadyException
  - 所有搜索操作自动等待 Smart Mode，与 IDEA 原生 Hierarchy 实现机制一致
  - 移除单独的 isDumb/waitForSmartMode 检查（已内置在 runReadActionInSmartMode 中）
  - 优化类文档注释，明确实现机制

## [0.0.97]
- Get Top Callers Information 功能性能根本优化：
  - 使用 IDEA 原生的 GlobalSearchScopesCore.projectProductionScope 替代自定义 scope，性能与原生 Hierarchy 一致
  - 移除所有截断限制（MAX_CALLERS_PER_METHOD、MAX_TOP_CALLERS、MAX_VISITED_METHODS），保证结果完整性
  - 移除双重 isInTestSourceContent 判断，避免冗余的性能消耗
  - 保留搜索结果缓存机制，避免重复搜索相同方法

## [0.0.96]
- Get Top Callers Information 功能性能深度优化：
  - 新增搜索结果缓存：避免重复搜索相同方法，显著提升调用链分析速度
  - 新增增量搜索机制：使用 Iterator 替代 findAll，支持快速取消，减少内存占用
  - 新增广度限制：每层最大调用者数量限制（100），防止热门方法导致的队列爆炸
  - 新增智能提前终止：找到足够多顶层调用者（500）或访问方法数达到上限（2000）后停止
  - 优化进度指示器：实时显示处理进度、已找到顶层调用者数量和队列状态
  - 新增 clearAllCaches() 公开方法用于手动清理缓存

## [0.0.95]
- Get Top Callers 表格右键菜单文本国际化："跳转到源码" → "Go to Source"，"跳转到XML" → "Go to XML"，"复制" → "Copy"

## [0.0.94]
- 调整右键菜单 Kiwin 目录内菜单项顺序，将 "Extract Method Information" 置于第一位

## [0.0.93]
- 弹出面板标签"源位置"改为英文 "Source"
- Excel 导出文件名优化：使用触发的 ID 或方法名而非完整路径

## [0.0.92]
- Get Top Callers Information 功能数据展示差异化调整：
  - 弹出表格面板（简洁模式）：移除 Package 列，StatementID 仅显示 ID 部分（去除 namespace），新增 Statement Comment 列
  - 控制台输出和 Excel 导出（完整模式）：Method 使用全限定名格式，StatementID 显示完整格式，新增 Statement Comment 列
- TopCallerWithStatement 实体类新增 statementComment 字段和 getSimpleStatementId() 方法
- 优化表格列结构，提升界面简洁性和输出完整性的平衡

## [0.0.91]
- 更新 Kiwi产品说明书.md 和 README.md 文档
- 完善 TopCallerFinderService 技术文档描述：
  - 新增广度优先搜索（BFS）算法说明
  - 新增性能优化特性：方法键缓存、生产代码范围缓存、批量查找、并发安全
  - 新增异常处理机制：Dumb 模式等待、取消操作支持
  - 补充完整的函数式接口方法过滤列表
  - 更新关键方法说明，与代码实现保持一致
