# V4 SAM 目标选择修复

## 可确认的原因与证据边界

检查基线提交 159dee9：DEFAULT 人物框 [0.03,0.27,1,0.77] 覆盖全图 48.5%，珍珠框 [0,0.63,0.96,1] 覆盖 35.52%；两框交叠约全图 13.02%。已用上传 ZIP 中 1648×2944 的实际 source.png 查看：人物框下沿侵入珍珠上部，珍珠框上部包含裙摆，外围包含水流和岩石。SAM 是提示驱动的对象分割，不知道 Python 变量名 person/pearl 的语义。旧实现只有框，未提供任何正负点，直接 argmax(iou_scores) 写目标 mask：预测 IoU 是候选自身质量估计，不是目标类别正确概率。故选错对象仍可被自动输出。这是已确认的提示歧义与选择逻辑缺陷。

旧坐标路径 normalized XYXY -> 原图 XYXY（x×1648、y×2944）-> SamProcessor 自行变换 -> post_process_masks 还原原图，未发现宽高颠倒或重复缩放。新增非方形测试固定核验。不能为了修复目标错误而改原图尺寸或处理坐标。

未获得用户本次错误 mask、三个候选、scores 或运行 config，因此不能确定用户当次选中哪个候选、分数多少，也不能声称已重现相同 SAM 推理结果。此前 CPU 测试没有覆盖目标选择质量，不能作为真实分割通过证据。

## 修改

- `tools/layered_v4/segmentation.py`：独立可编辑场景框/正负点、原图坐标转换、目标合理性检查、命名叠加图、绑定原图/两个 mask/提示的人工审核记录。
- `cli.py`：DEFAULT 明确使用 sam_prompts；旧 box-only 项目必须显式迁移；新增 check-segmentation / approve-segmentation。通过检查不自动批准。
- `models.py`：人物与珍珠分别请求 SAM，同时给框和正负点；保留三个原生尺寸候选及逐候选检查/分数，不再自动生成目标 mask。每次重跑清除旧目标 mask 和旧审核；严格检查输出为 3×原图高度×宽度。matte 在加载 GPU 前要求当前分割检查和独立人工批准。
- Notebook：显式迁移旧 config；原图、提示框/点、每个命名候选及叠加图全部显示；SELECT 初始 None，必须人工选择。可继续用原生尺寸画笔/外部 L PNG 修正。独立合理性与人工审核关卡；本次 CONTINUE_AFTER_SEGMENTATION=False 阻止后续所有流程。
- tests / Notebook 检查脚本：坐标、框与正负点、错误目标/大片背景/错误尺寸拒绝、旧配置拒绝及无审核禁止 matting。

合理性检查包括正点必须属于目标、负点必须不属于目标、非空且不过大、框外比例以及两层过度交叠。它们是可编辑场景约束，不是完整语义评估；不会裁掉遮罩或靠放宽阈值冒充成功。细发丝、每根手指、透明白纱和裙摆完整性仍必须人工查看与修正。珍珠应只保留照片中可见本体，原图底部裁切仍为已知限制。

新框：人物 [0,.285,1,.735]，珍珠 [0,.665,1,.985]。人物正点覆盖脸/发丝区域/手部/白纱/裙摆，负点覆盖珍珠及背景；珍珠正点在球体内部，负点在人物、裙摆、岩石与底部环境。它们已在实际原图叠加检查位置，但尚未验证实际 SAM 输出。若某一部位无法同时被 SAM 保留，应调整提示或画笔修正，不得选择错误候选后继续。

## 已执行与尚未执行

- 24 项 CPU 测试通过；Python compileall、Notebook 11 个代码单元 AST 和 nbformat 严格检查通过。
- 实际源图框/点叠加可视检查已执行；未修改原图、深度或输入 ZIP。未提交私人图片。
- 当前环境 torch 未安装、nvidia-smi 不存在，未进行 SAM 权重推理；真实分割质量未通过验证。
- **未运行 VitMatte、背景补全、真实素材打包**。未改 Android、正式 Shader、APP V1 导入器或其他分支。

## Colab 重跑顺序（止于分割）

1. 先保存旧错误 mask / proposal / config 供对照；重启 Notebook 会话以免 Python 缓存旧模块。打开最新同分支 Notebook，运行第一“环境/代码”单元，确认拉取最新提交。已有独立环境无需重新安装；若新会话无环境则运行依赖/preflight/lock-models 单元。
2. 已有 WORK/source.png 和 config 时跳过上传/init 单元，避免 init 拒绝覆盖。原图对象 source 不在新内核时先执行 `source=Image.open(WORK/'source.png')`，并导入 PIL Image、IPython display、google.colab files。下方分割单元也会自行导入必要显示对象。
3. 运行“迁移提示与 SAM”单元：第一次 RESET_SCENE_PROMPTS=True；人工调整正负点后设 False，避免重跑覆盖调整。检查命名原图、框/点与全部候选/叠加。此步骤清空旧目标 mask，不清空候选对照文件以外的私人数据。
4. 在新增 SELECT 单元分别选择 person/pearl 候选索引；没有合格候选不要选择，先改提示重跑。必要时运行画笔编辑或外部遮罩上传单元，再重新查看命名叠加图。
5. 运行分割审核单元，检查失败则看 segmentation-check.json 并纠正。SEGMENTATION_REVIEWED 只有逐项查看后才能手动 True；CONTINUE_AFTER_SEGMENTATION 必须保持 False，本轮停在此处。不要运行后续抠图/补全/打包单元。

若仍失败，请保留本次 config.json、person/pearl_proposal.json、候选 PNG、选中 mask 和 segmentation-check.json，以便明确分析真实模型行为。
