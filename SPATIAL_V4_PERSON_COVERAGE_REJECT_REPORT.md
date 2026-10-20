# Reject后回到人物语义分割：白纱/腰腹/裙摆/腿脚覆盖复审

## 人工结论与定位

接受用户对 FloatDeck-selected5-alpha-review.zip 的Reject：环境preflight与真实matte执行已成功，失败在人物分割语义覆盖；珍珠2仍通过。此次未附新Alpha ZIP，因此没有声称逐像素检查它。本轮依据明确人工结论，以及此前实际收到的原生人物复审包和原图。

此前“人物5已选定＋轻清理”不能修复漏分区域：清理仅删除，没有恢复白纱/衣料能力；matting在既有trimap附近工作，也不能补回大块完全不在mask里的衣料。已撤销该继续路径，不重复调环境、不把GPU执行成功当画质成功。

旧人物5在新增采样点上的实测覆盖：白纱衣袖0%、胸口腰腹50%、腿脚66.7%、发梢50%；裙摆稀疏点却100%命中。这与人工发现大块裙摆/中部缺失并不矛盾，说明点命中不能证明语义完整。因此本轮加入密集局部可视对比和区域占用统计；占用统计也不作为准确率或自动通过证据。

## 旧批准与后续阶段阻断

新增review_policy.py记录当前GENERATION_PAUSED策略：人物5及Alpha已Reject，当前仅允许人物复审。Notebook移除人物5直接Alpha入口；approved_layers.prepare_selection / validate_selection / execute_alpha均在读图或加载模型之前拒绝；models.matte/background、core.build/pack、CLI批准和后续生成命令也主动阻断。旧工作目录中的审核文件不能绕过最新Reject。此前批准记录属于历史，不会据此继续生成。

现有纯数学/格式CPU测试使用明确隔离的合成沙箱暂时解除策略，仅验证schema/math，不用于真实图片或实际GPU执行。另有专门测试证明生产默认策略在任何模型/素材读取前拒绝matte/background/build/pack。此测试沙箱不出现在Notebook，也不提供用户运行的绕过开关。

## 新候选生成策略

仍只推理person，锁定pearl_candidate_2原始字节与源图/深度哈希；不重算或替换珍珠。

六个新候选不是硬选旧0..5，而是新一轮SAM推理：

- 0..2：full-garment-v2。扩展至[.10,.28,1,.75]提示框，增加白色衣袖、胸口/腰腹连续衣料、左下大裙摆、腿脚、头发末梢的正点，保留明确平台/蓝环/珍珠/水面岩石负点。
- 3..5：parts-union-v2。分别提示头手发、白纱腰腹、裙摆、腿脚四组，各保留SAM三候选；按同一索引做布尔并集生成三个整体候选。并集仅组合实际SAM预测像素，没有膨胀/填洞/补纹理，也不以“最大连通块”清掉衣料。所有12个原生部件mask一并导出，供审核并集是否误吸环境。

并集没有真实SAM IoU分数，因此整体iou_score=null，保留各部件真实分数，绝不虚构平均IoU作为新质量分数。查看排序使用约束错误、最弱部位采样覆盖、珍珠交叠和环境小ROI占用；IoU不参与，排序不是选择或批准。该并集仍可能误吸背景或漏纱，必须实际运行后人工审核，不能预先宣称改善成功。

## 输出与状态

独立Notebook单元改为「人物覆盖复审（白纱/裙摆/腿脚）」。优先复用Colab已有审核输入；丢失时可上传原SAM/人物复审或Alpha审核包，只读源图/深度、固定珍珠2和SAM版本锁，不使用其旧Alpha推进生成。

运行后下载 **FloatDeck-person-coverage-rereview.zip**，与旧包分开命名。包含：六个整体原生L候选、原图叠加/独立遮罩、两组框点预览、白纱/腰腹/裙摆/腿脚/发梢五个局部区域各六套原图＋叠加＋mask对照、原生部件mask、完整box/points/parts配置、部件真实分数、固定珍珠及来源、区域统计、执行/错误日志和中文说明。字体子集扩充局部中文标题字符，保留OFL许可。

人物passed=false、human_approved=false、selected=null、CONTINUE_AFTER_SEGMENTATION=false。只在完整写出六候选后记录candidate_generation_complete=true，明确区分执行完毕与画质通过。失败也下载诊断ZIP。不会运行matte、inpaint、背景、最终spatial打包。

## 验证与执行限制

本地59项CPU测试通过；Notebook13代码单元AST/严格nbformat、compileall与diff检查通过。新增测试覆盖部件并集不添加部件之外像素、拒绝尺寸错位、衣料/腿脚配置、脸部mask不能被误报全身覆盖、生产默认全链路Reject阻断，以及未来新批准也不能复活旧人物5继续入口。既有人物only、珍珠哈希保留、ZIP路径安全、无自动选择、IoU不排序测试继续通过。

有一项使用此前真实人物mask的本地结构回归，仍只验证历史清理算子，没有表示被Reject的人物语义合格；私人fixture不提交，CI缺少时明确skip。其余合成测试同样不证明新真实画质。

本云端无T4/torch、不能访问用户Colab。本轮新SAM推理与新真实复审ZIP **尚未产生**，需要在用户T4运行该单元；不会把旧mask并集或合成测试结果包装成新真实结果。未上传任何真实mask/原图/Alpha到公共仓库；未改Android/正式Shader/APP V1导入器、依赖或环境预检查。[Actions39399682266](https://github.com/fdsfbgdf-blip/FloatDeck/actions/runs/39399682266) 对代码提交ddf116239264fee43881f08e05c430d741c3a089已completed/success。CI59项中58通过、1项私人素材回归明确skip；本地59项全部通过。

## 用户只需操作

在已通过环境检查的T4会话打开最新Notebook，点击「人物覆盖复审（白纱/裙摆/腿脚）」，等待自动下载FloatDeck-person-coverage-rereview.zip，发回人工审核。不运行全部单元，不再点击旧人物5 Alpha流程。
