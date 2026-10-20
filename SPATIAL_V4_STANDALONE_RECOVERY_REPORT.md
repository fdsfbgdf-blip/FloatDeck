# 人物覆盖复审：全新会话与丢失 preflight 的独立恢复

## 根因与实际修复

已确认上一入口在调用任何环境准备前，直接执行require_environment(project, PY)，而该函数直接读取project-001/preflight.json。全新/重置Colab会话中该文件不存在，故FileNotFoundError发生在SAM之前；models_started=false与此路径一致。此前入口的“独立”仅指代码更新和按钮独立，没有包含运行时状态恢复。这是项目启动顺序缺陷，不是新的T4/CUDA/分割错误。

本轮不是仅增加日志或把文件缺失当通过：Notebook不再先调用require_environment，而由新coverage_entry.py明确编排 **真实当前环境准备/预检查 → 审核包验证/素材恢复 → 人物SAM**。任何前置失败都不会调用SAM。

## 修改

- `coverage_entry.py`：标准库即可导入的独立编排，不要求先在Notebook内核安装numpy/torch。建立项目目录，隔离旧preflight，然后调用既有diagnostics.prepare。每次点击都重新进行真实当前preflight；已有正确依赖版本与缓存继续复用，不反复安装。旧报告的passed字段不作通行证。
- 自动处理缺失的专用Python3.12/依赖/缓存；若专用venv存在但解释器失效，仅在确认它是本生成器目录、非符号链接且有pyvenv.cfg时可逆重命名隔离，至多重建一次。不会删除/改动DA3或用户素材。CUDA、显存、磁盘门槛及pip check保持。
- `diagnostics.py`：新增后续任务文案参数，环境成功后显示“继续人物覆盖复审”，不再误提示“停在此处”；每次调用先重置passed/gpu_verified并清除上一调用的preflight内存记录，防止重试误用旧值。
- `sam_export.py`：底层严格检查器对缺失/损坏记录返回明确RuntimeError，保留严格验证；它本身不伪造文件。真正自动恢复放在独立入口中，而不是捕获异常后默许通过。
- `cli.py`：新增restore-review CPU步骤。由同一目标Python执行安全ZIP/尺寸/通道/hash/珍珠锁校验；恢复缺失原图/深度到project，固定珍珠2并记录人物5Reject，成功后才允许运行原person-review命令。原图/深度原生坐标不变，不导入包内的旧preflight或人物批准。
- 对“珍珠锁仍在、pearl_mask文件丢失”的部分重置，先隔离验证审核包，再仅从完全匹配锁定源哈希/珍珠哈希的候选2恢复缺失文件；冲突遮罩不覆盖、不猜其他候选。
- 素材选择：优先复用现有完整审核包。没有时仅调用一次上传回调，接受FloatDeck-SAM-review.zip或包含source/depth/pearl_candidate_2/models.lock的完整复审包。取消/无效上传不循环开窗；已上传但校验失败不会启动SAM。环境未通过前不弹上传框。
- Notebook「人物覆盖复审（白纱/裙摆/腿脚）」自行更新代码、reload新编排模块、恢复/检查环境、必要时上传一次、验证素材、运行六候选并下载。无需先点其他环境单元、改路径或运行Python。失败时不显示旧候选当新结果，已生成ZIP后的内嵌预览错误不会把成功运行误改写成“未启动SAM”。
- `person_review.py`：无论完整候选或失败，都在ZIP根目录写入 **00_请先阅读_运行结果.txt**，明确包是“候选完整但待审核”还是“未完成分割：失败诊断”。附阶段、执行状态、中文原因、完整脱敏报告/日志；不再只有JSON。Notebook源码拉取失败也有不依赖仓库模块的中文回退ZIP。

## 真实检查与状态约束

真实预检查仍由原Python3.12解释器运行：固定依赖版本、pip无冲突、实际torch CUDA与torchvision小算子、GPU/显存、缓存可写与磁盘检查。没有复制旧preflight.json。审核ZIP中的同名文件即使声称通过，也完全忽略。

珍珠候选2继续按哈希固定；人物5Reject和GENERATION_PAUSED不变，不导入任何旧人物审批。输出人物passed=false/human_approved=false，完整执行只记candidate_generation_complete=true。本轮不会调用Alpha/matte、background/inpaint、build或最终spatial打包。

## 自动化验证

本地71项CPU测试全部通过，其中新增12项恢复回归：

- 全新Colab模拟、无venv/无项目/无preflight，准备成功后只上传一次，实际CPU素材验证在SAM模拟前完成。另执行真实prepare控制流（仅外部命令mock），验证创建专用venv、所有pip安装都使用目标PY、真实preflight命令路径以及环境阶段不启动任何模型。
- 空项目但已有审核包，无需上传；记录缺失自动重建；旧passed或损坏JSON均隔离后重新验证。
- CUDA失败：不弹上传框、不调用SAM，下载包含醒目中文失败说明，models_started=false。
- 上传取消或无效：只调用一次，无模型命令。
- 包内旧preflight/旧人物批准不复制；恢复源/深度与固定珍珠的原字节。
- 部分重置丢失珍珠/深度文件安全恢复，锁身份不变。
- 专用损坏venv只隔离/重建一次。
- require_environment缺失记录不再抛原始FileNotFoundError，独立入口会先恢复而不是直接走此失败路径。

模拟环境/安装/SAM边界明确用mock，素材恢复使用真实子进程CLI和真实CPU ZIP/原生图像/hash验证；不将mock称为GPU实测。原诊断测试仍验证已满足依赖跳过安装。

Notebook13代码单元AST/严格nbformat、单一上传调用/入口顺序检查、compileall、diff检查通过。私有真实mask回归仅本地运行，不向公共仓库上传；CI缺少fixture时明确skip。[Actions39419356547](https://github.com/fdsfbgdf-blip/FloatDeck/actions/runs/39419356547) 对代码提交d8a66b3ccd6be052f216c4fd9520270b646e52d5已completed/success；CI共71项、70通过、1项私人素材回归明确skip。本地71项全部通过。

**当前云端没有真实T4/torch，也不能访问用户Colab会话。全新Colab在线安装、真实CUDA恢复和新六候选推理尚未实测，不宣称分割成功。** 本轮没有运行Alpha/背景/最终生成，未改Android或其他分支。

## 用户唯一操作

在Colab选择T4后打开最新Notebook，只点「人物覆盖复审（白纱/裙摆/腿脚）」。它会自动处理环境与状态；确实缺素材时只需在弹框选择原SAM审核ZIP或完整复审包一次。等待自动下载 **FloatDeck-person-coverage-rereview.zip**。若为失败包，根目录中文说明会明确写“未完成分割”；直接发包即可，无需处理路径/命令/日志。
