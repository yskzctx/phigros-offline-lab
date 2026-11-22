# 变更记录

按时间倒序。所有版本均未真机验收，电脑端检查见 `tools/run_all_tests.py`。

---

## v186 — 导入导出存档实测修复 + 4.1.0 曲库

- **修复存档导入的两个真实缺陷**（由新增往返测试发现）：
  - 导出的 ZIP 只能读出第一个条目 —— `unzip` 用 `drain()`，其 `finally` 关闭流，对 `ZipInputStream` 等于结束整个压缩包
  - 损坏压缩包抛裸 `EOFException`，消息为 null
- 新增 `SaveTransferTest`：检查 → 导出 → 重新导入 → 应用 → 恢复 → 拒绝非法输入，断言恢复后与原文件逐字节相同
- 新增 `tests/shim/android/util/Log.java`（`android.jar` 的 Log 是抛 `Stub!` 的桩）
- 曲库切换为 4.1.0 的 1072 条
- 测试 24 项

## v185 — 4.1.0 曲库同步

- 从 4.1.0 的 Addressables 目录重建曲库：**1072 谱面 / 329 首**（157 是 1037/326）
- 解密关键：地址表每项是 `u8 类型标签 + u32 长度 + 内容`，**标签 1 = UTF-16**，按 UTF-8 全解会在第一个中文歌名处断掉
- 157 的 1037 条全部保留，新增 35 条（Legacy / SP / Random / AT）
- 35 个新谱面定数缺失 → 留空 → NaN → 从 RKS 计算排除；`RksPlanner.known()` 统一过滤，`UnknownConstantTest` 锁住
- 组装脚本新增 `--catalog`

## v184 — 导入导出存档 + 修 Activity 未注册

- 新增「导入 / 导出存档」：原始 `playerprefs.xml` 或面板 ZIP，先检查再写、自动备份、一键回退
- **修复 `SkinActivity` / `SaveTransferActivity` 未在 manifest 声明** —— 点「导入皮肤」必抛 `ActivityNotFoundException` 崩溃
- 新增 `register_activities.py`（apktool 重建二进制 manifest）和 `check_activities_declared.py`
- 组装脚本新增 `--manifest`
- 构建迁移到 NTFS：exFAT 卷上 3.3GB APK 出现 CRC 错误

## v183 — 修复缺库闪退

- **修复启动即崩**：`OfflineApplication` 加载 `libphigros_privacy`、`SkinController` 加载 `libphigros_skins`，但 `NATIVE_TARGETS` 只编了 `libphigros_offline`。缺任一 → `attachBaseContext` 抛 `UnsatisfiedLinkError`
- 三个库全部编译；`verify()` 从 Java 源码的 `System.loadLibrary` 反推必需库，缺失则构建失败

## v182 — 结算符号 + Good 早晚（含缺陷）

- 新增结算符号（Φ / FC）与 Good 早/晚
- `RksEditor` 去掉写死的 1037 条，改为校验非空 + key 唯一 + 定数合理
- **此版本有缺陷**：缺 privacy/skins 两个原生库，启动即崩。已被 v183 取代

## v181 — 判定保护 / 音符大小 / 结算显示 / 成绩同步（含缺陷）

- **判定保护 `mode=3` 完全不可用** —— Java 与原生两层同时拒绝，已修
- **音符大小不一致** —— 只锁宽度，高度随 PNG 长宽比漂移，改为两轴锁定
- **成绩无法同步** —— 成绩写入被吞；HTTP 强制 403 又抑制了宿主本地记账。移除两个 HTTP hook
- 新增「结算页强制显示」独立开关
- **此版本有缺陷**：组装脚本把修改版代码写进 `classes.dex`（游戏本体），删掉整个游戏，启动即崩。已修：改写 `classes2.dex`，并把 `classes.dex` 加入保护列表；新增 `verify_apk_dex.py`

## v180 — 补齐全解锁表遗漏项

- 补齐 12 项因键名格式差异遗漏的曲目解锁与曲绘
- 新增「仅全解锁（保留成绩）」
- 「恢复上次备份」同步回退解锁状态

## v177 — 随机 K 判定与成绩保护

- 新增随机 K 判定、自动游玩触摸屏蔽开关、S/FC/AT 成绩保护与额外保护分数
- 历史种子改为每次冷启动增量合并：已有成绩保持不变，只补齐新曲目
- 判定保护改为必须开启自动游玩、临界接管

## v174 / v173 — 判定规则与目标预估

- 判定规则（按音符类型、位置、序号指定判定）
- 目标 RKS 反推预估
- 173 为正式发行版

## v172 — 预发行

- 游戏内容 4.0.1(157) arm64-v8a

## v167 — 资源单份存储修复

## v166 — 模式校验与规则列表修复

## v165 — 修改与验证记录

---

## 早期验证记录

165 / 166 / 167 的详细验证数据已归档到 `docs/archive/`。
