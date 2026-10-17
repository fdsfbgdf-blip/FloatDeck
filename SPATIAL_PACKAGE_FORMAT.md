# FloatDeck Spatial ZIP 标准 1

该标准的版本独立于 APP 版本。`format: "floatdeck-spatial"` 与原版 FloatDeck `template.json` 卡片模板完全不同，播放器不会将空间 ZIP 交给卡片模板系统处理。

## 文件结构

使用单个普通 ZIP 文件，建议命名 `作品名称.spatial.zip`。**必须直接放在 ZIP 根目录，不要再套一层文件夹。** 内容只能是以下 3 或 4 个文件：

```text
manifest.json
wallpaper.png       # 必选，或 wallpaper.jpg / wallpaper.webp，三选一
depth.png           # 必选
mask.png            # 可选；存在时 manifest 必须引用
```

文件名大小写敏感。原图使用 JPG、PNG 或 WebP；深度图、遮罩只能是 PNG。所有素材必须使用相同的实际像素宽高，推荐竖向 1080×1920 或 1440×2560。灰度图即使保存为 RGB PNG 也可使用，但三个颜色通道须相同，不可用透明通道代替灰度值。深度图和遮罩必须不透明。

JPEG 的 EXIF 方向必须为正常/未定义；制作时先把旋转或镜像应用到像素，再制作对应深度图和遮罩。包内图片按编码像素坐标配准，播放器不会分别自动旋转。空间包预制素材应使用静态图片和 sRGB；工作纹理转换为 sRGB ARGB8888。原始文件按原字节保存在应用私有库中，不重新编码原文件。

## manifest.json

UTF-8，无 BOM，最多 16 KiB。以下字段全部必选，只有 `assets.mask` 可省略。版本 1 不接受额外字段。

```json
{
  "format": "floatdeck-spatial",
  "version": 1,
  "name": "晨光森林",
  "assets": {
    "image": "wallpaper.png",
    "depth": "depth.png",
    "mask": "mask.png"
  },
  "depthDirection": "white-near",
  "preset": {
    "strength": 0.45,
    "sensitivity": 1.0,
    "maxAngleDegrees": 8.0,
    "crop": 0.04
  }
}
```

| 字段 | 定义及合法值 |
| --- | --- |
| format | 必须为字符串 `floatdeck-spatial` |
| version | 数值 `1`，其他版本拒绝导入 |
| name | 1–80 个 UTF-16 字符，不能全空白或含控制字符 |
| assets.image | `wallpaper.jpg`、`wallpaper.png`、`wallpaper.webp` 之一 |
| assets.depth | 固定为 `depth.png` |
| assets.mask | 可选，存在时固定为 `mask.png`，不能为 null |
| depthDirection | `white-near`：白近黑远；或 `black-near`：黑近白远 |
| preset.strength | 数值，0–1；0 为静止；推荐 0.3–0.6 |
| preset.sensitivity | 数值，0.2–3；默认建议 1 |
| preset.maxAngleDegrees | 数值，1–15；默认建议 8 度，使用既有引擎的虚拟运动上限 |
| preset.crop | 数值，0–0.3；额外裁切比例，建议 0.04；引擎另有基本安全边距 |

所有参数都必须是有限数值，不能用字符串代替。不使用遮罩时，同时省略 `assets.mask` 和 ZIP 内的 `mask.png`。遮罩白色为主体、黑色为背景、灰色为柔和边缘，仅供已有景深时钟工具使用；空间 3D 本身不需要遮罩。

`black-near` 在导入时转换成引擎既有的白近黑远工作纹理，原始深度文件保持不变。不支持 AI 深度生成、脚本、网络素材引用或卡片配置。

## 安全与资源限制

- ZIP 文件不超过 64 MiB；每个图片文件不超过 32 MiB；实际解压总计不超过 96 MiB。
- 仅接受 3–4 个根目录白名单文件，不接受目录、额外文件、重复文件名、绝对路径、反斜线、`..` 或外部引用。
- 普通 ZIP32，压缩方式仅 STORED / DEFLATED；不支持 ZIP64、分卷、加密、归档注释、可执行前缀和尾随数据。中央目录最大 4096 字节，条目数在创建 ZipFile 之前即检查，避免超多条目耗尽内存。
- 每条目解压/压缩比最大 200:1；同时校验声明长度、实际读取字节数和 CRC32。
- 实际图片类型必须匹配扩展名；最长边不超过 8192，单张最多 40,000,000 像素；图片解码前先读尺寸。原图、深度图和遮罩的像素尺寸必须完全一致。
- 导入后统一生成最长边不超过 2048 的工作纹理，保持比例并采用相同目标尺寸；同时保留包内原始文件。不能将保留原文件理解成当前 GPU 支持完整 8K 输出。
- 当前最多保存 100 套壁纸；导入准备阶段要求至少 192 MiB 可用空间。可在壁纸库中删除未选中、未应用的壁纸。
- 整包在应用私有临时目录验证及准备，成功后通过目录重命名发布；失败清理临时目录，不替换当前预览或壁纸。
- 去重 ID 由规范化语义字段和各素材内容 SHA-256 组成。同内容换压缩方式/JSON 排版重新打包，不会产生重复壁纸，也不会覆盖用户已调强度。改变名称、预设、深度方向或素材内容将视为另一套作品。

上述限制是格式 1 的播放器实现边界；打包工具不应依靠宽松 ZIP 解压器接受的行为。

## 制作与样例

仓库的 [晨光森林](samples/spatial/sunrise.spatial.zip) 和 [暮色森林](samples/spatial/dusk.spatial.zip) 是可直接一次导入的完整包，分别验证两种深度方向和不同默认强度。天空、太阳、山坡、树均由程序生成，采用仓库 MIT 许可，没有付费壁纸或第三方图片。

```bash
python3 scripts/build-spatial-samples.py samples/spatial
```

制作自己的作品时：先准备方向正确的高清原图，再制作像素配准的灰度深度图（及可选遮罩），填入预设和名称，将 3/4 个文件直接压缩到根目录。不要把原版 FloatDeck 卡片模板改后缀作为空间包。
