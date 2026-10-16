# ui-spec-unification Specification

## Purpose
TBD - created by archiving change ui-spec-unification. Update Purpose after archive.

## Requirements

### Requirement: 标题规格

所有二级页面标题 MUST 使用 `titleMedium`（网盘配置页、文件夹管理页、文件夹详情页、目录选择器页头均含），与定时关闭等设置子页一致。

#### Scenario: 二级页标题统一

- **WHEN** 进入网盘配置页、文件夹管理页、文件夹详情页、网盘目录选择器
- **THEN** 页头标题字号与定时关闭等设置子页（titleMedium）完全一致

### Requirement: 空态规格

所有"未添加信息"空态提示 MUST 统一为 `bodyLarge + onSurfaceVariant + 顶部48dp + 水平居中 + 左右24dp`；文案 MUST 采用"主句 + 操作引导"两行结构（主页、网盘配置、文件夹管理、文件夹详情、目录选择器 5 处）。

#### Scenario: 空态统一

- **WHEN** 主页/网盘配置/文件夹管理/文件夹详情/目录选择器任一页面处于空态
- **THEN** 提示文本水平居中、字号 bodyLarge、颜色 onSurfaceVariant、顶部 48dp、左右 24dp，文案为主句+操作引导两行

### Requirement: 格线规格

文件夹管理列表与文件夹详情文件列表的行间 MUST 添加 `HorizontalDivider` 格线（与设置页/网盘账号列表一致）。

#### Scenario: 列表格线

- **WHEN** 打开文件夹管理页列表或文件夹详情页文件列表
- **THEN** 行间有 HorizontalDivider 分隔线，视觉与设置页/网盘账号列表一致
