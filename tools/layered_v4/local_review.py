"""Native-coordinate local SAM hypotheses. Every output requires human review."""
import copy,json,zipfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from .core import read_image,load_mask,save_mask,digest
from .segmentation import PERSON_BACKGROUND_PROBES,PERSON_COVERAGE_PROBES,pixels

ZIP_NAME='FloatDeck-person-local-rereview.zip'
NEG=[[.10,.50],[.25,.31],[.06,.63],[.96,.65],[.50,.81]]
PROFILES=[
 {'id':'core','label':'人物核心（重新核对）','box':[.24,.28,.68,.53],'positive':[[.49,.36],[.56,.32],[.30,.445],[.50,.437]],'negative':NEG},
 {'id':'white_sleeve','label':'白纱大衣袖','box':[.57,.435,1.,.55],'positive':PERSON_COVERAGE_PROBES['white_sleeve'],'negative':NEG+[[.96,.59]]},
 {'id':'chest_waist','label':'胸腰衣料','box':[.36,.41,.64,.625],'positive':PERSON_COVERAGE_PROBES['chest_waist'],'negative':NEG+[[.33,.55]]},
 {'id':'large_skirt','label':'左下裙摆（环境交界待审核）','box':[.15,.55,.66,.75],'positive':PERSON_COVERAGE_PROBES['large_skirt'],'negative':NEG+[[.14,.70],[.64,.74]]},
 {'id':'leg_foot','label':'右腿与脚','box':[.63,.50,.87,.665],'positive':PERSON_COVERAGE_PROBES['leg_foot'],'negative':NEG+[[.88,.64]]},
 {'id':'hair_tips','label':'右侧发丝','box':[.63,.30,.98,.445],'positive':PERSON_COVERAGE_PROBES['hair_tips'],'negative':NEG+[[.95,.29]]}]

def rectangle(size,box):
    w,h=size
    if len(box)!=4 or not 0<=box[0]<box[2]<=1 or not 0<=box[1]<box[3]<=1:raise ValueError('invalid normalized ROI')
    a=np.zeros((h,w),bool);x0,y0,x1,y1=[int(round(v*(w if i%2==0 else h))) for i,v in enumerate(box)]
    a[y0:y1,x0:x1]=True;return a

def constrain(raw,size,profile,pearl):
    a=np.asarray(raw)>0
    if a.shape!=(size[1],size[0]) or np.asarray(pearl).shape!=a.shape:raise ValueError('native mask dimensions differ')
    exclusion=np.asarray(pearl)>0
    for box in PERSON_BACKGROUND_PROBES.values():exclusion=exclusion|rectangle(size,box)
    bounded=a&rectangle(size,profile['box'])&~exclusion
    return bounded,{'raw_pixels':int(a.sum()),'outside_roi_pixels':int((a&~rectangle(size,profile['box'])).sum()),
        'excluded_background_pixels':int((a&exclusion).sum()),'retained_pixels':int(bounded.sum()),
        'raw_background_occupancy':{k:float(a[rectangle(size,b)].mean()) if rectangle(size,b).any() else 0. for k,b in PERSON_BACKGROUND_PROBES.items()},
        'selected':None,'human_approved':False,'semantic_quality_validated':False,'passed':False}

def preview(p,name,mask,label):
    src=read_image(p/'source.png','RGB');rgb=np.array(src);a=np.asarray(mask)>0
    overlay=rgb.copy();overlay[a]=(rgb[a]*.5+np.array([255,40,150])*.5).astype('uint8')
    font=ImageFont.truetype(str(Path(__file__).parent/'fonts/ReviewLabels.otf'),19)
    images=[src,Image.fromarray(overlay),Image.fromarray((a*255).astype('uint8')).convert('RGB'),Image.fromarray(np.where(a[...,None],rgb,0)),Image.fromarray(np.where(a[...,None],rgb,255))]
    canvas=Image.new('RGB',(1500,650),'#222222');d=ImageDraw.Draw(canvas)
    for i,(im,title) in enumerate(zip(images,['原图','叠加','二值支持范围','黑背景（非Alpha）','白背景（非Alpha）'])):
        im.thumbnail((300,590));canvas.paste(im,(i*300,60));d.text((i*300+3,35),title,font=font,fill='white')
    d.text((5,5),label+' · 未人工批准',font=font,fill='white');canvas.save(p/'local-previews'/f'{name}.jpg',quality=92)

