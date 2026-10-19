# spatial-v2 实验格式草案（不用于现有 Android）

扩展名 `.spatial-v2.zip`。顶层 `format="floatdeck-spatial-layered-experimental"`、`version=2`、`profile="spatial-v2-draft"`、`experimental=true`、`not_for_android_v1=true`。这不是已发布 ZIP V2 标准，不使用 V1 的 format/version 冒充兼容。

现有 Android 的 V1 ZIP 导入与 Shader 完全不变。继续分发原有 `.spatial.zip`；不要将实验包改名后导入正式 APP。未来 APP 分层渲染为后续阶段。

## 坐标、色彩与图层

所有核心 PNG 保持导入原图的 `width × height`、左上原点、upright 原生坐标。不对人物/珍珠单独裁切、缩放、旋转或重生成。JPEG 解码为无损 PNG 工作参考；有 ICC 时做一次保持外观的 sRGB 转换，无 ICC 时保留原始解码 RGB。EXIF 非 upright 的输入明确拒绝，不能只旋转其中一张。深度保持 8-bit 白近黑远。

背景 RGB 无透明；前景必须是 **RGBA PNG，straight / unassociated Alpha**，不是黑底 RGB，也不是预乘 Alpha。Alpha 为 8-bit 0–255，`*_alpha.png` 必须与 RGBA 的 A 通道逐像素一致。

顺序：background `z_order=0` → person `z_order=1` → pearl `z_order=2`。合成采用 sRGB 值域的 source-over：`out = F * a + B * (1-a)`。这是可复核的实验约定，不把单张照片的白纱宣称为物理透射真值；后续渲染器若采用线性光合成，必须明确升级色彩约定，不得混用预乘/非预乘。

## 必需文件

| 文件 | 通道 | 含义 |
|---|---|---|
| manifest.json | JSON | 格式、版本、尺寸、顺序、Alpha、所有文件哈希和大小 |
| source.png | RGB | 原图的规范解码参考，所有人物/珍珠取色的唯一来源 |
| source_depth.png | L | 原始白近黑远深度，无 APP repair |
| background.png | RGB | 原生分辨率补全背景；hole 外必须逐像素等于 source |
| background_hole.png | L 0/255 | 允许生成背景的已审核区域，必须覆盖所有前景 Alpha 支持 |
| person_rgba.png / pearl_rgba.png | RGBA | 人物和可见珍珠的独立透明图层 |
| person_alpha.png / pearl_alpha.png | L | 精细抠图和人工编辑的 Alpha |
| person_protect.png / pearl_protect.png | L 0/255 | 保护原始不透明细节；该处 A=255，RGB 必须等于 source |
| person_unobserved.png | L 0/255 | 原图中被珍珠遮住的人物支持区域，纹理未知；不能把珍珠复制到人物层 |
| person_depth.png / pearl_depth.png | L | 原始观测深度引用；Alpha=0 区域无效，不是假造的完整层深度 |
| input.json | JSON | 输入 ZIP SHA-256、规范原图/深度哈希、尺寸和原始 V1 manifest |
| background_record.json | JSON | 实际补全模型/确切 revision、seed、config、hole/background 哈希、执行状态 |
| quality.json | JSON | 零位移误差、颜色逆合成裁切、保护核心检查、源图裁切/未知遮挡标记 |
| review.json | JSON | 遮罩哈希、分项人工检查及输出哈希，修改后自动失效 |
| previews/*.png | RGB/L | 合成、分别水平/斜向移动、黑白/棋盘底、局部原尺寸放大 |

成功模型输出还包含 `models.lock.json`、`preflight.json`、`*-execution.json`。未运行 GPU 模型的 schema 单元测试 fixture 不含伪造 GPU 执行记录，仅标记 `UNREVIEWED-DRAFT`。

背景遮挡深度为 `unknown-behind-occluders`；本轮用明确前后层级满足空间层顺序，不声称 SDXL 图像同时提供可信深度。已有原图深度不能用来填补新背景几何。

## Manifest 核心结构

生成器写入完整文件清单，简化示例如下（SHA/尺寸由程序实际计算）：

```json
{
  "format": "floatdeck-spatial-layered-experimental",
  "version": 2,
  "profile": "spatial-v2-draft",
  "experimental": true,
  "not_for_android_v1": true,
  "quality_status": "UNREVIEWED-DRAFT",
  "width": 1648,
  "height": 2944,
  "orientation": "upright",
  "color_space": "sRGB",
  "alpha": "straight-unassociated",
  "background": {"image":"background.png","hole_mask":"background_hole.png","z_order":0,"depth":"unknown-behind-occluders"},
  "layers": [
    {"id":"person","image":"person_rgba.png","alpha_mask":"person_alpha.png","depth":"person_depth.png","z_order":1},
    {"id":"pearl","image":"pearl_rgba.png","alpha_mask":"pearl_alpha.png","depth":"pearl_depth.png","z_order":2}
  ],
  "assets": {}
}
```

实际生成器补充 `depth_validity`、`source`、`known_limits`、完整非空 `assets`。验证器要求精确引用、逐文件 SHA-256/字节数、图片通道/尺寸、Alpha 匹配、已知像素不变、遮罩和审核哈希，以及重新计算的零位移误差。

## 审核与已知限制

`UNREVIEWED-DRAFT` 默认不能通过正式验证，只有 `--allow-draft` 可做结构检查，不能解读为画质通过。`human-reviewed-experimental` 需要完整分项人工记录、实际模型执行记录和未过期文件哈希，并检查零位移坏像素比例不超过 2%（RGB 任一通道误差 >8/255）。该全图阈值不是脸部验收替代品；保护核心必须无损，局部 ROI 与 Alpha 黑白底必须人工检查。

源图的珍珠被画面底边裁切，不能无损恢复画外完整球体。珍珠遮住的人物纹理也未知；单独移动珍珠可能露出未观测衣料。它必须在 manifest/预览中显式标记并经额外确认，不能以生成新裙摆或复制珍珠“补人物”。本轮不生成人物或珍珠内容。

白纱 Alpha/颜色分离从单图无法唯一求解；部分透明像素的前景颜色根据背景估计逆合成，可能超出色域后被裁切。数量和重合误差必须记录，不得以零位移近似重合宣称透明度真实。
