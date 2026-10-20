# Colab 安装与预检查错误报告修复

## 根因与证据边界

基线 4ed133e 的 CLI 在所有命令分发前无条件读取 config.json 并执行 check_config。SAM 修复新增 sam_prompts 必填，因此旧素材配置会抛出 `ValueError: Old box-only config: migrate sam_prompts explicitly before segmentation`，甚至尚未进入 preflight。这是可确认的代码错误；新增回归还使用损坏的旧配置证明环境预检查不应依赖素材 JSON。

旧 Notebook run 使用 subprocess.run(check=True)，没有 capture_output；故源码没有主动吞掉 stderr，但依赖 Colab 转发子进程流，没有在失败时重新显示，也不留日志，CalledProcessError 本身不包含实际原因。无法仅凭用户提供的异常摘要确定当次是配置错误、依赖导入、Python、CUDA、显存或磁盘失败。此次修复诊断能力，**不宣称已确认用户运行时的最终根因**。

旧 preflight 只在全部成功后保存报告，失败可能留下旧成功文件；显存错误没有输出实际数值。metadata.version 只能证明分发存在，不能证明 torchvision 等二进制能导入/运行，pip check 也不能证明 CUDA 可用。

## 文件与修改目的

- `tools/layered_v4/cli.py`：preflight、lock-models 不读取或校验素材配置；其他命令仍严格校验，未迁移的分割配置不会被自动放行。
- 新增 `tools/layered_v4/process.py`：标准库子进程封装，打印真实 shell 可读命令/退出码、完整 stdout/stderr；原文分别保存到 `/content/floatdeck-v4/command-logs/<时间戳>.*`。不打印环境变量。失败重新抛出携带双流的 CalledProcessError，停止单元。没有截断日志。
- `models.py`：分阶段诊断 Python、缓存、依赖版本/实际导入、同一解释器 pip check、CUDA、显存、磁盘及极小 torchvision CUDA NMS 操作；无模型下载。失败写入 passed=false、stage、异常和 traceback，覆盖旧成功。保留 Python3.12、CUDA 可用、总显存至少14 GiB/空闲至少10 GiB、磁盘至少20 GiB 原要求；错误报告真实观测数值。basic_only 仍不代表 GPU 通过。
- Notebook：run() 与安装/pip check 都经同一 PY 和日志封装；顺序保持定义辅助函数 → 安装 → pip check → preflight。成功显示 preflight.json；本次默认 LOCK_MODEL_REVISIONS=False 停留在诊断，显式开启后仍须锁定 revision，不能跳过模型阶段的锁定要求。所有人工审核和分割暂停开关保留。
- CPU 测试、Notebook 静态检查脚本：覆盖命令/完整双流/退出码与落盘、旧配置解耦、Python/缓存失败及旧成功覆盖；用明确标注的 mock 测试 CUDA不可用、显存不足、磁盘不足、pip冲突均拒绝且归因正确。模拟测试不是 GPU 验证。

已审查 `requirements-colab.txt`：numpy/scipy/Pillow/transformers/diffusers/accelerate/safetensors/huggingface-hub/addict 固定清单与现有 torch2.6.0 + torchvision0.21.0 cu124 安装方案。未发现足以依据本次异常修改版本的证据，因此清单不变；未在本环境安装全部模型依赖，不声称该组合已通过 Colab 实装。

## 验证

本地 29 项 CPU 测试通过；Notebook JSON、11 个代码单元 AST、nbformat 严格检查通过；compileall 与 diff whitespace 检查通过。独立 Python3.12 解释器用于上述检查；无 torch、无 nvidia-smi，无真实 T4/CUDA，未进行 GPU 导入/算子/显存验证。未启动 SAM/VitMatte/背景补全，也未下载全部模型。[GitHub Actions 39388269448](https://github.com/fdsfbgdf-blip/FloatDeck/actions/runs/39388269448) 对代码提交 3d115cab0632bb89bf67b2197cfcaa3e315f9416 已 completed / success。

另在本云端真实执行了完整 CLI preflight（未使用 --basic）：按预期退出 1，报告 stage=dependencies、passed=false、gpu_probe_passed=false、具体异常 `No package metadata was found for torch`，stderr 已落盘。这验证真实失败报告路径，不能用于解释用户 Colab 的具体失败，亦不构成 GPU 成功。

Android、正式 Shader、V1 APP 导入器无修改，只提交 spatial-layered-v4-generator 分支。

## Colab 准确操作步骤

1. 打开 GitHub 分支上的最新 Notebook。保留 T4 GPU，重启内核避免旧模块缓存；不要删除素材或已有环境。运行第一“环境/代码”单元，确认 generator commit 更新以及 PY 为 `/content/floatdeck-v4/python312/bin/python`。如使用既有 DA3 解释器，只作 base，新依赖仍装在独立 PY 环境。
2. 运行第二“安装与预检查”代码单元。每条安装、pip check、preflight 都使用该 PY，且显示命令/退出码。保持 LOCK_MODEL_REVISIONS=False；不运行上传、SAM 或后续单元。
3. 如果只重跑诊断且已装完依赖，可以在第一单元后新建临时单元仅执行 `run('preflight')`，不使用 --basic。成功查看 `(WORK/'preflight.json').read_text()`；失败查看已完整打印的 stderr/stdout、同目录报告和 BASE/command-logs。若失败发生在 CLI 模块导入前，没有新 preflight.json，此时以该命令完整 stderr 为准，不拿旧报告当成功。
4. 按真实失败阶段处理：python→检查 PY 路径并重建错误版本的独立环境；dependency-imports / pip-check→根据具体缺包/冲突修复该 PY 的安装，不能忽略；cuda→确认 Colab T4 已分配且该 PY 是 CUDA wheel，根据驱动/库原始错误处理；vram→关闭其他 GPU 模型或重新启动 T4 运行时；cache/disk→释放该缓存磁盘空间或使用可写且充足的 HF_HOME。不要降低门槛或跳过检查。
5. 修复具体问题后只重跑 `run('preflight')`。请提供失败命令对应 stderr、stdout 与 preflight.json（如生成），以确定真实 Colab 根因。本轮到预检查结束，后续分割/生成不自动开始。
