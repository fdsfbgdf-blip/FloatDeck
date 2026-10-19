"""Strict offline ZIP validation. --allow-draft never means visual acceptance."""
import argparse,hashlib,io,json,zipfile
from pathlib import PurePosixPath
import numpy as np
from PIL import Image
from .core import SCHEMA,MAX_BYTES,MAX_PIXELS,compose

REQUIRED={'source.png','source_depth.png','background.png','background_hole.png','person_rgba.png','pearl_rgba.png',
 'person_alpha.png','pearl_alpha.png','person_protect.png','pearl_protect.png','person_unobserved.png','person_depth.png','pearl_depth.png',
 'quality.json','review.json','background_record.json','input.json'}
def validate(path,allow_draft=False):
    with zipfile.ZipFile(path) as z:
        names=z.namelist()
        if len(names)!=len(set(names)) or len(names)>128:raise ValueError('duplicate/excess entries')
        for e in z.infolist():
            p=PurePosixPath(e.filename)
            if p.is_absolute() or '..' in p.parts or '\\' in e.filename or e.flag_bits&1 or (e.external_attr>>16)&0o170000==0o120000:raise ValueError('unsafe entry')
        if sum(e.file_size for e in z.infolist())>MAX_BYTES:raise ValueError('archive too large')
        if z.getinfo('manifest.json').file_size>65536:raise ValueError('manifest too large')
        m=json.loads(z.read('manifest.json'))
        if m.get('format')!=SCHEMA or m.get('version')!=2 or not m.get('experimental') or not m.get('not_for_android_v1'):raise ValueError('incorrect experimental schema')
        if not allow_draft and m.get('quality_status')!='human-reviewed-experimental':raise ValueError('UNREVIEWED-DRAFT; inspect in Colab')
        size=(m['width'],m['height'])
        if not all(isinstance(x,int) and x>0 for x in size) or size[0]*size[1]>MAX_PIXELS:raise ValueError('invalid dimensions')
        if m.get('alpha')!='straight-unassociated' or m.get('color_space')!='sRGB' or m.get('orientation')!='upright':raise ValueError('unsupported channel/color/coordinate semantics')
        assets=m['assets']
        if not REQUIRED.issubset(assets) or set(names)!=(set(assets)|{'manifest.json'}):raise ValueError('missing or unlisted files')
        if m['background']!={'image':'background.png','hole_mask':'background_hole.png','z_order':0,'depth':'unknown-behind-occluders'}:raise ValueError('invalid background/layer order')
        if [(l['id'],l['image'],l['alpha_mask'],l['depth'],l['z_order']) for l in m['layers']]!=[(n,n+'_rgba.png',n+'_alpha.png',n+'_depth.png',i+1) for i,n in enumerate(['person','pearl'])]:raise ValueError('invalid layer references')
        arrays={}
        for n,desc in assets.items():
            raw=z.read(n)
            if len(raw)!=desc['bytes'] or hashlib.sha256(raw).hexdigest()!=desc['sha256']:raise ValueError('hash/size mismatch: '+n)
            if n.endswith('.png'):
                with Image.open(io.BytesIO(raw)) as im:
                    if n.startswith('previews/'):
                        if im.width*im.height>MAX_PIXELS:raise ValueError('oversized preview')
                        continue
                    expected='RGBA' if n.endswith('_rgba.png') else ('RGB' if n in ['source.png','background.png'] else 'L')
                    if im.mode!=expected or im.size!=size:raise ValueError('size/channel mismatch: '+n)
                    arrays[n]=np.array(im)
        for n in ['person','pearl']:
            a=arrays[n+'_rgba.png'][...,3]
            if not np.array_equal(a,arrays[n+'_alpha.png']):raise ValueError('PNG alpha/mask mismatch')
            if not np.any(a>0) or not np.any(a==0):raise ValueError('empty/black-background-fake layer')
            protected=arrays[n+'_protect.png']>127
            if not np.all(a[protected]==255) or not np.array_equal(arrays[n+'_rgba.png'][protected,:3],arrays['source.png'][protected]):raise ValueError('protected source detail changed')
        pa=arrays['person_alpha.png'];la=arrays['pearl_alpha.png'];hole=arrays['background_hole.png']>0
        if np.any((pa>0)&(la>0)):raise ValueError('pearl contamination in person layer')
        if np.any(((pa>0)|(la>0))&~hole):raise ValueError('foreground support outside inpaint mask')
        if not np.array_equal(arrays['source.png'][~hole],arrays['background.png'][~hole]):raise ValueError('AI changed visible original pixels')
        identity=json.loads(z.read('input.json'))
        if identity['source_sha256']!=assets['source.png']['sha256'] or identity['depth_sha256']!=assets['source_depth.png']['sha256']:raise ValueError('canonical input changed')
        review=json.loads(z.read('review.json'));q=json.loads(z.read('quality.json'));record=json.loads(z.read('background_record.json'))
        if set(review.get('mask_hashes',{}))!={'source.png','source_depth.png','person_alpha.png','pearl_alpha.png','person_protect.png','pearl_protect.png','person_unobserved.png','background_hole.png'} or any(assets[n]['sha256']!=h for n,h in review.get('mask_hashes',{}).items()):raise ValueError('stale mask approval')
        if record['mask_sha256']!=assets['background_hole.png']['sha256'] or record['background_sha256']!=assets['background.png']['sha256']:raise ValueError('stale inpaint provenance')
        actual=compose(arrays['background.png'],arrays['person_rgba.png'],arrays['pearl_rgba.png'])
        e=abs(actual-arrays['source.png'])
        bad=float((e.max(axis=2)>8).mean())
        if abs(q['zero_bad_fraction']-bad)>1e-8:raise ValueError('forged reconstruction score')
        if not allow_draft:
            checks=['segmentation','hair','face_fingers','veil_alpha','skirt','pearl','background_no_ghosts','shift_previews','alpha_on_black_white']
            if not all(review.get('accepted',{}).get(k) is True for k in checks):raise ValueError('missing visual review')
            if record.get('provider')!='sdxl-inpaint-global-and-native-tiles' or record.get('executed') is not True:raise ValueError('actual model background required')
            if 'inpaint-execution.json' not in assets or not json.loads(z.read('inpaint-execution.json')).get('succeeded'):raise ValueError('missing successful model execution')
            if review.get('output_hashes')!={n:assets[n]['sha256'] for n in ['background.png','person_rgba.png','pearl_rgba.png']}:raise ValueError('stale output approval')
            if bad>.02:raise ValueError('reconstruction exceeds 2% bad-pixel budget; not accepted')
            if np.any(arrays['person_unobserved.png']) and not review.get('accepted',{}).get('occluded_person_known_limit'):raise ValueError('hidden person texture unknown')
        return {'structure_passed':True,'human_approval_recorded':m['quality_status']=='human-reviewed-experimental',
                'independent_visual_validation':False,'size':size,'zero_mae':float(e.mean()),'zero_bad_fraction':bad,
                'background_pixels_preserved':int((~hole).sum()),'experimental_only':True}
def main():
    p=argparse.ArgumentParser();p.add_argument('zip');p.add_argument('--allow-draft',action='store_true');a=p.parse_args()
    print(json.dumps(validate(a.zip,a.allow_draft),indent=2))
if __name__=='__main__':main()