def execute_local(work,project,archive):
    from .person_review import import_review
    from .models import segment
    from .cli import DEFAULT
    p=Path(work);identity=import_review(archive,p,project);size=read_image(p/'source.png','RGB').size
    # Candidate 2 is reference evidence only. Never union candidate 5 or restore its approval.
    with zipfile.ZipFile(archive) as z:
        if 'person_candidate_2.png' not in z.namelist():raise ValueError('需要包含人物候选2的完整覆盖复审ZIP；未启动SAM')
        (p/'reference_person_candidate_2.png').write_bytes(z.read('person_candidate_2.png'))
    reference=load_mask(p/'reference_person_candidate_2.png',size);pearl=load_mask(p/'pearl_mask.png',size)
    cfg=copy.deepcopy(DEFAULT);cfg['sam_prompts']['person']=PROFILES[0];cfg['boxes']['person']=PROFILES[0]['box']
    cfg.update(CONTINUE_AFTER_SEGMENTATION=False,local_profiles=PROFILES,all_previous_person_candidates_rejected=True)
    (p/'config.json').write_text(json.dumps(cfg,ensure_ascii=False,indent=2))
    segment(p,cfg,targets=('person',),prompt_sets=PROFILES)
    (p/'local-previews').mkdir();(p/'local-masks').mkdir();checks=[];bounded=[]
    preview(p,'reference2',reference,'旧候选2：仅参考，Reject')
    for j,profile in enumerate(PROFILES):
        prompt_image=read_image(p/'source.png','RGB');draw=ImageDraw.Draw(prompt_image)
        box,pts,labels=pixels(profile,size);draw.rectangle(box,outline='yellow',width=3)
        for (x,y),label in zip(pts,labels):draw.ellipse((x-5,y-5,x+5,y+5),fill='lime' if label else 'red')
        prompt_image.save(p/'local-previews'/f"{profile['id']}_prompts.png")
        group=[]
        for i in range(3):
            name=f"{profile['id']}_candidate_{i}";raw=load_mask(p/f'person_candidate_{j*3+i}.png',size)
            mask,check=constrain(raw,size,profile,pearl);group.append(mask)
            save_mask(p/'local-masks'/f'{name}.png',mask)
            save_mask(p/'local-masks'/f'{name}_removed.png',(raw>0)&~mask)
            preview(p,name,mask,f"{profile['label']} · 候选{i}");preview(p,name+'_raw',raw,f"{profile['label']} · 原始候选{i}")
            box,points,labels=pixels(profile,size)
            check.update(name=name,profile=profile,pixel_box=box,pixel_points=points,point_labels=labels,
                         reference2_retained_pixels=int((mask&(reference>0)).sum()),requires_boundary_review=True)
            checks.append(check)
        bounded.append(group)
    # Three labeled hypotheses, no ranking or automatic choice; all parts remain individually auditable.
    for i in range(3):
        merged=np.logical_or.reduce([group[i] for group in bounded]);save_mask(p/'local-masks'/f'composition_{i}.png',merged)
        preview(p,f'composition_{i}',merged,f'组合假设{i}：各部件同编号，非最终人物')
        save_mask(p/'local-masks'/f'composition_{i}_reference2_missing.png',(reference>0)&~merged)
        preview(p,f'composition_{i}_reference2_missing',(reference>0)&~merged,'旧候选2未保留区域：核对脸发手身体')
    for file,key in [('source.png','source_sha256'),('source_depth.png','depth_sha256'),('pearl_mask.png','pearl_sha256')]:
        if digest(p/file)!=identity[key]:raise RuntimeError('源素材或固定珍珠发生变化')
    result={'complete':True,'candidates':checks,'selected':None,'human_approved':False,'passed':False,
      'candidate_generation_complete':True,'all_previous_person_candidates_rejected':True,'pearl_candidate':2,
      'composition_rule':'matching local index only; inspection hypotheses, not selected','CONTINUE_AFTER_SEGMENTATION':False}
    (p/'local-checks.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    (p/'局部分割审核说明.md').write_text('# 局部分割待人工审核\n\n核心及白纱、胸腰、裙摆、腿脚、右发分别3候选。保留原始SAM输出及ROI/背景约束后的支持范围。所有人物旧审核Reject，新结果未批准。\n\n先核对人物核心，再逐部件检查原始与受限叠加、黑白背景；removed图显示约束删除区域，reference2_missing显示旧2未保留区域，防止掩盖脸发手身体丢失。组合只是同编号部件的展示假设，无IoU排名或自动选择。\n\n二值白纱仅是未来Alpha支持范围；浅色不构成删除理由。裙摆与水流反射的语义无法由点覆盖率确认，必须人工审核。蓝环等小ROI的强制排除不代表背景误选已解决；检查raw_background_occupancy与删除图。ROI也可能截断真实人物边缘，需审核后再调整。\n')
    return result

