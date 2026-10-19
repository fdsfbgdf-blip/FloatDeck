"""GPU vs independent exact nearest-depth oracle; includes one-pixel foreground.
Run from repository root with Mesa EGL ES3. No Android/device claims.
"""
exec(compile(open('scripts/spatial-render-check.py').read(), 'spatial-render-check.py', 'exec'))
import math
# Photo deliberately has twice the depth resolution; channels encode source UV.
photo=bytearray(v for y in range(512) for x in range(512) for v in (x//2,y//2,0,255))
photo_buf=(C.c_ubyte*len(photo)).from_buffer(photo)
gl('glActiveTexture',None,[U],0x84C0)
gl('glBindTexture',None,[U,U],0x0DE1,tex[0])
gl('glTexImage2D',None,[U,I,I,I,I,I,U,U,P],0x0DE1,0,0x1908,512,512,0,0x1908,0x1401,C.cast(photo_buf,P))
checks=0
color_checks=0
for fixture in ['thin', 'steps', 'ramp']:
    depths=[]
    for y in range(256):
        for x in range(256):
            depths.append((240 if x%23==0 or y%29==0 else 25) if fixture=='thin' else
                          ([25,125,240][(x//17+y//19)%3] if fixture=='steps' else (x*3+y*5)%256))
    raw=bytearray(v for z in depths for v in (z,z,z,255))
    buf=(C.c_ubyte*len(raw)).from_buffer(raw)
    gl('glActiveTexture',None,[U],0x84C1)
    gl('glBindTexture',None,[U,U],0x0DE1,tex[1])
    gl('glTexImage2D',None,[U,I,I,I,I,I,U,U,P],0x0DE1,0,0x1908,256,256,0,0x1908,0x1401,C.cast(buf,P))
    gl('glUniform1i',None,[I,I],loc('uClockEnabled'),0)
    gl('glUniform1i',None,[I,I],loc('uMeasureFallback'),1)
    for mx,my in [(.12,.0765),(-.12,-.0765),(.12,-.0765),(-.12,.0765),(0,0)]:
        gl('glUniform1i',None,[I,I],loc('uMeasureFallback'),1)
        pixels=render(mx,my)
        gl('glUniform1i',None,[I,I],loc('uMeasureFallback'),0)
        colors=render(mx,my)
        for y in range(8,248,7):
            for x in range(8,248,7):
                bx=(x+.5)/256; by=1-(y+.5)/256
                hits=[]
                for zbyte in set(depths):
                    z=zbyte/255; qx=bx-mx*z; qy=by-my*z
                    if 1/256<=qx<=255/256 and 1/256<=qy<=255/256:
                        if depths[int(qy*256)*256+int(qx*256)]==zbyte: hits.append(z)
                actual=pixels[(y*256+x)*4]==0
                assert actual==bool(hits), (fixture,mx,my,x,y,actual,hits)
                checks+=1
                if hits:
                    z=max(hits)  # nearest visible surface, not any valid root
                    expected=[(bx-mx*z)*256-.5,(by-my*z)*256-.5]
                    actual_color=colors[(y*256+x)*4:(y*256+x)*4+2]
                    assert all(abs(actual_color[i]-expected[i])<=2 for i in range(2)), (fixture,expected,list(actual_color))
                    color_checks+=1
print(json.dumps({'exact_ray_oracle_samples':checks,'thin_foreground_steps_ramp':'passed','motion_unchanged':True,'first_hit_and_mismatched_resolution_color_checks':color_checks}))
# Normal-pass fallback must not extrude a foreground silhouette into disocclusion.
for index in range(2):
    raw=bytearray()
    for y in range(256):
        for x in range(256):
            near=(x-128)**2+(y-128)**2<40**2
            raw.extend(((255,0,0,255) if near else (0,255,0,255)) if index==0 else
                       ((255,255,255,255) if near else (0,0,0,255)))
    buf=(C.c_ubyte*len(raw)).from_buffer(raw)
    gl('glActiveTexture',None,[U],0x84C0+index)
    gl('glBindTexture',None,[U,U],0x0DE1,tex[index])
    gl('glTexImage2D',None,[U,I,I,I,I,I,U,U,P],0x0DE1,0,0x1908,256,256,0,0x1908,0x1401,C.cast(buf,P))
def area(pixels):
    return sum(pixels[i]>180 and pixels[i+1]<80 for i in range(0,len(pixels),4))
base_area=area(render(0,0))
for motion in [(.12,0),(-.12,0),(0,.12),(0,-.12),(.096,.0612)]:
    actual_area=area(render(*motion))
    assert abs(actual_area-base_area)<base_area*.02, (motion,base_area,actual_area)
print(json.dumps({'fallback_silhouette_area_checks':5,'result':'passed, under 2 percent change without reducing motion'}))
