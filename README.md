# Phigros 离线修改版

**当前预发行：[v186](https://github.com/yskzctx/phigros-offline-lab/releases/tag/v186-pre.1)** · 包名 `com.PigeonGames.Phigros.offline` · 无网络权限 · 不提供登录

> **本项目的任何版本都未经过真机验收。** 电脑端能验证的是编译、签名、16KB 对齐、CRC、dex 分离、资源完整性、原生库齐全和 25 项回归测试；**不能**替代手机上启动、整局游玩和结算验证。

用途：仅限本地。此修改版不能登录账号，不提供排行榜、云存档或其他线上操作，不声明网络权限。

---

## 三个产品

本项目包含三个独立产物，各自定位不同：

| 产品 | 产物 | 宿主 | 联网 |
|---|---|---|---|
| **离线修改版** | `Phigros-offline-v186.z01/.z02/.z03/.zip` | 自带游戏数据（4.0.1 / 157） | ❌ 无 INTERNET 权限 |
| **Phigros 模块** | `Phigros-Lab-LSPosed-157-161-arm64-v0.7.1-TEST.apk` | 原版 4.0.1(157) **或** 4.1.0(161) | ✅ 继承宿主 |
| **Phira 模块** | `Phira-Lab-LSPosed-0.8.3-arm64-v0.5.0-TEST.apk` | Phira 0.8.3 arm64 | ✅ 继承宿主 |

模块仓库：[Phigros 模块](https://github.com/yskzctx/phigros-lsposed) · [Phira 模块](https://github.com/yskzctx/phira-lsposed)

**关键区别**：离线版是独立 APK，完全不联网；两个模块注入原版进程，保留宿主联网能力（登录、云存档、排行榜正常）。

---

## 一、离线修改版

独立 APK，自带游戏数据，无网络权限。

### 功能

**这局怎么打（判定保护 `mode=3`）**
- 三种保护：保护 AP、保护 FC、保护分数
- 必须开启「自动游玩」开关才会接管（这是该功能的语义，不是 bug）
- 分数仍在目标内时保留真实触摸；即将掉出目标时在 Perfect 窗口接管
- 支持显式判定规则（按音符类型、位置、序号指定判定）

**结算页强制显示**
- 8 个独立字段：分数、准确率、Perfect、Good、Bad、Miss、最大连击
- **结算符号**：不干预 / Φ（全 Perfect）/ FC（无 Bad、Miss）。符号不是独立字段，是游戏用判定数量派生的，所以选 Φ 会把 Perfect 设为判定总数、其余归零
- **Good 早/晚**：可选开关，单独指定早/晚计数，默认关闭
- 独立开关，与自动游玩、判定保护互不影响
- 接受真实一局不可能出现的组合（强制显示的目的就在于此）

**目标历史 RKS**
- 支持四位小数，上限 17.32（= 恢复全部 AP）
- 通过反推每首歌的成绩来达成目标 RKS

**恢复历史成绩与全解锁**
- 「全解锁 ＋ 全部 AP」：解锁全部曲目、收藏品、曲绘、头像，并设置全 AP
- 「仅全解锁」：保留当前成绩
- 「恢复上次备份」：回退上次修改前的状态

**导入 / 导出存档**
- 导入游戏原始 `playerprefs.xml`，或本面板导出的 ZIP
- 先检查（来源、条目数、大小）再写入，写入前自动备份
- 备份列表一键回退；恢复操作限制在备份目录内
- 不解密不重加密，原样拷贝，账号身份和设置完整保留
- 导入后需完全退出重启（游戏把配置缓存在内存）

**我的自制章节**
- 导入带音乐和插画的本地 `.pez` / ZIP 谱包
- 记录独立保存

**音符皮肤**
- 导入 Phira 标准音符图包（蓝键、黄键、红键、长条、复押）
- 音符大小两轴锁定（修复了只锁宽度导致高度漂移的问题）

**初始 Data**
- 首次使用准备至少 10000 MB 本地 Data，只补一次，不减少更高余额

### 曲库

`data/chart-catalog.tsv` = **1072 谱面 / 329 首**（4.1.0 数据）

从 4.1.0 的 `assets/aa/catalog.json` 重新生成：
- 地址表格式：`u32 数量` + 每项 `u8 类型标签 + u32 长度 + 内容`，**标签 1 = UTF-16**（中文/日文歌名）
- 地址格式 `Assets/Tracks/<歌名>.<曲师>.<编号>/Chart_<难度>.json`，对应曲库的 `<歌名>.<曲师>.<编号>.Record.<难度>`
- 1072 这个数字与 UnityPy 独立扫谱面 TextAsset 的计数一致（交叉验证）
- 157 的 1037 条全部保留，新增 35 条（Legacy / SP / Random / AT 等）

**35 个新谱面没有定数**（地址表里不含）。处理方式：定数留空 → 解析为 NaN → 从 RKS 计算排除。不编造数值，`UnknownConstantTest` 锁住这个行为。

### 已知限制

- **本体仍是 4.0.1(157) 的游戏数据**，只有曲库表是 4.1.0 的
- 未真机验收

---

## 二、Phigros LSPosed 模块

注入原版进程，**不修改 APK、不改签名**。保留宿主联网能力。

### 支持版本

按**函数序言匹配**自动选钩子表，不按版本号猜：
- 4.0.1(157)：`core/expected-hooks.h`
- 4.1.0(161)：`core/expected-hooks-161.h`
- 157 表不匹配时自动落到 161 表；两版 `bad_call` 也不同（`0x1d875c4` / `0x1d89dcc`）

### 功能

与离线版共享大部分实现（判定保护、结算显示、RKS、全解锁、存档导入导出、皮肤、自制章节），差异：

- **不注入触摸**，因此判定保护通过 `active_plan()` 生效而非 `active()`
- **可解锁全部内容**：1072 谱面 / 329 首
- **不隐藏登录**（见下方边界）

### 边界：模块绝不隐藏或阻止登录

登录隐藏只属于离线版（它本来就不联网）。模块运行在原版游戏里，登录、账号、云同步都应正常工作。

`tools/check_module_no_login_block.py` 强制这条边界，检查三项：
- 模块源码不含 `privacy_hooks` / `hide_account` / `login_hidden` 等符号
- 产物不包含 `libphigros_privacy.so`
- 原生库不导出相关符号

### 已知限制

- **4.1.0 皮肤渲染不可用**：12 个 Unity 辅助 RVA 已恢复 7 个，5 个未解（见 `profiles/161.json` 的 `auxNote`）。已做安全降级（能加载、不装错 hook、不崩）
- 未真机验收

---

## 三、Phira 模块

注入 Phira 0.8.3 arm64。

### 功能

- **这局怎么打**：判定保护 `mode=3`
- **结算时间误差**：目标 ±ms
- **结算页强制显示**：分数、准确率、P/G/B/M、最大连击、**结算符号**、**Good 早/晚**
- **Phira 历史记录**：目标 RKS
- **恢复历史成绩**

Phira 原生支持自制谱和自制音符，模块不干预这部分。

### 已修的 bug

判定保护在模块里曾完全无效：钩子只看 `enabled`（自动游玩开关），而模块从不开启自动游玩。已拆分为 `active()`（触摸注入）和 `active_plan()`（计划判定）。

---

## 构建与验证

```bash
python tools/run_all_tests.py          # 全部 25 项检查
python tools/run_all_tests.py --quick  # 跳过 NDK/JDK 交叉编译
```

测试覆盖：源码副本一致性、profile 审计、C 回归（判定规划、原生分发、皮肤缩放）、Java 回归（判定保护、皮肤样式、强制结算、未知定数、存档往返）、模块编译、原生库完整性、网络边界、登录边界、dex 分离。

### 关键工具

| 工具 | 用途 |
|---|---|
| `assemble_offline_apk.py` | 组装离线 APK（`--manifest` 覆盖、`--catalog` 选曲库） |
| `verify_apk_dex.py` | 断言 `classes.dex` 与底包逐字节一致 |
| `verify_struct_offsets.py` | 验证结构偏移在新版本仍成立 |
| `register_activities.py` | 用 apktool 把 Activity 注册进二进制 manifest |
| `check_activities_declared.py` | 源码里启动的每个 Activity 必须已声明 |
| `check_module_no_login_block.py` | 模块不得隐藏登录 |
| `extract_410_catalog.py` | 从 Addressables 重建曲库 |
| `audit_profile.py` | 三信号审计 hook 偏移 |
| `split_release.py` | 标准 ZIP 分卷 |

### 构建注意

- **在 NTFS 上构建**。exFAT 卷上 3.3GB APK 出现过 CRC 错误。
- 离线版必须无 INTERNET 权限、`classes.dex` 保持原样、三个原生库齐全（`phigros_offline` / `phigros_privacy` / `phigros_skins`，缺任一启动即崩）。

---

## 历史

变更记录见 [docs/CHANGELOG.md](docs/CHANGELOG.md)。早期验证记录归档在 [docs/archive/](docs/archive/)。

## 免责声明

见 [DISCLAIMER.md](DISCLAIMER.md)。
