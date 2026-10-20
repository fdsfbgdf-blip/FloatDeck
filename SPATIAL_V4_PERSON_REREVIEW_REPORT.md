# 固定珍珠2，仅重做人像候选

## 已知事实与本次边界

依据用户明确人工结论：pearl_candidate_2通过，人物0/1/2均不通过；旧0背景误吸，旧1/2缺人物。旧0不得直接作为最终人物mask。此结论作为授权输入，不重新猜测珍珠。

**本轮消息没有可读取的新附件指针，workspace附件中也只有此前wallpaper_001.spatial.zip，没有最新FloatDeck-SAM-review.zip。** 因此尚未对最新人物候选逐像素复核，也没有获得已批准珍珠的实际哈希。已要求补附该包，同时Notebook入口可从Colab原审核ZIP读取，缺失时弹框上传。未伪造已导出的真实复审ZIP或人物改善结果。本云端无可用T4/torch，也无法访问用户Colab会话。

## 实现

- `segmentation.py` 新增两个人物提示配置：focused框[.20,.285,.995,.715]、skirt-coverage框[.13,.285,1,.735]，原框[0,.285,1,.735]。第一组收紧左侧和下沿，第二组保留更多左侧裙摆供完整性对照。只改变提示，不裁掉SAM输出。
- 正点在脸、胸口、左掌/手指附近、裙摆中心、右腿/脚附近、右侧头发和衣料；负点在左平台、蓝环、左下反光、珍珠及右岩石/背景。基于原始仙女图与用户误吸描述提出，**尚未经过本轮真实SAM验证**。小型环境ROI只是诊断点，不当作像素真值、也不用于擦除遮罩。
- `models.py` 支持targets=('person',)、两组提示各三候选，共6候选；保留每个候选的提示、box、原图XY点、分数和原生L遮罩。绝不以最高IoU选择。带fixed-pearl.json的目录禁止包含pearl的重新分割，且人物重跑不删除/改写珍珠。
- `cli.py` 新增person-review独立命令；`person_review.py` 实现安全审核ZIP读取、固定候选2、人物推理、指标/对比、中文预览、复审ZIP导出。全部在现有目标Python执行，不重装依赖，不改环境预检查。
- 珍珠文件只取包内精确名称pearl_candidate_2.png，验证原生尺寸/二值L通道后按文件字节复制为pearl_mask.png；锁定其SHA256以及source/depth SHA。后续重跑若源素材或固定珍珠不一致立即停止，无其他候选回退。绑定来源写入fixed-pearl.json，表示仅珍珠已获用户批准；人物仍未批准。
- 如果project已有源图/深度，审核ZIP必须匹配；不同输入不覆盖。项目旧人物/全场景审核失效，person_mask不再被误当作可用。独立person-review-runs目录保留输出，原始图/深度文件不改。
- `sam_export.py` 预览支持可变候选数；旧双目标入口在珍珠已固定后停止并要求使用人物复审入口，避免误触重算珍珠。
- Notebook新增独立「人物分割复审导出（固定珍珠2）」：自带代码更新/环境通过检查、复用审核ZIP或提示上传、六候选推理、中文预览及自动下载。CONTINUE_AFTER_SEGMENTATION=False / ALLOW_ASSET_STAGES=False，不运行VitMatte、inpaint、layered generation或最终spatial打包。

## 复审包

运行后下载 `FloatDeck-person-rereview.zip`。候选为person_candidate_0..5.png，保留原生尺寸与坐标；包含固定pearl_candidate_2与相同字节pearl_mask、来源哈希/批准范围、source/depth、旧person0（若有，仅对比）、提示配置与逐候选参数、ROI/正负点、JSON检查、中文说明、原生候选的独立展示/叠加及两组框点预览、版本锁/执行记录、脱敏命令双流。失败同名包保存部分结果和错误，不需用户分析日志。

推荐先看的两个编号仅按：基本约束错误→珍珠交叠→环境小ROI占用→正点覆盖排列，IoU不参与。报告逐候选列环境ROI占用及相对旧0变化（如果提供旧0），并列正点覆盖。负变化只说明该ROI占用减少，不能宣称整块环境错误已消失。正点覆盖也不等于发丝/透明衣料/手指完整性。报告不自动选择，不会把“best”写成person_mask。

两组提示生成后哪版更完整、哪些误吸真正消除，必须等待真实模型输出/人工复审；代码和合成测试不能替代此结论。

## 验证与限制

48项CPU测试通过：原41项+人物复审7项，覆盖person-only调用、六候选和待审状态、珍珠字节/哈希不变与篡改拒绝、ZIP路径安全、排序不看IoU、提示坐标与范围、人物重跑不删除珍珠、通用分割禁止覆盖锁定珍珠。Notebook13单元AST/严格nbformat与阶段守卫检查、compileall、diff检查通过。

测试中的SAM为显式fake，只验证流程，不代表真实分割成功。当前没有真实人物复审输出，未生成可称为实际仙女结果的ZIP；新包由Colab运行后产生。未改Android/正式Shader/APP V1导入器或其他分支。Actions结果提交后补充。

## 用户操作

打开同分支最新Notebook，仅点击「人物分割复审导出（固定珍珠2）」。审核包仍在Colab时自动复用；否则在弹框选择已审核的FloatDeck-SAM-review.zip。等待下载FloatDeck-person-rereview.zip，发回人工审核；不要运行后续生成。环境会话若已丢失，则先恢复已有一键环境检查，不绕过其约束。
