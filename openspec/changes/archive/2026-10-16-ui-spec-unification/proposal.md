---
id: ui-spec-unification
status: proposed
title: 二级页标题/空态/格线 UI 设计规格统一（v2.0.12）
---

## Why

用户基于截图提出三项 UI 规格统一，并要求"设置为设计规格"（长期生效）：① 网盘与文件夹管理标题字体与定时关闭一致；② 未添加信息提示文本统一大小、位置与描述；③ 文件夹管理页与文件夹详情页添加格线。随后补一条：主页空态提示同样统一。

## Context

- ①现状（代码实证）：设置内嵌子页标题 = `titleMedium`（定时关闭/缓存/毛玻璃等）；`CloudConfigScreen`（WebDAV 账号/编辑页头）与 `FolderManageScreen`（文件夹管理/详情/picker 页头）为 `titleLarge`，不一致。
- ②现状：主页/网盘/文件夹管理/文件夹详情/目录选择器 5 处空态均为左对齐 `bodyLarge + onSurfaceVariant + top48`；文案风格不统一（部分无操作引导）。
- ③现状：文件夹管理列表行（`FolderManageRow`）与文件夹详情文件行（`ManageFileRow`）无分隔线；网盘账号列表/设置页/目录选择器已有 `HorizontalDivider`。
- 设计规格（已固化偏好）：二级页标题统一 `titleMedium`；空态统一 `bodyLarge + onSurfaceVariant + 顶部48dp + 水平居中 + 左右24dp`，文案"主句 + 操作引导"两行；列表行统一 `HorizontalDivider` 格线。
- 版本：当前 2.0.11/65（已提交未推送）；本改动升 patch 2.0.12/66；只构建 debug。

## What Changes

1. 标题规格：`CloudConfigScreen`（2 处）、`FolderManageScreen`（3 处）`titleLarge → titleMedium`。
2. 空态规格：5 处统一 `fillMaxWidth + padding(top=48, start/end=24) + textAlign=Center + bodyLarge + onSurfaceVariant`；文案优化（EMPTY_HOME / EMPTY_DIR_AUDIO / EMPTY_MANAGE / 该目录为空 / WebDAV 未添加，均为主句+操作引导）；同步更新测试断言。
3. 格线规格：文件夹管理列表行、文件夹详情文件行补 `HorizontalDivider`（同设置页/账号列表风格）。
4. 版本 2.0.12 / versionCode 66。

## Requirements

- **REQ-1（标题规格）**: 所有二级页面标题 MUST 使用 `titleMedium`（网盘配置页、文件夹管理页、文件夹详情页、目录选择器页头均含）。
- **REQ-2（空态规格）**: 所有"未添加信息"空态提示 MUST 统一为 `bodyLarge + onSurfaceVariant + 顶部48dp + 水平居中 + 左右24dp`；文案 MUST 采用"主句 + 操作引导"两行结构（主页、网盘配置、文件夹管理、文件夹详情、目录选择器 5 处）。
- **REQ-3（格线规格）**: 文件夹管理列表与文件夹详情文件列表的行间 MUST 添加 `HorizontalDivider` 格线（与设置页/网盘账号列表一致）。
- **REQ-4（验收）**: 233 单测全绿 + assembleDebug 通过；版本 2.0.12；真机验收三项规格。

#### Scenario: 二级页标题统一

- **WHEN** 进入网盘配置页、文件夹管理页、文件夹详情页、网盘目录选择器
- **THEN** 页头标题字号与定时关闭等设置子页（titleMedium）完全一致

#### Scenario: 空态统一

- **WHEN** 主页/网盘配置/文件夹管理/文件夹详情/目录选择器任一页面处于空态
- **THEN** 提示文本水平居中、字号 bodyLarge、颜色 onSurfaceVariant、顶部 48dp、左右 24dp，文案为主句+操作引导两行

#### Scenario: 列表格线

- **WHEN** 打开文件夹管理页列表或文件夹详情页文件列表
- **THEN** 行间有 HorizontalDivider 分隔线，视觉与设置页/网盘账号列表一致

#### Scenario: 真机验收

- **WHEN** 安装 v2.0.12
- **THEN** 标题/空态/格线三项规格符合预期，版本 2.0.12
