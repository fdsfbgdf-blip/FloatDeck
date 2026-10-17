# 原创空间壁纸包

`sunrise.spatial.zip`（白近黑远，默认强度 45%）与 `dusk.spatial.zip`（黑近白远，默认强度 65%）均符合 [FloatDeck Spatial ZIP 标准 1](../../SPATIAL_PACKAGE_FORMAT.md)。可直接通过 V1.1 的“导入空间壁纸 ZIP”使用，无需先解压。

全部图像由 `scripts/generate-spatial-fixtures.py` 和 `scripts/build-spatial-samples.py` 程序生成，采用仓库 MIT LICENSE，没有第三方图像或付费壁纸。

诊断版另提供 `diagnostic_3d.spatial.zip`（深度 16/128/240）与 `diagnostic_constant.spatial.zip`（同一原图，恒深度 128），用于区分 GPU 视差、姿态传感器和素材参数问题。使用步骤见 [3D 效果检测](../../SPATIAL_DIAGNOSTICS.md)。检测图同样为程序生成，按 MIT 分发。
