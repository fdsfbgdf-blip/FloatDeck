# FloatDeck Spatial Wallpaper V1.0 原型

基于 v0.5.0 / `54e0c6d8cccda31c0769c54ebaa7dc27023df3fe`。开发目标为 Fork 的 `spatial-v1` 分支；不创建上游 PR。保留 MIT LICENSE 与原应用第三方许可入口，没有增加运行时第三方依赖或付费素材。

## 源码审查与改动

原 `FloatDeckRenderer` 使用 OpenGL ES 3.0，负责背景、肖像卡片和锁屏布局动画；`GLWallpaperThread` 管理 EGL、帧节奏和 Surface 生命周期；`SensorHandler` 使用旋转向量，回退到游戏旋转向量与加速度计，已有屏幕方向映射；`Templates` 负责原模板系统。本次保留这些功能，空间模式从原渲染器提前分流，不调用卡片和自动动画路径。

- `spatial/SpatialRenderer.kt`：壁纸与实时预览共用的 GPU 渲染器。
- `gl/Shaders.kt`：新增 ES 3.0 全屏三角形和高度场射线求交着色器。
- `spatial/SpatialAssets.kt`：SAF 文件导入、尺寸限制、配准比例检查、原创几何测试场景。
- `spatial/SpatialMath.kt`：运动限制、等比例填充与未来 DepthProvider 接口。
- `spatial/SpatialActivity.kt`：A 原版 / B 空间 / C 景深 / D 应用内组合预览入口。
- `spatial/DepthClockActivity.kt`、`ClockMath.kt`：独立时钟分层、遮罩阈值生成与素材导出。
- `service/FloatDeckWallpaperService.kt`：复用 EGL、不可见暂停和传感器生命周期；空间模式约 30 FPS，不加载原版模板纹理。
- `sensor/SensorHandler.kt`：跨线程姿态值可见性。
- `.github/workflows/spatial-debug.yml`：Fork 分支自动构建 APK。
- `scripts/generate-spatial-fixtures.py`、`spatial-render-check.py`：可复现原创素材和真实 ES3 像素验证。

## 已实现

A 保留原模板、肖像卡片、特效及原版设置。新安装的启动入口为模式选择与空间设置；选择 A 可进入全部原版设置。

B 导入 JPG/PNG/WebP 和配准的灰度深度图（白近黑远）；按原比例 cover 裁切。GPU 从近到远作 24 步高度场求交，首次交点进行四步细化，解决可见深度面的优先关系；像素逆向映射避免正向网格的空三角裂缝。前、中、远景的相对运动来自深度采样，无粒子、循环动画、模糊或矩形漂浮卡片。可调强度、灵敏度、最大视角、额外裁切。运动幅度受到限制并留有裁切余量。缩放/采样不重新生成五官或材质。

C 独立应用内景深时钟预览，支持黑白主体遮罩、深度阈值生成粗遮罩、时钟水平/垂直位置、字号、三种颜色及主体遮挡范围。按原图 → 自绘时钟 → 原图主体合成。灰度转换含透明度，支持柔和轮廓；实际质量依赖导入遮罩。可导出预览 PNG、灰度遮罩、透明主体，以及未经重新编码的完整导入原文件。

D 应用内组合预览：主页面先导入主体遮罩（或 C 中生成），点击 D 开关。位移后的主体遮罩和原图使用同一射线交点 UV，遮挡自绘时钟。当前组合预览使用固定白色时钟和默认位置；C 的可调时钟布局尚未同步到 D。

完整导入原文件保存在私有目录（单文件上限 128 MiB）；工作纹理最长边 2048，Android 26/27 使用采样解码，28+ 使用 ImageDecoder（自动处理方向）。不读取设备的其他图片，不自动上传任何导入文件。生成场景只有程序绘制的天空、太阳、山坡、树。两张 2048×2048 RGBA 纹理理论上限约 32 MiB，不生成 mipmap。组合预览另有遮罩和较小的时钟纹理。解码、预览、遮罩处理仍有 CPU 内存峰值，尚无真机 GPU/功耗分析数据。

## Android 锁屏能力边界

已检查 SDK 36 的公开 `WallpaperManager`、`WallpaperService.Engine` API。支持壁纸 Surface 渲染、系统选择器及锁屏/桌面目标标志；没有公开方法控制 SystemUI 原生时钟 Z 顺序或提交主体遮罩。动态壁纸能否只用于锁屏、或同时用于桌面和锁屏，交由设备系统选择器决定。本原型未在真实锁屏上验证。

