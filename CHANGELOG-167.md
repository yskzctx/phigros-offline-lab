# 167 资源单份存储修复

本版仅修复安装后储存占用翻倍的问题。版本号 167，游戏内容仍为 4.0.1（157）。

## 原因与改动

166 将整套资源放在 APK 内，首次启动又复制到修改版 files/offline-packs，额外占约 3.29 GB。167 的 OfflineAssets.java 改用 Unity 的安装时资源路径：getAssetPackPath 返回空字符串，核心资源从已安装 APK 的 AssetManager 读取，StreamingAssets 沿用引擎的 APK URI 路径。不增加新 hook，也不修改游戏 native。

启动前核对 APK 中全部 2724 项资源的索引与长度；核对失败不清理旧副本。核对通过后，只清理旧索引明确列出的、长度相符的资源副本及旧完成标记。拒绝路径越界与符号链接，不遍历存档、会话备份、历史备份或未知文件。空间在升级后首次启动时回收；单纯安装完成尚未回收。无需清除数据或卸载。

## 范围

游戏源码只改变 OfflineAssets.java；AndroidManifest.xml 仅递增版本号。与 166 比较，APK 仅 AndroidManifest.xml、classes2.dex 的 CRC/大小变化；其他所有条目含游戏资源、native、浅色浮窗、规则界面、配置、谱面目录及匿名初始存档保持原内容和压缩方式。自动游玩、判定、RKS、Data、存档行为不做功能调整。

## 验证

储存回归先在 166 复现“返回提取目录”；167 的 23 项断言通过，覆盖无首次复制、旧副本回收、保存及备份保留、重复索引/缺失资源/长度错误/路径越界/符号链接拒绝与重复启动。实际完整 APK 的资源索引验证通过。签名 v2/v3、16 KB ZIP 对齐、ZIP CRC、原内容比较通过。

已通过 ADB 保留数据覆盖安装 167；安装前已在维护者电脑私有目录验证备份修改版的数据（排除可从 APK 重建的资源副本）。

用户手动检查后回复“正常，可以用”，确认启动、章节、开始游玩/音乐及收藏页基本可用。ADB 只读核对版本 167；旧 files/offline-packs 已不存在。修改版内部数据从 3220418 KiB 降至 8052 KiB；安装程序与资源占 3331806 KiB，总占用约 3.42 GB（十进制），此前约 6.71 GB。实际回收约 3.29 GB 的资源副本。正版仍安装，其内部数据占用与安装前读数相同。本次没有通过工具启动、点击或控制手机界面。设备日志缓冲为空，没有取得启动日志；资源验证依据是主机检查、用户实测以及手机实际目录与占用。该反馈不是所有曲目、设备和既有自动游玩判定功能的穷尽验收。

## 安装与恢复

独立包名 com.PigeonGames.Phigros.offline、原修改版签名，可直接覆盖升级。正版目录不参与清理。旧资源副本不属于用户存档，安装旧版并首次启动可重新提取；旧版版本号较小，普通安装会拒绝降级，调试回退须保留修改版数据并使用合法的降级安装方式，不要卸载或清除数据。

历史/自动会话备份与恢复方式沿用 README。公开发布不包含维护者的手机存档、日志、签名私钥或密码。

参考 Unity 官方文档：[安装时资源包](https://docs.unity3d.com/cn/2022.3/Manual/android-asset-packs-manage.html)、[GetAssetPackPath](https://docs.unity3d.com/ja/current/ScriptReference/Android.AndroidAssetPacks.GetAssetPackPath.html)、[StreamingAssets](https://docs.unity3d.com/ja/2022.3/Manual/StreamingAssets.html)。这些说明提供路径依据，不能替代本版本的手机验证。
