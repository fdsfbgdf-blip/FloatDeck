"""Normal-color image regression + explicit-mask attribution and GPU timing.
Synthetic fixtures isolate mechanisms; they are NOT visual acceptance of user art.
Optional --package accepts the user's V1 ZIP for a same-input contact sheet.
"""
import os, sys, subprocess, argparse, time, csv, json, zipfile, io
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
parser=argparse.ArgumentParser();parser.add_argument('--algorithm',choices=['v1','v2','v3']);parser.add_argument('--package')
args=parser.parse_args(); output=Path('work/v3-gpu');output.mkdir(parents=True,exist_ok=True)
if not args.algorithm:
    for algorithm in ['v1','v2','v3']:
        cmd=[sys.executable,__file__,'--algorithm',algorithm]
        if args.package:cmd+=['--package',args.package]
        subprocess.run(cmd,check=True)
    rows=[json.loads((output/f'{a}.json').read_text()) for a in ['v1','v2','v3']]
    (output/'metrics.json').write_text(json.dumps(rows,indent=2))
    for fixture in ['identity','identity-mismatched','quantized-slope','discontinuity']+(['actual-package'] if args.package else []):
        sheet=Image.new('RGB',(360*3,680),(30,30,30));draw=ImageDraw.Draw(sheet)
        for i,a in enumerate(['v1','v2','v3']):
            sheet.paste(Image.open(output/f'{a}-{fixture}-normal.png'),(i*360,40));draw.text((i*360+8,10),a.upper(),fill='white')
        sheet.save(output/f'{fixture}-comparison.png')
    print(json.dumps({'synthetic_only':not bool(args.package),'results':rows},indent=2));sys.exit()
os.environ['FLOATDECK_ALGORITHM']=args.algorithm
# Reuse the exact ES3 setup/compiler from the existing GPU harness.
setup=Path('scripts/spatial-render-check.py').read_text().split('base=render(0,0)')[0]
setup=setup.replace('(0x3057,256,0x3056,256,0x3038)','(0x3057,720,0x3056,1280,0x3038)')
exec(compile(setup,'spatial-render-check.py','exec'))
w,h=360,640
report={'algorithm':args.algorithm,'backend':gl('glGetString',C.c_char_p,[U],0x1F01).decode(),'fixtures':[]}
def upload(index,array):
    array=np.ascontiguousarray(array,dtype=np.uint8)
    gl('glActiveTexture',None,[U],0x84C0+index);gl('glBindTexture',None,[U,U],0x0DE1,tex[index])
    gl('glTexImage2D',None,[U,I,I,I,I,I,U,U,P],0x0DE1,0,0x1908,array.shape[1],array.shape[0],0,0x1908,0x1401,C.c_void_p(array.ctypes.data))
    if index==1:gl('glUniform2f',None,[I,F,F],loc('uTexel'),1/array.shape[1],1/array.shape[0])
def rgba(rgb):
    return np.concatenate([rgb,np.full((*rgb.shape[:2],1),255,dtype=np.uint8)],axis=2).astype(np.uint8)
def sample(array,u,v):
    sh,sw=array.shape[:2];px=np.clip(u*sw-.5,0,sw-1);py=np.clip(v*sh-.5,0,sh-1)
    x=np.floor(px).astype(int);y=np.floor(py).astype(int);f=(px-x)[...,None];g=(py-y)[...,None]
    return ((array[y,x]*(1-f)+array[y,np.minimum(x+1,sw-1)]*f)*(1-g)+
        (array[np.minimum(y+1,sh-1),x]*(1-f)+array[np.minimum(y+1,sh-1),np.minimum(x+1,sw-1)]*f)*g)
