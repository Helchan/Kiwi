# Kiwi Changelog

## [0.0.74]
### Added
- 顶层调用者 TreeTable 表格新增默认排序规则，初始化时自动应用
  - 第一优先级：类型（API 排在 Normal 前面）
  - 第二优先级：请求路径（升序）
  - 第三优先级：方法全限定名（升序）
- 子节点（StatementID）跟随其对应的父节点（顶层调用者）排序位置

## [0.0.73]
### Changed
- 同步更新产品说明书和 README.md 文档，确保与代码功能保持一致
- 补充 SQL 片段顶层调用者查找功能说明（支持 `<sql>` 标签和 `<include>` refid 触发）
- 补充 TreeTable 层级展示组件介绍及交互功能说明
- 补充 Excel 导出功能说明
- 更新项目结构，添加新增的领域模型和服务组件

## [0.0.72]
### Changed
- Excel导出列宽调整为自适应宽度+30像素，超过300像素则按300像素

## [0.0.71]
### Changed
- Excel导出列宽最大限制从50字符调整为300像素，确保内容完整显示
- 列宽自适应内容，超过300像素时强制限制

## [0.0.70]
### Fixed
- 修复“Get Top Callers Information”弹出表格的右键菜单混乱问题
- StatementID 列有内容的单元格：右键菜单显示“跳转到XML”、“Copy Expanded Statement”、“复制”
- 其他列（包括顶层调用者列）有内容的单元格：右键菜单显示“跳转到源码”、“复制”
- 无内容的单元格不显示右键菜单

## [0.0.69]
### Changed
- 实现顶层调用者表格列宽自适应功能，支持响应式布局
- 表格宽度自动占满弹出面板的可用宽度
- 当用户调整面板大小时，表格自动调整宽度以贴合面板边缘
- 各列宽度根据内容最大宽度按比例分配总宽度
- 同时支持 TopCallersTableDialog 和 TopCallersTreeTableDialog 两种表格组件

## [0.0.68]
### Fixed
- 修复 TreeTable 右键菜单功能丢失问题
- 修复坐标转换逻辑，确保点击任意位置都能正确触发右键菜单
- 同时在 treeTable 和 tree 组件上添加鼠标监听器

## [0.0.67]
### Changed
- 重构顶层调用者展示组件：将 JBTable 替换为 TreeTable 实现真正的层级展示
- 顶层调用者作为父节点，StatementID 作为子节点，支持展开/折叠
- 保持所有原有功能：右键菜单、跳转功能、复制功能、Excel 导出（合并单元格）
- UI 层使用 TreeTable，导出层仍使用 POI CellRangeAddress 实现真正的单元格合并

## [0.0.66]
### Fixed
- 修复单元格合并渲染器的行索引转换问题（视图行 -> 模型行）
- 改进合并单元格的背景色处理，保持选中状态一致性
- 添加合并信息调试日志

## [0.0.65]
### Changed
- StatementID 列宽度设置为自适应模式，最大宽度限制为 200 像素

## [0.0.64]
### Changed
- 调整顶层调用者表格 StatementID 列位置从第一列移动到最后一列

## [0.0.63]
### Added
- SQL 片段模式下顶层调用者表格新增 "StatementID" 列，展示引用该 SQL 片段的 Statement 全局 ID
- StatementID 列支持右键跳转到对应的 MyBatis Mapper XML 位置
- StatementID 列支持右键 "Copy Expanded Statement" 功能
- 单元格合并逻辑：同一顶层调用者涉及多个 Statement 时，顶层调用者信息列自动合并
- Excel 导出支持单元格合并

### Changed
- 仅在 SQL 片段上触发（<sql> 标签 ID 或 <include> refid）时才显示 StatementID 列

## [0.0.62]
### Changed
- 优化顶层调用者表格列宽自适应逻辑：所有列根据内容自动计算宽度，最大限制80字符

## [0.0.61]
### Fixed
- 修复顶层调用者表格序号和类型列宽度过大的问题，优化列宽自适应逻辑

## [0.0.60]
### Changed
- 构建发布版本

## [0.0.59]
### Changed
- 准备首次 Release 版本发布

## [0.0.58]
### Changed
- 全面更新 Kiwi 产品说明书，详细补充 TopCallerFinderService 的功能说明
- 新增调用链场景说明：直接方法调用、接口/父类方法调用、Lambda表达式调用、方法引用调用
- 新增技术特点说明：递归深度限制、函数式接口方法过滤、生产代码范围搜索
- 新增函数式接口方法过滤列表说明（JDK函数式接口、Spring框架回调）
- 新增 Lambda 表达式和方法引用调用追溯的使用示例
- 更新 README.md 功能介绍，突出调用链分析核心能力
- 完善 TopCallerFinderService 核心组件说明，增加关键方法说明

## [0.0.57]
### Fixed
- 修复TopCallerFinderService在遇到Lambda表达式/函数式接口时调用链中断的问题
- 新增Lambda表达式声明位置追踪功能，支持追溯到Lambda声明所在的方法
- 新增方法引用(Class::method)追踪功能
- 新增JDK函数式接口方法过滤逻辑，过滤Consumer.accept、Function.apply等不应作为顶层调用者的接口方法
- 支持Spring框架常见回调接口的识别（TransactionCallback、RowMapper等）

## [0.0.56]
### Previous
- 初始版本功能

## [0.0.18]
- 将插件ID从 com.euver.template 更改为 com.euver.kiwi
- 清理冗余代码和未使用的模块
- 移除未使用的 IncludeRefInfo 模型类
- 更新项目文档

## [0.0.17]
- 优化控制台输出格式
- 修复循环引用检测逻辑

## [0.0.1]
- 初始版本
- 实现 Copy Expanded Statement 功能
- 支持 XML 文件和 Java Mapper 接口触发
- 支持跨文件 SQL 片段引用
- 支持循环引用检测
- 集成 Kiwi Console 控制台输出
