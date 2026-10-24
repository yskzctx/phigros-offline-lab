# 174 修复更新：ZIP 分卷

请完整下载以下三个文件，放在同一个文件夹，保持文件名不变：

- Phigros-offline-v174.z01
- Phigros-offline-v174.z02
- Phigros-offline-v174.zip

**从最后一个 `.zip` 文件选择解压，不要单独解压 `.z01`，不要改名为 `.zip.001`。**
这是标准 ZIP 分卷，无密码；需要解压器支持 ZIP 分卷。解出的 APK 大约 3.34 GB，下载文件本身约 2.97 GB，安装时还需要额外空间。

本机已分别使用 ZIP4J、7-Zip、SevenZipJBinding 完整读取并解压校验；解出 APK 的 SHA-256 与原始 v174 APK 一致。手机具体下载器、解压器和安装尚未验证。此更新包尚未真机验收，不能把电脑解压检查当作设备测试成功。

## 下载错误的检查

若提示 DATAERROR、CRC 错误、缺卷或无法解压，先逐个比较 SHA256SUMS.txt 的 SHA-256。文件大小相同仍可能下载损坏；哈希不一致时仅重新下载对应分卷。换压缩格式无法修复已经下载坏的字节。

电脑 PowerShell 可在本文件夹执行：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath .\Phigros-offline-v174.z01, .\Phigros-offline-v174.z02, .\Phigros-offline-v174.zip
```

## 安装

解压得到 Phigros-offline-v174.apk。它属于 `com.PigeonGames.Phigros.offline`，用于升级既有修改版；保留当前数据，勿卸载正版、勿清除正版或修改版数据。升级前自行导出需要保留的自制谱及私人存档。旧的 v173 正式版继续保留，此包不会自动安装，也不会覆盖原版应用数据。
