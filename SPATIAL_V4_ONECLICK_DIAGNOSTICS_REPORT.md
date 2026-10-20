# V4 一键环境检查交付

## 上一轮修复的实际边界

已检查 3d115ca 的实际改动及最新 b66559c 基线，工作区初始干净、分支 spatial-layered-v4-generator。3d115ca 确实修复了 CLI 对 preflight 无条件读取/校验旧素材配置的问题，且增加失败报告；但用户那次 Colab 只有 CalledProcessError 摘要，没有完整运行结果，因此不能确认实际 T4 故障已解决。它可能还来自依赖、GPU、资源或其他运行时条件；增加日志本身不是解决这些故障。本轮不以“CPU通过”代替T4通过。

## 实现

- Notebook 第一代码单元现在是「▶ 一键环境检查与诊断」。只需选择 T4、点该单元，自动更新当前分支，准备专用 Python3.12、按需安装、pip check、完整 preflight、中文归因、报告 ZIP 下载。启动/拉取代码失败同样走独立打包回退路径。
- `tools/layered_v4/diagnostics.py` 仅用标准库，不需要事先安装 numpy/torch 才能报告安装失败。每条命令记录 argv/退出码/完整 stdout/stderr，启动错误也记录。按步骤归类最可能原因，不从 CalledProcessError 推断 CUDA 成功或失败。
- 独立解释器 `/content/floatdeck-v4/python312/bin/python`。只有创建解释器所需的 uv bootstrap 可使用 Colab 内核；所有生成器依赖、pip check、preflight 都经目标 PY。不改变 DA3 环境。若发现已有目标解释器不是3.12则停止并报告，不静默替换或继续。
- 在缓存写入与磁盘容量检查后选择系统3.12或自动 uv 管理的3.12 创建 venv。逐项读取目标解释器的分发版本：完全符合者跳过安装；torch/torchvision 以精确 `2.6.0+cu124` / `0.21.0+cu124` 匹配并安装，避免仅写2.6.0时旧CPU构建被pip认定“已满足”的问题。其他依赖只安装缺失/版本不符项，安装后再次核对全部直接固定版本，然后 pip check 和实际 preflight。未调整既有依赖版本清单。
- `HF_HOME` 继续使用已有 BASE/cache，保留模型与pip缓存；诊断不调用 lock-models、不读取模型权重、不下载或启动 SAM/VitMatte/SDXL。后续素材单元默认被 ALLOW_ASSET_STAGES=False 锁住。以后显式开启素材阶段时，已有 models.lock.json 原样复用，没有锁才解析版本；模型加载仍要求确切 revision，分割/遮罩/最终人工审核均保留。
- 仍强制 Python3.12、pip无冲突、CUDA可用、≥14 GiB总显存/≥10 GiB空闲、≥20 GiB磁盘、可写缓存及小型 torchvision CUDA 算子检查，不降低限制。nvidia-smi 缺失本身不是失败或成功证据；GPU状态以实际preflight为准。

## 报告、安全与失败行为

每次新建独立诊断会话，仅导出本次生成的结构化报告和命令双流，不递归打包目录。ZIP 名固定：`FloatDeck-environment-diagnostics.zip`。包含 kernel/target 解释器、版本前后对照、安装计划、GPU/驱动/显存观测、缓存/磁盘、完整 preflight或失败阶段、中文建议、生成器提交与每条命令退出码。未执行的GPU检查不填成功。失败也打包，浏览器自动下载失败时提供文件链接与文件面板同名文件。

不导出原图、遮罩、缓存文件、git配置、环境变量全集、访问令牌或密码。导出前过滤敏感环境变量值、Bearer、常见HF/GitHub令牌、URL用户密码与标记的token/password/secret等字段；日志原意保留但秘密替换为 REDACTED。仅打包允许的自建内容，并有泄密回归测试。Bootstrap 使用相同过滤函数的内嵌副本（GitHub不可用时不依赖仓库代码）。未知错误只输出中文停止说明与ZIP，不要求用户自行读日志。

## 已执行验证

- 36 项 CPU 测试通过（原29项+7项诊断测试）：版本判断/跳过安装、错误CUDA构建需修复、完整退出码和双流、ZIP可读取、秘密过滤、禁止夹带私人文件、磁盘/安装失败仍打包、模拟成功路径不启动模型/不重复安装。
- Notebook JSON、11代码单元AST、nbformat严格检查通过；第一单元顺序与全部后续素材守卫检查通过；compileall、diff检查通过。
- 在本云端实际执行解释器/依赖探测与完整CLI preflight：因本环境没有torch，真实退出1并生成失败报告，再实际打包了可读取ZIP。该结果只验证CPU失败报告路径，**不是用户Colab根因，也不是GPU通过**。未在云端安装整套GPU依赖，避免无GPU条件下载大体积轮子。
- 无真实Colab T4可用：Python3.12 venv/uv在线安装、CUDA wheels实际安装、驱动兼容、GPU显存/算子、浏览器自动下载均**尚未验证**；纯mock成功测试明确不构成T4实测。
- GitHub Actions 已扩展执行诊断测试；[运行39389393303](https://github.com/fdsfbgdf-blip/FloatDeck/actions/runs/39389393303) 对代码提交 f597b5459e7509c66412613ccfc4624a64ba6fc3 已 completed / success（包含36项CPU测试、严格Notebook检查与格式fixture校验）。不修改 Android、正式 Shader、V1 APP 导入器或其他分支。

## 用户仅需做的事

打开同分支最新Notebook，Colab选择T4，点击第一单元「一键环境检查与诊断」的播放键，等待中文结论与自动下载；不要运行全部单元。不用改代码、手动执行Python或阅读日志。若仍失败，只把 `FloatDeck-environment-diagnostics.zip` 发回，由我分析并继续修复。需要更改运行时/重启/磁盘处理时会显示具体中文建议，不会假装已自动完成这些浏览器侧动作。