def draw(mx,my,mask=False):
    gl('glViewport',None,[I,I,I,I],0,0,w,h)
    gl('glUniform2f',None,[I,F,F],loc('uMotion'),mx,my)
    gl('glUniform2f',None,[I,F,F],loc('uCamera'),mx*.3,my*.3)
    gl('glUniform1i',None,[I,I],loc('uMeasureFallback'),int(mask))
    gl('glDrawArrays',None,[U,I,I],4,0,3)
    pixels=np.empty((h,w,4),dtype=np.uint8)
    gl('glReadPixels',None,[I,I,I,I,U,U,P],0,0,w,h,0x1908,0x1401,C.c_void_p(pixels.ctypes.data))
    assert gl('glGetError',U,[])==0
    return pixels[::-1].copy()
u,v=np.meshgrid((np.arange(w)+.5)/w,(np.arange(h)+.5)/h)
gl('glUniform2f',None,[I,F,F],loc('uCover'),.90,.90)
# A detailed color texture, independently sized from depth, not a depth visualization.
y,x=np.mgrid[:2048,:1146]
photo=rgba(np.stack([(x*3+y*5)%256,(x//7*31+y//11*17)%256,(x//3+y//5)*23%256],axis=2))
ym,xm=np.mgrid[:2048,:1536]
mismatched_photo=rgba(np.stack([(xm*3+ym*5)%256,(xm//7*31+ym//11*17)%256,(xm//3+ym//5)*23%256],axis=2))
yd,xd=np.mgrid[:2048,:1146]
for name in ['identity','identity-mismatched','quantized-slope','discontinuity']:
    if name.startswith('identity'):
        depth=np.where((xd//7+yd//9)%2,230,25).astype(np.uint8);mx=my=0.0;image=mismatched_photo if name.endswith("mismatched") else photo
    elif name=='quantized-slope':
        depth=(yd*255//2047).astype(np.uint8);mx,my=.096,.0612;image=photo
    else:
        near=(xd>400)&(xd<740)&(yd>650)&(yd<1450)
        depth=np.where(near,255,0).astype(np.uint8)
        image=rgba(np.where(near[...,None],np.array([245,15,10]),np.array([15,190,55])).astype(np.uint8));mx,my=.096,.0612
    upload(0,image);upload(1,rgba(np.repeat(depth[...,None],3,axis=2)))
    normal=draw(mx,my);mask=draw(mx,my,True)[...,0]>128
    Image.fromarray(normal).save(output/f'{args.algorithm}-{name}-normal.png')
    Image.fromarray((mask*255).astype(np.uint8)).save(output/f'{args.algorithm}-{name}-mask.png')
    Image.fromarray(image).save(output/f'{name}-source.png')
    item={'fixture':name,'photo_size':[image.shape[1],image.shape[0]],'depth_size':[1146,2048],'failed_pixels':int(mask.sum()),'total':w*h}
    if name.startswith('identity') or name=='quantized-slope':
        bx=(u-.5)*.9+.5-mx*.3;by=(v-.5)*.9+.5-my*.3
        if name.startswith('identity'):qx,qy=bx,by
        else:
            # Independent monotone scalar root bisection of actual uploaded depth.
            lo=np.zeros_like(u);hi=np.ones_like(u)
            darray=depth[...,None]/255.
            for _ in range(36):
                z=(lo+hi)*.5;dz=sample(darray,bx-mx*z,by-my*z)[...,0]
                positive=dz>z;lo=np.where(positive,z,lo);hi=np.where(positive,hi,z)
            z=(lo+hi)*.5;qx=bx-mx*z;qy=by-my*z
        expected=sample(image[...,:3].astype(float),qx,qy)
        error=np.max(np.abs(normal[...,:3].astype(float)-expected),axis=2)
        valid=(qx>=1/1146)&(qx<=1-1/1146)&(qy>=1/2048)&(qy<=1-1/2048)
        item.update(normal_bad_pixels=int(((error>3)&valid).sum()),hit_bad_pixels=int(((error>3)&~mask).sum()),
                    fallback_bad_pixels=int(((error>3)&mask&valid).sum()),unknown_source_pixels=int((~valid).sum()),max_error_valid_source=float(error[valid].max()))
        Image.fromarray(np.clip(np.abs(normal[...,:3]-expected)*4,0,255).astype(np.uint8)).save(output/f'{args.algorithm}-{name}-color-error.png')
        if args.algorithm=='v3':
            assert np.array_equal(mask,~valid),(name,'mask must match actual source availability, not create internal cracks',int((mask^~valid).sum()))
            assert error[valid].max()<=3,(name,item)
    else:
        foreground=(normal[...,0]>180)&(normal[...,1]<80)
        item['foreground_color_in_failed_pixels']=int((foreground&mask).sum())
        if args.algorithm=='v3':
            assert mask.any(),'fixture must retain actual missing-background failures'
            assert not (foreground&mask).any(),'unknown background may not contain foreground copies'
            assert np.all(normal[mask,0]==normal[mask,1]) and np.all(normal[mask,1]==normal[mask,2])
    report['fixtures'].append(item)
# Timings at 720x1280 on the same uploaded full-resolution fixture, same motion.
gl('glUniform1i',None,[I,I],loc('uMeasureFallback'),0)
gl('glViewport',None,[I,I,I,I],0,0,720,1280)
for _ in range(3):gl('glDrawArrays',None,[U,I,I],4,0,3)
gl('glFinish',None,[])
exts=gl('glGetString',C.c_char_p,[U],0x1F03).decode();timer='GL_EXT_disjoint_timer_query' in exts
query=U();gpu=[];sync=[]
if timer:
    gl('glGenQueries',None,[I,C.POINTER(U)],1,C.byref(query))
    addr=getproc(b'glGetQueryObjectui64vEXT')
    get64=C.CFUNCTYPE(None,U,U,C.POINTER(C.c_uint64))(addr) if addr else None
for _ in range(7):
    start=time.perf_counter_ns()
    if timer:gl('glBeginQuery',None,[U,U],0x88BF,query.value)
    gl('glDrawArrays',None,[U,I,I],4,0,3)
    if timer:gl('glEndQuery',None,[U],0x88BF)
    gl('glFinish',None,[]);sync.append((time.perf_counter_ns()-start)/1e6)
    if timer and get64:
        ns=C.c_uint64();disjoint=I();get64(query.value,0x8866,C.byref(ns));gl('glGetIntegerv',None,[U,C.POINTER(I)],0x8FBB,C.byref(disjoint))
        if not disjoint.value:gpu.append(ns.value/1e6)
    assert gl('glGetError',U,[])==0
report['performance']={'viewport':[720,1280],'warmup':3,'samples':7,'sync_median_ms':float(np.median(sync)),
    'sync_p95_ms':float(np.percentile(sync,95)),'gpu_timer_median_ms':float(np.median(gpu)) if len(gpu)==7 else None,
    'gpu_timer_valid':len(gpu)==7,'note':'software Mesa, not mobile GPU; explicit fences, not sustained FPS'}
# A supplied actual package produces evidence only; no automated visual acceptance.
if args.package:
    with zipfile.ZipFile(args.package) as zf:
        manifest=json.loads(zf.read('manifest.json'))
        # V1 field names are read from the package, never guessed from screenshot.
        image_path=manifest['assets']['image'];depth_path=manifest['assets']['depth']
        actual=Image.open(io.BytesIO(zf.read(image_path))).convert('RGBA');actual.thumbnail((2048,2048))
        actual_depth=Image.open(io.BytesIO(zf.read(depth_path))).convert('RGBA').resize(actual.size,Image.Resampling.NEAREST)
        upload(0,np.array(actual));upload(1,np.array(actual_depth))
        levels=np.array(actual_depth)[...,0]
        gl('glUniform2f',None,[I,F,F],loc('uDepthRange'),int(levels.min())/255,int(levels.max())/255)
        Image.fromarray(draw(.096,.0612)).save(output/f'{args.algorithm}-actual-package-normal.png')
        report['actual_package']='captured, visual acceptance requires real-phone comparison; manifest not applied as a preset'
(output/f'{args.algorithm}.json').write_text(json.dumps(report,indent=2))
