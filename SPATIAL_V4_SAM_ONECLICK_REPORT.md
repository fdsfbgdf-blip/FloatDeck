# 一键 SAM 分割与审核导出

## 范围与环境事实

用户已报告提交787ee9d在真实Colab T4通过：Python3.12.9、torch2.6.0+cu124、CUDA12.4、约14.46GiB可用显存、62.8GiB磁盘。环境问题按已解决处理；本轮未修改models.py的preflight、diagnostics.py、依赖清单或已通过的环境单元。

本云端不能访问用户的Colab会话，且无CUDA/torch，**未执行真实SAM推理**。本轮交付是会在用户T4上执行真实SAM的独立单元及可测导出流程；CPU合成测试不是实际仙女分割验收。

## 实现与文件

- Notebook新增独立「一键 SAM 分割与预览」，无需先点击环境安装单元、改代码或选择候选编号。自身更新同分支代码，复用已通过的preflight与专用解释器，不安装依赖；源码拉取/准备失败也导出脱敏诊断ZIP。
- `tools/layered_v4/sam_export.py`：Notebook内核仅做标准库编排；真实SAM在 `/content/floatdeck-v4/python312/bin/python` 子进程执行，不能误用内核解释器。要求project-001/preflight.json是完整GPU通过且解释器匹配；SAM原有gpu_stage仍即时检查CUDA/显存，避免旧报告掩盖当前资源不足，不重复安装。
- 优先使用project-001/source.png与source_depth.png，缺失才提示上传V1 ZIP。上传素材用既有安全读取器在独立目录导入，遇到现存图片冲突停止，绝不覆盖。核对一致后只恢复缺失原图/深度，便于下次复用；无需重跑init。
- 每次在BASE/sam-runs/时间戳下生成独立副本，使用最新PROMPTS（独立框、正负点），不覆盖project中的旧配置/遮罩/人工审核。原图和深度按文件复制，校验尺寸与源哈希，不缩放/旋转/修饰原生素材。
- 仅加载facebook/sam-vit-base。已有segment锁正确则原样复用；无锁只解析SAM的确切SHA并保存，保持已有matte/inpaint锁不变。模型缓存继续BASE/cache；不解析或下载其他模型，不调用VitMatte、SDXL、build或最终spatial打包。
- 人物、珍珠分别保留3个原生L通道候选。重新计算每个候选的正负点/面积/框外等合理性，不自动按IoU选目标。JSON有中文名称、原始分数及完整失败原因；selected=null / human_approved=false，基础通过不等于视觉通过。
- 原图叠加、框点及所有候选生成带中文标题的预览，包括“人物”“前景珍珠”、索引、通过/不合格说明与未审核标记。内嵌75KiB重命名字体子集 `fonts/ReviewLabels.otf`（SIL OFL，版权/许可证随附），不在线安装字体、不显示乱码方框。
- 原生PNG素材/候选保持坐标不变。展示用预览缩小并保存高质量JPEG，避免重复导出多张巨大的原生RGB叠加PNG；所有可评估的原生候选与原图另行保留。预览压缩不影响模型输入或原生mask。

## 审核 ZIP 与失败处理

固定输出 `/content/floatdeck-v4/FloatDeck-SAM-review.zip`，自动下载并提供文件链接。包括：原图与深度、6个原生候选、16张中文审核预览（原图/提示/候选遮罩/叠加）、提示配置、模型revision、源哈希、逐候选检查、preflight与实际SAM耗时/显存记录、生成器提交、命令与完整双流。无论候选是否合格，都不选择/批准，只交给用户转交ChatGPT审核。

任一步失败同样打包已有部分结果及错误诊断，passed=false。只导出明确白名单文件，不递归包含用户文件/缓存/令牌；JSON日志经现有脱敏规则处理。运行前失败亦有Notebook自带回退ZIP。图片属于审核素材，包含在ZIP供用户主动分享；未上传到GitHub。

后续高级手动单元及分割/遮罩/最终审核仍保留默认守卫。本轮到SAM导出即停止。

## 已执行验证

- 41项CPU测试通过：此前36项+5项SAM导出回归。覆盖六候选/命名中文预览、原生尺寸通道、基础不合格标记、无自动选择/批准、ZIP白名单与秘密过滤、无环境批准时零模型命令、解释器与basic检查拒绝、模拟SAM调用使用最新提示/复用版本锁/保留project文件。
- 12代码单元AST、Notebook JSON/严格nbformat、独立SAM单元不安装依赖及后续阶段守卫检查通过；compileall与diff检查通过。
- 以上模拟SAM明确是管道测试，不代表真实SAM分割成功。实际Colab权重下载/缓存命中、T4推理、真实仙女六候选质量、浏览器交互与自动下载仍需新单元实际运行确认。
- GitHub Actions增加SAM导出测试，提交后补充结果。未修改Android、正式Shader、APP V1导入器或其他分支。

## 用户最少操作

打开最新版同分支Notebook，在已通过检查的同一T4会话中，只点击「一键 SAM 分割与预览」播放键。原素材还在则无需上传；丢失时弹框只需选择原始spatial.zip。等待自动下载 `FloatDeck-SAM-review.zip`，把它发给ChatGPT即可，无需看日志/改代码/选候选。若整个Colab运行时被重置导致独立环境与通过报告丢失，才需先点已有的一键环境检查单元；不会绕过这一前置条件。
