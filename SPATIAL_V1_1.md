# FloatDeck Spatial Wallpaper V1.1 交付报告

基于 `spatial-v1` 上的 V1.0，复用既有 `SpatialRenderer`、深度 GPU 着色器和 `SensorHandler`。未重写空间引擎，未增加 AI 模型或收费系统，锁屏时钟只保留已有功能。

## 消费者流程

1. 安装 Debug APK，打开应用。
2. 点击 **导入空间壁纸 ZIP**，通过 Android 系统文件选择器选取已下载的 `.zip`。
3. APP 一次完成原图、深度图、可选遮罩和预设解析、验证、纹理准备及私有库保存，自动开始预览。不需要手动选择深度或理解参数。
4. 倾斜手机查看前、中、远景；可选微调“空间效果强度”。
5. 点击 **设为动态壁纸**，在系统选择器中确认，应用范围以设备提供的桌面/锁屏选项为准。
6. 点击 **已保存的空间壁纸 · 切换** 随时打开其他作品。选择预览与当前系统壁纸使用独立设置，单纯切换预览不会替换已应用壁纸。

“更多”中保留普通动态壁纸、V1.0 技术工具和已有时钟预览。技术工具保留原先对当前空间设置的直接修改行为，消费者正常使用无需进入。

## 实现

- 独立 `floatdeck-spatial` ZIP v1 标准：[准确格式与限制](SPATIAL_PACKAGE_FORMAT.md)。与卡片模板的入口、解析、数据存储隔离。
- `SpatialPackage.kt`：manifest 校验，ZIP 中央目录预检、路径/类型白名单、数量/字节/压缩比/CRC 校验、内容去重。
- `SpatialLibrary.kt`：事务目录导入、原文件和工作素材保存、壁纸列表、选择与每套强度的持久化、非活动作品删除。
- `SpatialPackageStore.kt`：Android 真实图片类型/尺寸检查、像素配准检查、有界采样、深度方向转换、完整参数快照发布。
- `SpatialActivity.kt`：消费者播放器入口、导入后自动预览、切换、可选强度微调、系统动态壁纸应用。
- `SpatialToolsActivity.kt`：保留原 V1.0 高级技术界面。
- `SpatialRenderer.kt`：复用原渲染，仅允许预览使用独立设置源，并逐帧读取同一份路径/参数快照，避免切换期间混用两套素材。GLSL 空间渲染算法没有改变。
- 原图与原始深度保留在私有库；生成最长边 2048 工作纹理。深度及遮罩必须和原图实际像素尺寸一致，`black-near` 自动规范化。预览区域按手机屏幕比例显示；壁纸保持原比例 cover 和裁切，不拉伸。
- 新包未启用时钟或任何装饰，不出现漂浮卡片。系统壁纸服务不可见时继续使用已有暂停机制。

## 示例与 APK

可直接导入 [sunrise.spatial.zip](samples/spatial/sunrise.spatial.zip) 和 [dusk.spatial.zip](samples/spatial/dusk.spatial.zip)。约 27 KiB/套，包含真实 1080×1920 PNG 原图、深度图、主体遮罩及预设。所有画面为程序生成，按 MIT 许可分发，无付费素材。

Debug 包名仍为 `app.floatdeck.dev`，版本 `1.1.0-spatial-player`，versionCode 10，Android 8.0/API 26 起，需要 OpenGL ES 3.0。保留与原正式版并存的能力。不同来源的 Debug 签名可能不同；如安装提示签名冲突，先备份，再卸载旧 dev 包。卸载会清除私有壁纸库，ZIP 原文件请保留备份。

Fork 的 **Spatial Debug APK** 工作流上传 **FloatDeck-Spatial-V1.1-debug**，内含 APK、测试报告、两套样例 ZIP 和文档。解压后安装 `app/build/outputs/apk/debug/app-debug.apk`；样例位于 `samples/spatial/`。Android 模拟器测试报告、实际预览及应用截图在 **FloatDeck-Spatial-V1.1-Android-tests**。所有构建只提交个人 Fork，不创建上游 PR。

## 验证方法

复用现有云端 `/workspace/.cloud-setup/env.sh` 中的 JDK、SDK 36、Gradle 和 glslang，没有在该环境重新安装 SDK。运行：

```bash
. /workspace/.cloud-setup/env.sh
./gradlew testDebugUnitTest validateShaders assembleDebug assembleDebugAndroidTest
MESA_SHADER_CACHE_DIR=work/mesa python3 scripts/spatial-render-check.py
```

- JVM 测试覆盖：现有 108 测试 + 34 个 ZIP/格式/持久化用例，含真实示例包、路径穿越、重复文件、异常条目数、压缩炸弹、截断/CRC、错误版本/字段/参数、事务回滚、语义去重、强度与选择持久化。
- 6 个 GLSL 着色器检查；既有 Mesa ES3 四方向深度位移与时钟组合遮挡检查。
- Android 集成测试使用真实 `BitmapFactory`、原始 ZIP、私有文件及 SharedPreferences，覆盖图片类型/尺寸/灰度检查、黑近白远规范化、失败回滚、页面重建、实际 GLSurfaceView PixelCopy 预览像素、应用按钮和系统 Intent。
- Actions 的临时 API 35 模拟器运行上述 Android 测试，并通过公开系统 UI 操作验证强制停止后的恢复、37% 强度持久化和系统壁纸选择器应用。本次 API 35 模拟器已实际执行通过；云端本身没有 Android 真机，不能将模拟器结果等同于厂商手机实测。

