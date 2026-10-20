"""Persistent native-pixel manual corrections; proposals never approve a person."""
import json,io,base64,zipfile,shutil,time,argparse
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from .core import read_image,load_mask,save_mask,digest
SIZE=(1648,2944)

def state(p):return json.loads((Path(p)/'editor.json').read_text())
def persist(p,s):
    tmp=Path(p)/'editor.json.tmp';tmp.write_text(json.dumps(s,ensure_ascii=False,indent=2));tmp.replace(Path(p)/'editor.json')
def initialize(p,archive,project):
    from .coverage_entry import restore_assets
    p=Path(p);p.mkdir(parents=True,exist_ok=True)
    if (p/'editor.json').exists():
        s=state(p)
        if digest(p/'source.png')!=s['source_sha256'] or digest(p/'pearl_mask.png')!=s['pearl_sha256']:raise ValueError('编辑素材或珍珠锁不一致')
        return s
    restore_assets(archive,p,project)
    if read_image(p/'source.png','RGB').size!=SIZE:raise ValueError('本素材必须保持1648×2944，禁止自动缩放')
    with zipfile.ZipFile(archive) as z:
        if 'editor.json' in z.namelist():
            restored=json.loads(z.read('editor.json'))
            if restored.get('source_sha256')!=digest(p/'source.png') or restored.get('pearl_sha256')!=digest(p/'pearl_mask.png'):raise ValueError('保存进度与素材不匹配')
            import re
            for name in z.namelist():
                if re.fullmatch(r'(revision_[0-9]+|background_[0-9]+|initial_[0-2])\.png',name):
                    target=p/name;target.write_bytes(z.read(name));load_mask(target,SIZE)
            for rev in restored['history']:
                if not isinstance(rev,int) or rev<0:raise ValueError('非法历史记录')
                load_mask(p/f'revision_{rev}.png',SIZE);load_mask(p/f'background_{rev}.png',SIZE)
            if not -1<=restored['cursor']<len(restored['history']):raise ValueError('非法历史位置')
            restored.update(human_approved=False,passed=False,all_previous_person_rejected=True)
            persist(p,restored)
            if 'draft.json' in z.namelist():(p/'draft.json').write_bytes(z.read('draft.json'))
            return restored
        for i in range(3):
            name=f'local-masks/composition_{i}.png'
            if name not in z.namelist():raise ValueError('请上传完整局部复审ZIP（含三个组合）')
            target=p/f'initial_{i}.png';target.write_bytes(z.read(name));load_mask(target,SIZE)
    s={'source_sha256':digest(p/'source.png'),'pearl_sha256':digest(p/'pearl_mask.png'),'history':[], 'cursor':-1,'selected_initial':None,'human_approved':False,'passed':False,'all_previous_person_rejected':True,'events':[]}
    persist(p,s);return s

def select_initial(p,index):
    p=Path(p);s=state(p)
    if s['history']:raise ValueError('已有编辑进度，请继续编辑或撤销，不覆盖')
    if index not in range(3):raise ValueError('invalid initial choice')
    m=load_mask(p/f'initial_{index}.png',SIZE)>0;m&=~(load_mask(p/'pearl_mask.png',SIZE)>0)
    save_mask(p/'revision_0.png',m);save_mask(p/'background_0.png',np.zeros_like(m))
    s.update(history=[0],cursor=0,selected_initial=index);persist(p,s)

def current(p,s=None):
    s=s or state(p)
    if s['cursor']<0:raise ValueError('先选择一个未通过的组合作为编辑起点')
    return s['history'][s['cursor']]

