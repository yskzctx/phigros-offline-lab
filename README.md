# Phigros 离线共存测试项目

**最新更新：174** · [发布与下载](https://github.com/yskzctx/phigros-offline-lab/releases/tag/v174) · [安装说明](docs/INSTALL-174.md) · [更新记录](CHANGELOG-174.md)

174 已更新到普通发行渠道。**尚未完成真机验收**：电脑端签名、资源检查、JNI 和解压验证不能替代手机启动与游玩测试。167 的储存修复和既有控制功能保留。

## 下载与安装

在 [174 发行页](https://github.com/yskzctx/phigros-offline-lab/releases/tag/v174) 下载全部三卷：

- `Phigros-offline-v174.z01`
- `Phigros-offline-v174.z02`
- `Phigros-offline-v174.zip`

保持文件名、放在同一文件夹，**打开最后的 `.zip` 解压**，没有密码。解出一个 APK，分卷本身不能安装。另附 `SHA256SUMS.txt`，下载或解压异常时先核对各卷哈希。换格式不代表手机端间歇下载损坏的原因已被修复。

包名 `com.PigeonGames.Phigros.offline`，基于4.0.1（157）arm64。独立数据目录，签名与173一致；备份修改版数据后覆盖安装，不卸载、不清除原版或修改版数据。运行不需要 root 或 LSPosed。不声明网络权限，不用于在线排行榜或真实成绩证明。

## 174 的变化与边界

- 修复自制谱首次 JNI 字符串转换和路径错误分类，增加应用内导出、删除。
- 调整 Bad 提前触发和未击中清理路径，实际画面与音效待设备确认。
- 自制章节皮肤保留特效、音效和 Perfect/Good 颜色，缺失时才用默认资源。旧导入丢失的文件需要从原包重新导入。
- 改用标准 ZIP 分卷；ZIP4J、7-Zip、SevenZipJBinding 均已完整解压并核对 APK 哈希。

**Phigros 原生章节的扩展特效、音效与判定线颜色尚未完成。** 自制谱、自动游玩 Bad 和完整皮肤资源实际效果仍待真机验收。详细记录见 [验证文件](docs/VERIFICATION-174.json)。

对应修改源码位于 `chapter-player/` 及其 `android-source/`；完整自制谱播放器对应源码随发行附件提供。根目录 `src/` 是历史快照。仓库不是从零构建整个游戏的工程。

## 两个 LSPosed 模块

[独立模块仓库](https://github.com/yskzctx/phigros-phira-lsposed-lab) 的旧0.1候选已撤回：用户报告 Phira 启用失败，功能也没有达到修改版同等范围。正在修复启用诊断与界面，并继续实现缺失功能。**当前没有可用模块交付。** Phira 原有自制谱与皮肤导入不算新增功能。

## 历史版本

165、166、172预发行已撤出公开下载。173和167保留为历史发行；167曾由用户确认基本游玩正常，储存总占用由约6.71GB降到3.42GB，这不构成174的真机验收。[旧说明与历史验证归档](docs/HISTORY-THROUGH-173.md)。

不公开签名密钥、私人存档、设备日志或登录凭据。不实现购买、账号、服务器、签名完整性或反作弊校验绕过。游戏资源不因本项目成为开源素材；维护者已声明拥有修改APK公开分发授权，这不代表官方背书。请阅读 [免责声明](DISCLAIMER.md)。
