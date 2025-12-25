# Changelog

## 0.0.95
- Get Top Callers 表格右键菜单文本国际化："跳转到源码" → "Go to Source"，"跳转到XML" → "Go to XML"，"复制" → "Copy"

## 0.0.94
- 调整右键菜单 Kiwi 目录内菜单项顺序，将 "Extract Method Information" 置于第一位

## 0.0.93
- 弹出面板标签“源位置”改为英文 "Source"
- Excel 导出文件名优化：使用触发的 ID 或方法名而非完整路径

## 0.0.92
- Get Top Callers Information 功能数据展示差异化调整：
  - 弹出表格面板（简洁模式）：移除 Package 列，StatementID 仅显示 ID 部分（去除 namespace），新增 Statement Comment 列
  - 控制台输出和 Excel 导出（完整模式）：Method 使用全限定名格式，StatementID 显示完整格式，新增 Statement Comment 列
- TopCallerWithStatement 实体类新增 statementComment 字段和 getSimpleStatementId() 方法
- 优化表格列结构，提升界面简洁性和输出完整性的平衡

## 0.0.91
- 更新 Kiwi产品说明书.md 和 README.md 文档
- 完善 TopCallerFinderService 技术文档描述：
  - 新增广度优先搜索（BFS）算法说明
  - 新增性能优化特性：方法键缓存、生产代码范围缓存、批量查找、并发安全
  - 新增异常处理机制：Dumb 模式等待、取消操作支持
  - 补充完整的函数式接口方法过滤列表
  - 更新关键方法说明，与代码实现保持一致
