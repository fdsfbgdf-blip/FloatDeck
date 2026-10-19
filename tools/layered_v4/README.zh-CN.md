# V4 Colab 分层生成端使用说明

1. 打开仓库 `notebooks/FloatDeck_V4_Layered_Generator.ipynb`，Colab 运行时选择 **T4 GPU**。
2. 从上往下执行环境单元。可填已有 DA3 Python 3.12 作为 base；生成器创建独立子环境，不降级 DA3 依赖。所有模型和 pip 命令都使用显示的 `PY`，不是 Notebook 内核。
3. 上传本轮 `wallpaper_001.spatial.zip`。程序检查 V1、方向、尺寸和 ZIP 安全；实际输入应为 **1648×2944**。原始 ZIP 不改写。
4. SAM 输出 person/pearl 三候选。检查默认建议；用原生坐标编辑器修正 `person_mask.png`、`pearl_mask.png`。可外部编辑并上传同名 **L 灰度 PNG**。不要上传缩略图或黑底 RGB。
5. 显式确认分割后运行 VitMatte；分阶段进程退出以释放 GPU。可修改三值 trimap 再抠图，或直接编辑 Alpha；脸、眼睛、手指核心和珍珠反光用保护遮罩保留原 RGB。细发丝外的未知带需要手工扩大，白纱不要全部当不透明。
6. `prepare-masks` 去掉人物层中的珍珠支持，输出未知人物遮挡标记。检查 Alpha、保护区和 `background_hole.png`，再确认 `MASKS_REVIEWED=True`。任何修改都会使已审核哈希失效。
7. SDXL 仅补已审核 hole；全局生成再原生像素 tile 精修，hole 外逐像素恢复原始可见背景。为避免细发丝遮罩在扩散 latent 网格消失，仅模型条件遮罩扩张 16 像素；最终写回仍严格限定在已审核 hole 内，不扩大修改权限。失败就停止，没有复制原图/模糊兜底。看背景预览，检查没有人物、白纱、珍珠残影。
8. `build` 生成透明 PNG、零位移、分别水平/斜向移动、黑白/棋盘底以及脸、头发、手指、白纱、裙摆、珍珠放大对照。检查原始大小，不能只看缩略图。
9. 每个最终检查项手动改 True 后才生成已审核实验包；否则默认导出 `UNREVIEWED` 草稿。若珍珠遮住人物，必须另确认该未知衣料限制；不会生成新人物/裙摆。
10. 下载 `.spatial-v2.zip`。它是实验生成端素材，**不能导入现有 Android ZIP V1 播放器**。保留原有 V1 包和 APP。

## 命令行

在仓库根目录，用同一个 Python 3.12 环境运行：

```bash
python -m tools.layered_v4.cli init --input wallpaper_001.spatial.zip --work work/my-scene
python -m tools.layered_v4.cli preflight --work work/my-scene
python -m tools.layered_v4.cli lock-models --work work/my-scene
python -m tools.layered_v4.cli segment --work work/my-scene
# 先检查/编辑 mask，再 matting；不要把自动分割当通过。
python -m tools.layered_v4.cli matte --work work/my-scene
python -m tools.layered_v4.cli prepare-masks --work work/my-scene
# 检查当前 alpha/protect/hole 后显式确认。
python -m tools.layered_v4.cli approve-masks --reviewed --work work/my-scene
python -m tools.layered_v4.cli background --work work/my-scene
python -m tools.layered_v4.cli build --work work/my-scene
# 未完成人工局部验收，只允许草稿：
python -m tools.layered_v4.cli pack --draft --output work/my-scene.UNREVIEWED.spatial-v2.zip --work work/my-scene
python -m tools.layered_v4.validate work/my-scene.UNREVIEWED.spatial-v2.zip --allow-draft
```

正式实验审核用 `approve-output --reviewed`（有未知人物区域时另加 `--allow-known-person-occlusion`），然后不带 `--draft` 打包/验证。这是你明确记录审核，不是自动画质证明。

## T4 / Python 故障

- `nvidia-smi` 缺失：代码用 `shutil.which` 检测，不直接执行不存在的命令；以选定解释器内 `torch.cuda.is_available()` 和 VRAM 检查为准。
- `No module named addict`：requirements 显式固定 addict，preflight 会导入；确认安装与运行用同一个 `PY`。
- OOM：停止其他 GPU 进程；每阶段单独运行；matte tile 改 512、inpaint tile 改 768、global_long_side 改 896，overlap 小于 tile。最终输出保持原图分辨率，不能以缩小人物或模糊代替修复。
- 下载失败：检查网络/模型缓存/Hugging Face 权限，不使用假背景。模型 SHA 在 `models.lock.json`；缓存可复用，不要误删旧 DA3 缓存。
- 阴影/白纱黑白边：检查 Alpha 和 trimap、保护区、背景 estimate；逆合成色域裁切会记录。重新编辑、审核、补全和合成，不能只看零位移误差。

## 能与不能保证

代码保证原生坐标、真 Alpha、保护核心 RGB 不变、hole 外原图 RGB 不变、无前景生成、格式与哈希检查。自动分割边界、透明度、背景语义、发丝完整性和模型是否在 T4 实际成功，只能通过模型执行与图像检查确认。

目前云端只有 CPU 结构检查；不要把测试用 `SYNTHETIC_SCHEMA_ONLY` ZIP 当作仙女图生成结果。原图裁掉的珍珠和被珍珠遮住的衣料无法从单图无损恢复。
