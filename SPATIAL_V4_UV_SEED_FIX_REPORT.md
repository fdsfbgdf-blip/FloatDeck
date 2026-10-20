# V4 Colab ensurepip 故障修复

## 根因与范围
用户提供的真实 Colab T4 日志确认：系统 Python 3.12 存在，但 `python3.12 -m venv` 因 `ensurepip is not available` 失败，尚未启动 SAM。本轮未取得或声称重新执行用户的 T4 会话。

共享 `diagnostics.prepare()` 已移除标准库 venv 创建路径。固定 uv 0.6.3，使用 `uv venv --python 3.12 --seed --no-python-downloads`；首次失败时仅允许一次显式 uv 管理 Python 下载/创建重试，不重复执行已知失效命令。uv 工具安装到独立 bootstrap-tools 目录，不替换 Notebook/DA3 内核依赖。

## 修改
- diagnostics.py：真实检查独立解释器版本、sys.prefix 和 pip；健康环境复用。损坏或部分创建的 python312 目录仅重命名隔离，拒绝符号链接；缓存、素材、珍珠结果不移动、不删除。记录 uv 命令、失败与恢复信息。
- coverage_entry.py：复审按钮与环境检查按钮使用相同 prepare；移除入口额外重试，恢复逻辑只有一处。
- Notebook：独立人物覆盖复审入口沿用共享准备器，注明 uv；用户无需前置执行安装单元。
- tests/Actions：新增缺 ensurepip、缺 pip、部分环境、管理 Python 回退、重试上限、健康环境复用、解释器身份错误、符号链接保护测试；保留全新会话、单次上传、固定珍珠、拒绝人物5、诊断 ZIP 测试。

## 保留的真实门禁
确认 Python 3.12 + pip 后才安装固定 torch 2.6.0+cu124 / torchvision 0.21.0+cu124 和固定依赖；继续真实执行 pip check、完整 preflight（CUDA 与 torchvision 小算子、显存/磁盘/导入检查）。任何失败立即停止，不启动 SAM。旧 preflight 不用作通过依据。候选生成仍固定 pearl2，person5 Reject，六个人物覆盖候选只能人工复审；不运行 Alpha、补背景、最终打包，不修改 Android。

## 本轮验证
- 本地 CPU 单元测试：78 项全部通过。
- Notebook JSON、AST、执行顺序和 nbformat 严格校验通过，compileall / git diff --check 通过。
- 实际 CPU smoke：uv 0.6.3 创建独立 Python 3.12，pip 可调用，pip check 成功；输出诊断 ZIP。未安装 torch 或模型来伪造 GPU 测试。
- 模拟系统缺 ensurepip 的测试禁止任何标准库 venv/ensurepip 调用；恢复前后缓存、source 和 pearl_mask 字节不变。
- Colab T4/CUDA 完整环境与真实 SAM 分割：尚未验证。没有新的实际人物候选 ZIP；只有在用户 Colab 真正完成分割后才会输出候选包。
- GitHub Actions：提交后记录结果。

## 用户操作
打开本分支最新版 Notebook，仅运行「人物覆盖复审（白纱/裙摆/腿脚）」单元。素材丢失时按一次上传提示选择已有完整审核包。其余环境准备、检查、固定珍珠、六候选推理和导出自动执行。

下载 `FloatDeck-person-coverage-rereview.zip`。若失败，同名 ZIP 含明确中文“未完成分割”结论和准确命令/退出码/stdout/stderr，而不是分割成功证明；把它发送回来即可。