def marks(size,strokes):
    w,h=size;bg=Image.new('L',size);draw=ImageDraw.Draw(bg);points=[];labels=[]
    if len(strokes)>1000:raise ValueError('标记过多，请分次修正')
    for st in strokes:
        label=st['label'];radius=int(st.get('radius',4));pts=st['points']
        if label not in (0,1) or not 1<=radius<=40 or not pts:raise ValueError('invalid stroke')
        for x,y in pts:
            if not 0<=x<w or not 0<=y<h:raise ValueError('标记越界')
        sampled=pts[::max(1,len(pts)//16)]
        points.extend(sampled);labels.extend([label]*len(sampled))
        if label==0:
            if len(pts)>1:draw.line([tuple(v) for v in pts],fill=255,width=radius*2)
            for x,y in pts:draw.ellipse((x-radius,y-radius,x+radius,y+radius),fill=255)
    if not points or len(points)>512:raise ValueError('请标记局部人物/背景，单次最多512提示点')
    return np.array(bg)>0,points,labels

def roi_mask(size,roi):
    w,h=size;x0,y0,x1,y1=map(int,roi)
    if not 0<=x0<x1<=w or not 0<=y0<y1<=h:raise ValueError('请选择有效局部修正框')
    a=np.zeros((h,w),bool);a[y0:y1,x0:x1]=True;return a

def merge_local(before,prediction,roi,background,pearl):
    arrays=[np.asarray(x)>0 for x in (before,prediction,roi,background,pearl)]
    if any(x.shape!=arrays[0].shape for x in arrays):raise ValueError('原始坐标尺寸不一致')
    before,prediction,roi,background,pearl=arrays
    out=before.copy();out[roi]=(prediction&~background&~pearl)[roi];return out

def propose(p,request):
    from .models import gpu_stage,model_spec
    import torch
    from transformers import SamModel,SamProcessor
    p=Path(p);(p/'proposal.json').unlink(missing_ok=True);s=state(p);rev=current(p,s);roi=roi_mask(SIZE,request['roi']);bg,points,labels=marks(SIZE,request['strokes'])
    if any(not roi[int(y),int(x)] for x,y in points):raise ValueError('全部标记必须位于修正框内')
    bg=(bg&roi)|(load_mask(p/f'background_{rev}.png',SIZE)>0)
    before=load_mask(p/f'revision_{rev}.png',SIZE)>0;pearl=load_mask(p/'pearl_mask.png',SIZE)>0
    def run():
        spec=model_spec(p,'segment');processor=SamProcessor.from_pretrained(**{'pretrained_model_name_or_path':spec['id'],'revision':spec['revision']})
        model=SamModel.from_pretrained(spec['id'],revision=spec['revision']).to('cuda').eval()
        try:
            inputs=processor(images=read_image(p/'source.png','RGB'),input_boxes=[[request['roi']]],input_points=[[points]],input_labels=[[labels]],return_tensors='pt')
            original=inputs.pop('original_sizes');reshaped=inputs.pop('reshaped_input_sizes')
            with torch.inference_mode():output=model(**{k:v.to('cuda') for k,v in inputs.items()},multimask_output=True)
            masks=processor.image_processor.post_process_masks(output.pred_masks.cpu(),original,reshaped)[0][0].numpy()
            if masks.shape!=(3,SIZE[1],SIZE[0]):raise ValueError('SAM输出尺寸错误')
            for i,m in enumerate(masks):save_mask(p/f'proposal_{i}.png',merge_local(before,m,roi,bg,pearl))
        finally:del model,processor
    gpu_stage(p,'editor-segment',run)
    save_mask(p/'proposal_background.png',bg)
    (p/'proposal.json').write_text(json.dumps({'revision':rev,'request':request,'source_sha256':s['source_sha256'],'human_approved':False},ensure_ascii=False))

def apply(p,index):
    p=Path(p);s=state(p);proposal=json.loads((p/'proposal.json').read_text())
    if index not in range(3) or proposal['revision']!=current(p,s):raise ValueError('候选已过期，请重新修正')
    roi=roi_mask(SIZE,proposal['request']['roi']);rev=current(p,s)
    old=load_mask(p/f'revision_{rev}.png',SIZE)>0;new=load_mask(p/f'proposal_{index}.png',SIZE)>0
    if np.any(old[~roi]!=new[~roi]):raise ValueError('候选改变了框外像素，拒绝应用')
    number=time.time_ns();shutil.copyfile(p/f'proposal_{index}.png',p/f'revision_{number}.png');shutil.copyfile(p/'proposal_background.png',p/f'background_{number}.png')
    s['history']=s['history'][:s['cursor']+1]+[number];s['cursor']+=1;s['events'].append({'revision':number,'proposal':proposal,'candidate':index});persist(p,s)
    (p/'draft.json').write_text(json.dumps({'roi':proposal['request']['roi'],'strokes':[]}))
    (p/'proposal.json').unlink()

def move(p,step):
    s=state(p);current(p,s);s['cursor']=max(0,min(len(s['history'])-1,s['cursor']+step));persist(p,s)
    (Path(p)/'proposal.json').unlink(missing_ok=True)

def image_url(im):
    stream=io.BytesIO();im.save(stream,format='PNG');return 'data:image/png;base64,'+base64.b64encode(stream.getvalue()).decode()
def view(p,proposal=None):
    p=Path(p);s=state(p);mask=None
    if digest(p/'source.png')!=s['source_sha256'] or digest(p/'pearl_mask.png')!=s['pearl_sha256']:raise ValueError('原图或珍珠发生变化')
    if proposal is not None:
        if proposal not in range(3) or json.loads((p/'proposal.json').read_text())['revision']!=current(p,s):raise ValueError('候选已过期')
        mask=read_image(p/f'proposal_{proposal}.png','L')
    elif s['cursor']>=0:mask=read_image(p/f'revision_{current(p,s)}.png','L')
    return {'source':image_url(read_image(p/'source.png','RGB')),'mask':image_url(mask) if mask else None,'state':s,'draft':json.loads((p/'draft.json').read_text()) if (p/'draft.json').exists() else None}

def export(p):
    p=Path(p);s=state(p);rev=current(p,s);rgb=np.array(read_image(p/'source.png','RGB'));a=load_mask(p/f'revision_{rev}.png',SIZE)>0
    save_mask(p/'person_edit_support.png',a)
    overlay=rgb.copy();overlay[a]=(rgb[a]*.5+np.array([255,40,150])*.5).astype('uint8');Image.fromarray(overlay).save(p/'overlay.png')
    for name,color in [('black',0),('white',255)]:Image.fromarray(np.where(a[...,None],rgb,color)).save(p/f'{name}_background.png')
    for i,event in enumerate(s['events']):Image.fromarray(overlay).crop(tuple(event['proposal']['request']['roi'])).save(p/f'review_region_{i}.png')
    (p/'审核说明.txt').write_text('人物遮罩支持范围，未人工通过；黑白预览不是Alpha。珍珠2固定。所有SAM旧结果Reject。禁止Alpha、背景补全及最终打包。恢复时上传本包即可继续编辑。\n')
    out=p.parent/'FloatDeck-person-mask-edit-review.zip'
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        for f in p.iterdir():
            if f.is_file() and f.suffix in ('.png','.json','.txt'):z.write(f,f.name)
    return str(out)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('action');parser.add_argument('--work',required=True);parser.add_argument('--request',required=True);args=parser.parse_args();p=Path(args.work);r=json.loads(Path(args.request).read_text())
    if args.action=='init':initialize(p,r['archive'],r['project'])
    elif args.action=='select':select_initial(p,r['index'])
    elif args.action=='draft':
        if r.get('roi') is not None:roi_mask(SIZE,r['roi'])
        if r.get('strokes'):marks(SIZE,r['strokes'])
        (p/'draft.json').write_text(json.dumps(r))
    elif args.action=='propose':propose(p,r)
    elif args.action=='apply':apply(p,r['index'])
    elif args.action=='undo':move(p,-1)
    elif args.action=='redo':move(p,1)
    elif args.action=='export':print(json.dumps({'zip':export(p)}));return
    print(json.dumps(view(p,r.get('preview') if args.action=='preview' else None)))
if __name__=='__main__':main()
