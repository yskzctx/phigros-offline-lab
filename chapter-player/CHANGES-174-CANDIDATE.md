# v174 合并候选版（2026-10-23）

这是一个 APK，包名仍为 `com.PigeonGames.Phigros.offline`。未安装到手机，未真机验收，未公开发布；v173 正式版不变。两个 LSPosed 模块及独立 Phira 计划保持取消。

## 反馈及已实现修复

- 截图显示 Dead Soul 已成功导入，打开播放器时显示“播放器拒绝此谱包路径”。补充图显示私有目录内存在 JSON、音乐、图片、info.txt。路径属于修改版，不要求 root；不修改正版目录。
- 根因已在新 JVM 中复现：`configureChapter` 首次 JNI 调用直接使用 `JString.to_string()`，jni 0.22.4 的 VM singleton 尚未初始化，得到 `<JNI Not Initialized>`，JSON 解析失败。原实现将全部失败映射为“拒绝路径”。修复为 `EnvUnowned.with_env` + `try_to_string(env)`，并分类抛出 IOException。未开启自动游玩时，不再让无效判定规则阻止手动游玩。
- Bad 从“正常击中后改记分”改为提前触发。Tap 使用原始 ClickControl.Judge 的早 Bad 分支；Drag/Flick/Hold 保留用户要求的非标准 Bad，通过原函数的未击中清理路径替换单次记分回调，避免正常击中音效。长条清理时间覆盖完整 holdTime。实际动画位置和听感仍待设备验证。
- 自制章节的皮肤导入保留 `hit_fx.png`、OGG/WAV/MP3 音效、特效参数、`colorPerfect` / `colorGood`。导出给 PRPR 时优先使用所选皮肤，缺项才使用默认资源；自定义 WAV 不会被默认 OGG 覆盖。使用 v2 导出缓存，避免复用旧默认资源缓存。旧版导入时已被丢弃的资源需要重新导入原图包才能恢复。
- 章节列表新增系统文件选择器导出，以及确认后删除；不需要 root。导出不包含 `local-record.json`，不接触其他谱包或正版存档。

## 尚未完成，不能视为全部功能交付

- **Phigros 原生章节**仍仅替换音符图。原生章节对自定义击中特效、音效和判定线颜色的接入尚未完成；上述扩展资源支持目前适用于**自制章节播放器**。
- 当前候选未在手机验证启动、Dead Soul 实际解码/游玩、Bad 动画音效及皮肤播放。电脑测试和编译成功不等于这些项目通过。
- 手机下载间歇损坏尚未取得问题文件的逐卷 SHA-256，不能指出哪一卷损坏，也不能声称已修复手机端传输。GitHub v173 当前公布的三卷大小和 digest 已再次核对。`tools/verify_release173_downloads.py` 可在电脑校验拷出的三卷，只读、不上传；可识别大小相同但内容损坏，只提示重下错误卷。
- GitHub CLI 当前未登录，GitHub connector 写入尝试也因连接失败未成功；没有推送或发布本候选。

## 修改文件

实际构建目录：`work/candidates/controls-v170/`（历史目录名，不代表版本170）。

- `src/offline.c`、`src/controls-plan.h`：提前 Bad、原始清理、时间线预估。
- `src/com/phigros/offline/SkinPack.java`、`SkinController.java`：扩展资源保留、格式/大小校验、特效 PNG 安全重编码。
- `chapter-src/com/phigros/offline/ChapterSkinExport.java`：自选特效/音效/颜色导出和缓存。
- `chapter-src/com/phigros/offline/ChapterFiles.java`、`ChapterLibraryActivity.java`：应用内导出、删除。
- `chapter-src/com/phigros/offline/ChapterPlayerActivity.java`：JNI IOException 声明。
- `work/publication/repo/chapter-player/local_chapter.rs`：首次 JNI 初始化、解码和分类错误。
- `work/publication/repo/chapter-player/planner.rs`、`local_auto_method.rs`：自制谱提前 Bad。
- `build_controls174.py`：合并打包，保留资源，核验后删除 unsigned/aligned 中间包。

已同步公开源码目录 `work/publication/repo/chapter-player/android-source/`，对应 SHA 见 source-manifest.json；未同步任何签名私钥或存档。

## 电脑验证

- 首次 JNI 测试：旧入口失败，修复后通过；测试真实生产 JNI 函数，覆盖中文路径、非法参数、越界、错误分类、失败重试和关闭自动游玩。
- 八组 C 回归、七组 Java 回归、九项 Rust 规划测试及路径限制测试通过。皮肤管线实际 PNG 解码/裁剪回归通过。
- 新增皮肤资源测试验证特效/音效保留、自定义音效优先级、颜色元数据、默认回退和图集范围。
- 谱包导出再导入、成绩记录排除、限定目录删除和其他数据保留通过。
- ARM64 后端在本地以 pinned Phira c830dee5、nightly-2026-01-01、NDK26.3.11579264 构建。最终链接传入 `-Clink-arg=-Wl,-z,max-page-size=16384`，PT_LOAD 对齐16KB。
- APK v2/v3 签名、ZIP16KB对齐、资源条目保留、无联网权限通过。真实手机验证为 false。

具体哈希及最终验证以同目录 verification.json、SHA256SUMS.txt 为准。
