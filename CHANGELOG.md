# Kiwi Changelog

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
