# FloatDeck 第四阶段：分层 ZIP 生成端执行报告

## 交付状态与边界

基础分支 `spatial-render-fix-v3`（`7dee272`），新分支 `spatial-layered-v4-generator`。本轮只实现离线生成工具、Colab Notebook、实验格式和验证；**未修改 Android 源码、V1 导入器或正式 Shader**。

代码与可执行 CPU 检查已实现。当前云端未安装 torch/CUDA，未提供 T4，也未运行 SAM、VitMatte 或 SDXL 权重推理。因此 **未生成已验证的真实仙女分层包，未确认人物/白纱/珍珠或补全背景的真实质量**。这些必须由用户在 Colab T4 执行和人工检查。不能把下述 CPU 测试或玩具 ZIP 称为真实素材通过。

用户真机提供的 V1/V2/V3 同视角失败率 2.574% / 7.609% / 0.801% 是本轮背景证据，不是本轮测得的分层质量指标。V3 棋盘缺口需要被遮挡背景数据，不能以原图复制、模糊、缩小运动或隐藏错误替代。

## 实际输入检查（已执行）

读取本轮上传 `wallpaper_001.spatial.zip`，仅包含 `manifest.json`、`wallpaper.jpg`、`depth.png`。V1 format 为 `floatdeck-spatial`、version 1、white-near。

- 原图 RGB、深度 L：均 **1648×2944，4,851,712 像素**，EXIF upright，无 ICC。
- 输入 ZIP SHA-256：`369b340f1e8cacc6de92f17bcfb5571233719db8e29769e15e936046f3dba19d`。
- 深度原始灰度范围 0–255。
- JPEG 解码工作 PNG 与原始 JPEG 解码 RGB **逐像素一致**；深度亦逐像素一致，未旋转、裁切、resize 或执行 APP repair。
- 未改写上传 ZIP；只在 `work/` 保存本地工作副本。原始图片/ZIP 没有提交到公开仓库，仓库仅记录输入哈希与检查结果。
- 机器记录：`reports/spatial-v4-actual-input-cpu.json`。该文件明确 `gpu_models_executed=false`、实际背景/Alpha 生成 false、画质验收 false。

已目视查看实际原图缩略图以配置框与 ROI：飞散发丝、手指/光球、透明白纱、裙摆以及大珍珠必须分开检查。**珍珠本身已被原始画面底边裁切**，在保持画布且不生成新珍珠的约束下，不可能恢复画外完整球体。珍珠也可能挡住裙摆；隐藏的人物纹理不在原图中，本工具标记 `person_unobserved.png`，不拿珍珠填入人物层，不重新生成裙摆。

## 实现文件

- `notebooks/FloatDeck_V4_Layered_Generator.ipynb`：完整按阶段执行的 Colab 入口，包含环境、上传、分割、编辑、matting、背景补全、预览、审核与下载。
- `tools/layered_v4/models.py`：模型预检查、revision lock、SAM、原生 tile VitMatte、SDXL 全局背景与原生 tile 精修。
- `core.py`：安全 V1 读取、真 Alpha/straight 色彩、保护核心、逆合成去污染、原图像素保护、预览、打包。
- `editor.py` / `editor.html`：Colab 原生坐标画笔编辑；不会自动批准结果。可改二值 mask、三值 trimap、细 Alpha、保护区和 hole，也支持外部 PNG 上传。
- `cli.py`：各阶段独立命令及审核关卡；`validate.py`：离线包校验与重新计算零位移误差。
- `test_generator.py`、`scripts/check-layered-notebook.py`、`.github/workflows/layered-generator.yml`：CPU 回归、严格 Notebook 检查与 Actions。
- `tools/layered_v4/FORMAT_V2_DRAFT.md`：格式草案；`README.zh-CN.md`：简短中文教程。
- `samples/spatial-v2/SYNTHETIC_SCHEMA_ONLY.spatial-v2.zip`：**仅 96×160 玩具格式 fixture**，UNREVIEWED-DRAFT；非真实图片、非 GPU 结果，普通验证必须拒绝。

## 模型流程与显存策略（实现，尚未在 T4 执行）