没有 Root、隐藏 API、系统签名权限、悬浮窗或安全锁屏替换。**WallpaperService 永远不启用自绘时钟**；C/D 仅限应用内预览，不会自动隐藏或覆盖原生时间。系统级空间视差 + 原生时钟景深组合入口禁用，未声称已实现。

公开资料调查截至 2026-10-17：

- [Android WallpaperService](https://developer.android.com/reference/android/service/wallpaper/WallpaperService)、[Engine](https://developer.android.com/reference/android/service/wallpaper/WallpaperService.Engine)、[WallpaperManager](https://developer.android.com/reference/android/app/WallpaperManager)：通用公开接口边界。
- [Xiaomi HyperOS 个性化锁屏说明](https://c.mi.com/global/post/674731)：官方社区说明系统编辑器的景深选项；不是提供第三方通用调用 SDK 的证据。
- [Samsung One UI 8 FlexWindow 设计说明](https://design.samsung.com/global/contents/flexwindow-wallpaper/)：说明特定系统界面的时钟适配，不是第三方原生遮挡 API。公开开发文档调查未找到本应用可直接使用的锁屏主体遮罩接口；这不等于断言所有三星设备都没有厂商景深功能。

厂商功能受设备、地区、系统版本影响，尚未建立真机兼容白名单。请导出原图，在设备“设置 → 壁纸/锁屏编辑器”中手动选图；若系统有景深/主体遮挡选项，由用户手动启用。没有选项则无法由本应用开启。遮罩和透明 PNG 是通用素材，并非已验证的 HyperOS / One UI 原生主题包格式。

## 构建与验证

复用 `/workspace/.cloud-setup/env.sh` 的 JDK、SDK 36、Gradle 和 glslang；没有重新安装 SDK。

```bash
. /workspace/.cloud-setup/env.sh
./gradlew testDebugUnitTest validateShaders assembleDebug
./gradlew lintDebug
python3 scripts/generate-spatial-fixtures.py work/test-assets
MESA_SHADER_CACHE_DIR=work/mesa python3 scripts/spatial-render-check.py
```

单元测试：原有 101 + 新增 7 = 108。新增覆盖远近相对位移、零运动、运动上限、等比例裁切、遮罩透明度和深度阈值。

验证结果：`testDebugUnitTest` 108/108 通过、`validateShaders` 6/6 通过、`assembleDebug` 成功；APK v2 签名验证通过。云端本地 JDK 21 环境中 `lintDebug` **未通过**：AGP 9.1.1/Kotlin FIR 在 OverdrawDetector 内部崩溃；仅排除该检测器的诊断重试又在原有测试文件/渲染器的 Compose LocalContextConfigurationReadDetector、ExperimentalDetector 中崩溃。没有保留屏蔽检测器的配置，也不把工具崩溃标记为检查通过。Actions 中 Lint 单独运行并明确允许失败，单元测试/GLSL/构建仍是必须通过的步骤。远端 GitHub Actions 的 JDK 17 环境中同一源码的 `lintDebug` 已实际通过（不是依赖 continue-on-error 才成功）；后续需修复本地 JDK 21 环境的 Lint 兼容性。

GLSL：6 个着色器（原有 4 + 新增 2）。另以云端 Mesa EGL ES3 实际编译链接和运行新增着色器，256×256 坐标编码图测得四方向远/中/近位移约 ±1/±5/±10 像素；组合时钟有 12 个遮挡采样检查。软件 ES3 验证不能替代手机 GPU 驱动、真实姿态、帧率、发热或锁屏兼容性测试。

## APK 与手机测试

Debug 包名 `app.floatdeck.dev`，版本 `1.0.0-spatial-preview`，minSdk 26，需要 OpenGL ES 3.0。可与正式版并存；如果设备上已有不同签名的 dev 包，需先备份其数据再卸载，才能安装当前 APK。

APK 路径：`app/build/outputs/apk/debug/app-debug.apk`。在 Fork 的 Actions 选择 **Spatial Debug APK**，打开 `spatial-v1` 最近成功的运行，下载 **FloatDeck-Spatial-V1-debug** artifact，解压 APK。首次使用 Fork Actions 时可能需要在仓库 Actions 页面启用；该流程也支持手动运行。构建报告一并上传。

1. 安装 APK，允许当前下载工具“安装未知应用”。或 USB 调试后 `adb install -r app-debug.apk`。
2. 打开应用，点击“生成原创测试原图与深度图”；预览中左右、上下小幅倾斜手机。树应比山坡移动更多，远处太阳最少。
3. 将强度拉至 0，确认画面静止；恢复 0.5、默认最大视角 8 度，调整灵敏度和裁切。横竖屏各测一次。
4. 点击 B，在系统壁纸选择器应用；系统允许时选择桌面/锁屏。回桌面、锁屏、熄屏、解锁，观察恢复、裁切、闪屏、耗电和异常日志。
5. 测试自有非付费图片：先导入原图，再导入同构图深度图。替换原图会使旧深度图/遮罩失效，需要重新导入。仅比例检查不能确认内容配准。
6. 点击 C，生成测试遮罩或从主页面导入精细主体遮罩，将时钟移到树冠附近，调整位置/字号/颜色/遮挡范围，检查轮廓。
7. 返回主页面点击 D，倾斜手机检查移动主体与时钟的遮挡关系。应用内时钟应保持固定，前景遮罩随原图移动。
8. 通过 A 回到原版设置，应用普通动态壁纸并确认模板、拖动、特效仍可用。A 会将壁纸渲染模式切回原版。

## 已知限制与后续修复

- 无 Android 真机/模拟器连接：未执行 Android UI instrumentation、安装运行和锁屏验证；ADB 设备列表为空。
- 单张图无法恢复被主体完全遮挡的背景。有限视角、裁切和首次交点能减少裂缝，但深度突变处仍可能有拉伸、重复边缘或重影；没有实现背景补全，不能声称完美恢复。
- 默认视角有意保守。深度图错误、细头发、眼镜、手指和透明物体需要高质量深度与精细遮罩。阈值遮罩不具有人像语义分割能力。
- GPU 工作纹理暂限 2048；超高清工作纹理分级、16 位深度、原始色域/HDR 保真、Android 26/27 EXIF 自动旋转仍待完善。完整原始文件保留，但工作纹理可能色域转换。
- 导入/生成产生的旧版本素材当前保留在私有目录，重复导入会增加磁盘占用；可清除应用数据，后续应增加素材库和安全清理。
- C 时钟布局暂不持久化，D 使用固定布局；尚无自定义字体或用户颜色输入。
- 约 30 FPS 的基础限帧与不可见暂停已实现；待 Adreno/Mali 真机验证后优化采样步数、分辨率、传感器死区和静止画面降频。
- 预留 DepthProvider 接口，但未集成 AI 深度生成。

下一步先在至少一台 Adreno 和一台 Mali 手机上验证安装、方向、Surface 重建及熄屏/解锁，采集 GPU 时间、PSS 和电量，再修复轮廓伪影、统一 C/D 参数和素材清理。厂商原生锁屏适配独立进行，不阻塞空间壁纸原型。

## 已核实的 GitHub 交付结果

- 功能提交：`74c8ab53ee9d792321f957ae06c129e4f5c2ab05`。
- SDK 安装 CI 修复提交：`3d2796205d5867c76ded4507095fed03ebcdef24`。首轮远端因 setup-android 默认请求已经移除的 `tools` 包失败，明确指定 SDK 36 包后修复。
- [成功运行记录](https://github.com/fdsfbgdf-blip/FloatDeck/actions/runs/38998941415)：测试、6 个着色器验证、APK 构建、Android Lint 和上传步骤全部 success。
- [下载 APK 和验证报告 ZIP](https://github.com/fdsfbgdf-blip/FloatDeck/actions/runs/38998941415/artifacts/12088102519)，需登录 GitHub。解压后 APK 位于 `outputs/apk/debug/app-debug.apk`，测试报告在 `reports/tests/testDebugUnitTest/`。该产物当前保留至 2027-01-15，过期后重新运行工作流获取。
- artifact ZIP SHA-256：`e8a3a2e93ee2fe050f7e5c756d1a07a13fda78900121d12e22f6aa59ea6e20fb`。
- 本地 APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `e507843674355d704b66da73141ad912647749c3db1674fc89c6d4e1860e708e`。本地与 Actions 使用各自的 Debug 签名，切换来源时可能需要卸载原 dev 包；不要将此 APK 哈希用于校验 Actions 的 ZIP。
- 未向上游提交 PR，MIT LICENSE 保持不变，无付费高清素材上传。
