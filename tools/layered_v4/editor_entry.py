"""Colab kernel bridge: all image/model operations use the checked target Python."""
import json,time,zipfile,traceback
from pathlib import Path
from .diagnostics import DiagnosticSession,redact
from .coverage_entry import ensure_environment,has_review

def launch(base,root):
    from google.colab import files,output
    from IPython.display import display,HTML
    base=Path(base);root=Path(root);s=DiagnosticSession(base,root)
    def failure(exc):
        s.report.update(passed=False,error=str(exc),traceback=traceback.format_exc(),message='遮罩编辑未完成；请发送诊断ZIP。')
        z=s.finish();files.download(str(z));return {'error':'操作未完成，诊断ZIP已下载；已保存的进度保留。'}
    try:
        env,_=ensure_environment(s);work=base/'person-mask-editor';work.mkdir(exist_ok=True)
        def command(action,data):
            request=work/'request.json';request.write_text(json.dumps(data,ensure_ascii=False))
            r=s.command([env['python'],'-m','tools.layered_v4.mask_editor',action,'--work',work,'--request',request])
            # GPU stage may print progress to stdout; the final line is the bridge response.
            return json.loads(r.stdout.strip().splitlines()[-1])
        if not (work/'editor.json').exists():
            candidates=[base/'FloatDeck-person-local-rereview.zip',base/'editor-input.zip']
            archive=next((p for p in candidates if has_review(p)),None)
            if archive is None:
                print('请上传完整 FloatDeck-person-local-rereview.zip，或此前导出的遮罩编辑审核ZIP。')
                data=files.upload()
                if len(data)!=1:raise ValueError('请选择一个完整审核ZIP')
                archive=base/'editor-input.zip';archive.write_bytes(next(iter(data.values())))
                if not has_review(archive):raise ValueError('审核ZIP缺少完整原图/深度/珍珠2/模型锁')
            command('init',{'archive':str(archive),'project':str(base/'project-001')})
        def callback(action,data):
            try:
                if action not in ['view','draft','select','propose','preview','apply','undo','redo','export']:raise ValueError('未知编辑操作')
                result=command(action,data)
                if action=='export':files.download(result['zip'])
                return result
            except Exception as exc:return failure(exc)
        output.register_callback('floatdeck.editor',callback)
        display(HTML((root/'tools/layered_v4/editor_ui.html').read_text()))
    except Exception as exc:failure(exc)
