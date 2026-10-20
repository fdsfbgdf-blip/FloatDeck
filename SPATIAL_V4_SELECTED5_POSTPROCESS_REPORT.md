# 人物5已选定、轻度清理与后处理入口

## 实际输入与审核范围

实际读取用户上传的 FloatDeck-person-rereview.zip（8,852,578字节，36文件）。用户明确批准person candidate5与pearl candidate2，并授权轻度清理及继续后续处理。本轮没有重跑SAM或重新要求选择人物候选。

代码中的APPROVED_INPUTS绑定用户这次实际文件：source、depth、人物5及珍珠2的SHA256，不把“候选编号5”泛化成任意新素材的自动批准。selection.json中人物5与珍珠2均selected=true、human_approved=true；清理结果绑定独立SHA，明确用户批准范围为二值选择加轻清理，不冒充已审查尚未产生的Alpha/补全背景/最终输出。

源RGB/深度保持1648×2944、文件字节不变。珍珠mask与候选2逐字节一致。已有原V1 input.json在哈希一致时保留；缺失时透明记录为审核包衍生来源，不伪造原V1来源。

## 实际清理与目视检查

读取实际候选5：462,398前景像素、61个8连通区域。最大主体区域386,314像素；另有39,767像素的独立右腿区域。因此不能“只留最大块”，否则会严重破坏人物。

已查看原图叠加与连通区域标注，确认左下反光/环境块、下沿与蓝环周围独立碎块。清理算法只删除原有前景中的特定独立区域：左下小块、下沿小块、蓝环小块及远离主体的极小碎屑。主体连通块始终保留；头发、手/光球、上身衣料及右腿附近保护区域只用来保留已有像素，不用于扩大人物。没有腐蚀、膨胀、平滑、填洞、拉伸、重绘或添加前景。删除超过已批准面积6%立即停止。

**实际结果：** 删除26个区域、22,836像素（4.9386%）；剩35个区域、439,562前景像素；新增像素0；最大主体及独立右腿保持。选中预览、清理后mask、彩色叠加、红色删除标记已在工作区实际生成并检查。未把蓝环、平台、下方大环境重新并入。未声称修复原候选本就缺失的透明衣料/细发丝，清理无法创造未分割细节。

## 产物与私有下载

本云端实际生成了完整准备包 `work/v4/FloatDeck-selected5-cleaned.zip`（原图/深度/选择/遮罩/叠加），以及仅遮罩包 `work/v4/selected5-mask-download/FloatDeck-selected5-masks.zip`。这些是真实CPU处理结果，不是假装生成的Alpha或背景。

自动审批拒绝将真实壁纸衍生遮罩样本推送到未确认私有可信的GitHub目的地，理由是代码提交授权不等同于图像衍生数据公开外发授权。因此代码提交排除全部真实素材、遮罩及预览；不以其他公开通道绕过。实际素材回归仅在本地运行，CI无私人fixture时明确skip这一项。用户通过自己Colab会话运行新单元后，从私人会话自动下载包含相同清理结果、原图叠加和Alpha产物的 `FloatDeck-selected5-alpha-review.zip`。

这些准备包不是最终spatial-v2；当前云端没有GPU抠图或背景结果。不会提供不存在的下载链接或把源码附件当实际素材包。

## 后续实现

- 新 `approved_layers.py`：精确素材绑定、轻清理、选择记录、源/珍珠保护、原图/差异预览、VitMatte执行、Alpha黑白底与真RGBA预览、阶段ZIP导出。无任何SAM调用。
- `segmentation.py`：保留真实基础检查结果；只对这次明确人工批准且哈希绑定的选择，允许人工目标判断覆盖SAM点/框启发式失败。不会把失败指标写成passed=true。普通未审核/被改动遮罩仍拒绝进入matte。
- Notebook新增 **“已选人物5：轻清理、Alpha抠图与导出”**。复用当前T4环境与审核包，自动处理、运行原生tile VitMatte、导出 `FloatDeck-selected5-alpha-review.zip`。已有模型缓存和确切revision复用，无matte锁时只解析VitMatte，既有SAM不重跑、不刷新。
- Alpha结果导出原生Alpha、保护核心、trimap/hole/未知遮挡、源RGB加真实Alpha的RGBA**预览**和黑/白底。此预览尚未用补全背景去污染，不冒充最终干净人物层。
- 用户通过的是人物二值候选，并未看到新matting/白纱结果，因此保留现有Alpha/背景/最终验收。新单元运行完会直接下载待验收Alpha包；不自动写masks_approved或最终accepted=true，也不偷偷启动SDXL。Alpha审核后可继续已有背景生成/合成/位移检查/最终打包流程。

## 已执行与未执行

54项CPU测试通过，含实际提供的原生人物mask回归：清理逐像素等于下载结果、删除数/区域数精确、右腿保护、原样珍珠哈希、无新增像素。另测试精确输入绑定（不同素材不能套用批准）、清理后篡改失效、真实失败指标保留、人审只批准二值、mock matting输出真RGBA但不冒充GPU实测/不批准背景。

Notebook14代码单元AST/严格nbformat/阶段顺序守卫通过；compileall、git diff检查通过。[Actions39397330712](https://github.com/fdsfbgdf-blip/FloatDeck/actions/runs/39397330712) 对代码提交aa52a5be750b973dd6eb4081fa7254c03a2dd4ab已completed/success；CI执行54项，其中私人素材回归明确skip、其余53项通过。本地54项全部通过。实际用户mask回归在本地执行通过；CI按明确skip保留该检查，未上传私人fixture。

从用户提供ZIP可见**此前**真实SAM记录：28.982秒、峰值allocated2.708GiB/reserved3.025GiB、执行成功。这是用户T4包内记录，不是本轮云端推理。

本云端没有torch/CUDA/T4，无法访问用户Colab会话。本轮VitMatte、背景补全、合成/位移回归及最终生成**未运行**。没有伪造Alpha或背景，也不把CPU清理称为生成模型验证。Android/正式Shader/APP V1导入器未改。

## 用户最少操作

清理产物已在私有工作区生成。通过自己的Colab下载并继续后处理时，在同一T4会话打开最新Notebook，只点击“已选人物5：轻清理、Alpha抠图与导出”。复审包还在则直接复用，否则选择这次已审核的FloatDeck-person-rereview.zip上传。无需重跑SAM或重新选编号。等待下载FloatDeck-selected5-alpha-review.zip，发回后审核透明白纱/发丝/黑白边并继续背景生成。