1. Python 3.12 专用子环境。可用既有 DA3 3.12 解释器作 base，但不向 DA3 环境安装/降级依赖。Notebook 中安装、preflight、所有模型执行均显式使用同一个 PY；内核只处理上传、显示和编辑。
2. 固定 PyTorch 2.6.0 / torchvision 0.21.0 CUDA 12.4 wheel；依赖清单显式固定 `addict==2.4.0`，完整 preflight 真实导入 addict。避免装在内核、运行在另一解释器。
3. `shutil.which` 先判断 nvidia-smi，捕获不存在/失败/超时；它不是 CUDA 成功证据。选定解释器中必须 torch.cuda 可用，并满足至少 14 GiB 总 / 10 GiB 空闲显存和 20 GiB 磁盘余量。Notebook 安装前另检查缓存可写与 25 GiB 空间。
4. SAM `facebook/sam-vit-base` 建议每类三候选；输出分数仅用于建议，不能当作边界或手指完整性通过。
5. VitMatte `hustvl/vitmatte-small-composition-1k`：float32、768 tile / 128 overlap；三值 trimap、原生坐标、只合并 Alpha，不改变照片几何。珍珠反光内部和人物确定前景保持不透明；本图白纱区域为“未知”提示而非强制不透明，必须编辑检查。用户已有 trimap 优先；修改后还应检查旧 protect 是否错误强制 Alpha=1。
6. SDXL `diffusers/stable-diffusion-xl-1.0-inpainting-0.1`：fp16、model CPU offload、attention slicing、VAE tiling；全局长边最多 1024，再原生 1024 tiles / 192 overlap 精修。只写已审核 hole；其外逐像素保留源图。tile 重叠仅融合模型生成的背景提案，不复制/模糊前景来填空。
7. 分割、matting、inpainting 分属独立 CLI 子进程，阶段结束退出，避免模型共占显存。记录耗时、峰值 allocated/reserved、是否实际执行/成功；失败不伪造成功、不降级为复制背景。
8. Hugging Face 模型先解析为实际 commit SHA，随后按锁定 revision 加载。缓存、Python/CUDA/依赖记录在 preflight；未运行模型时没有伪造模型 revision 或 GPU 时间。

已静态核对固定 transformers 4.48.3 的 SamModel/VitMatte forward/preprocess 接口。SAM 的 original_sizes/reshaped_input_sizes 仅供后处理，明确移除后才传给 forward。该接口核对不是权重推理成功证明。

## 画质防线与限制

人物和珍珠不使用生成模型重画。确定不透明像素 RGB 精确来自 source，尤其保护区必须 A=255 且 RGB 一致。珍珠 Alpha 支持从人物层中移除，避免分层移动后复制珍珠。遮住的人物部分明确未知，额外确认前不能标记为已审核可用。

白纱需要对原合成背景混合进行处理：部分 Alpha 像素以 `F=(I-(1-a)B)/a` 在 sRGB 实验约定下去污染；零 Alpha 清空 RGB，保护核心和所有 A=1 RGB 保持原图。逆问题不唯一，可能超色域裁切/边缘残影，报告裁切数、零位移误差和黑白底预览，不能以代数重合宣称 Alpha 为真值。

审核不是装饰：遮罩及输入绑定 SHA，修改后失效；背景记录绑定 hole/background；最终输出审核绑定三个图层文件。只有实际模型成功、全部分项人工检查、已知遮挡明确接受和重合阈值满足，才可标记 human-reviewed-experimental。验证器不会自动判定眼睛、手指数、发丝或背景语义正确。

输出包括零位移重合/差异 ×4、人物单独水平、珍珠单独水平、分别斜向、未知人物诊断、Alpha 黑/白/棋盘底、脸/头发/手指/白纱/裙摆/珍珠五列放大图。**所有这些真实场景预览必须在 Colab 完成实际模型后查看，本云端没有这些模型结果。**

## 必须由用户在 Colab 执行/验收

- T4 环境真实 wheel 安装、模型下载、CUDA 成功和峰值显存/运行时间；本云端未执行。
- 实际图三候选 SAM 是否选对、手指/头发/球体边界、白纱 trimap/保护区是否正确。
- VitMatte 实际 Alpha 是否漏发丝/手指、是否将反光或白布错误透明化；黑白底是否有边。
- SDXL 背景是否有重复人物/珍珠/残影、纹理是否自然、tile 接缝是否明显；被遮挡真实背景本无真值，需人工判断。
- 零位移重合和局部身份细节、单独/斜向位移露底、珍珠原始裁切与未知裙摆是否可接受。
- 原生尺寸/通道/manifest 验证，以及实际分层 ZIP 质量是否达到后续 APP 开发门槛。

未经过这些步骤，不能声称真实分层可用，也不能开始以该质量结论推动正式 APP 渲染替换。

## 执行结果与提交

CPU 与 Actions 最终结果在验证完成后补充；Notebook 代码单元初始 execution_count 均 null、outputs 均空，未插入伪造执行截图或日志。