## 真机测试清单

1. 下载、安装 APK 和两套示例 ZIP。使用同一 Actions 产物 APK 进行后续升级测试，避免签名不一致。
2. 导入 sunrise，确认直接显示森林，默认强度 45%，无需再选任何素材；上下左右倾斜，树比山坡运动更多，远太阳最少。
3. 再次导入同一 ZIP，确认提示已保存，库中不重复；将强度调至 37%，等待保存完成。
4. 导入 dusk，确认不同画面和 65% 默认强度，黑近白远被自动转换。切回 sunrise，确认恢复 37%。
5. 切换预览时检查已应用的桌面壁纸不被替换；点击设为动态壁纸，在系统 UI 确认桌面/锁屏范围，回桌面观察实际传感器视差。
6. 强制停止 APP 后重开，确认最后预览作品、库中两套壁纸和每套强度均保留。熄屏、亮屏、解锁后检查恢复。
7. 导入原版卡片模板、缺图/错尺寸/假扩展名/损坏 ZIP，确认出现错误提示且原有库和当前壁纸保持可用。
8. 横竖屏与不同长宽比手机检查裁切及无拉伸；切到“更多 → 普通动态壁纸”，确认原版模板和特效仍可用。
9. 库中可删除未选中且未应用作品。不要用唯一副本验证卸载；卸载或清除数据会清空私有库。

## 当前边界

- 模拟器结果不等于 Adreno/Mali 真机性能、发热和厂商锁屏兼容性验证；仍需按上述清单手机实测。
- 单张图片无法恢复原图未记录的遮挡背景；沿用 V1.0 有限视角和裁切策略，极端深度边缘仍可能拉伸或重影。
- 工作纹理最长边 2048、8 位/sRGB；保留原始高清文件不代表无损 HDR/广色域 GPU 输出。
- 系统选择器最终确认之前，按下“设为动态壁纸”会准备服务配置；若当前系统正在运行同一 FloatDeck 服务，它可能立即读取新配置。取消系统预览不会自动回滚已发布的配置。仅浏览库或导入预览不会改变当前系统壁纸。
- 时钟预览继续使用现有已发布空间设置；本次不扩展时钟、不覆盖或隐藏系统锁屏时间。无法承诺原生系统时钟景深。
- 不支持云同步、素材加密授权或购买功能；没有添加这些系统。
- 下阶段优先：真实手机验收、系统预览取消回滚、素材缩略图和大库管理，以及帧耗时分析。

保留原 MIT LICENSE 和现有第三方许可入口，没有新增 Android 运行时依赖。

## 本次已核实的交付记录

- 功能提交：`7cc62eb552c49e25ead9007975e804b9e1a52561`。CI/系统 UI 测试修复后的已验证源码：`8fa43ae4ac06b0bd95962d315aceffbb2fd0329c`，均在个人 Fork 的 `spatial-v1` 分支。
- [Actions 完整成功运行](https://github.com/fdsfbgdf-blip/FloatDeck/actions/runs/39002183931)：`spatial-debug` 与 `android-integration` 两个任务均为 success。
- [下载 APK、两套示例 ZIP 和报告](https://github.com/fdsfbgdf-blip/FloatDeck/actions/runs/39002183931/artifacts/12089517866)，需登录 GitHub。下载包解压后 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；示例位于 `samples/spatial/`。不要再解压示例 `.spatial.zip`，在 APP 中直接选择它。
- [下载 Android 测试报告、GL 预览及已应用桌面截图](https://github.com/fdsfbgdf-blip/FloatDeck/actions/runs/39002183931/artifacts/12089367036)。
- 142 个 JVM 单元测试：0 失败、0 错误、0 跳过。本地与远端 Debug 构建、6 个着色器检查均通过；远端 Android Lint 实际通过。
- API 35 模拟器：6 个 Android 集成测试通过，其中实际 PixelCopy 预览颜色检查通过。随后独立烟测通过：强制停止并重启应用后恢复森林作品及 37% 强度，通过公开系统选择器确认，`dumpsys wallpaper` 核实当前组件为 `FloatDeckWallpaperService`。
- 本地既有 Mesa ES3 深度位移及组合遮挡验证仍通过：四方向远/中/近位移约 1/5/10 像素。
- APK/示例下载 artifact ZIP SHA-256：`d4709e929c9c1249e3e8ece7e3a8b960f794216f8d61161caa2af41011f9cd41`，不是单个 APK 的哈希。产物当前保留至 2027-01-15，过期可重新运行工作流。
- 前两次 Android 任务在 6 个集成测试通过后，被 AGP 自动卸载测试应用阻断了后续烟测；已通过 AGP 的公开构建选项保留安装，并把截图采集移到模拟器关闭前。最终运行无此失败。
- 尚无 Adreno/Mali Android 真机验收，不宣称系统原生锁屏时钟景深可用；未向原作者提交 PR，没有上传任何付费壁纸。
