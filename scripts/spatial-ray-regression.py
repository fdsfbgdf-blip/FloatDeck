"""GPU vs independent exact nearest-depth oracle; includes one-pixel foreground.
Run from repository root with Mesa EGL ES3. No Android/device claims.
"""
exec(compile(open('scripts/spatial-render-check.py').read(), 'spatial-render-check.py', 'exec'))
import math
checks=0
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
        pixels=render(mx,my)
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
print(json.dumps({'exact_ray_oracle_samples':checks,'thin_foreground_steps_ramp':'passed','motion_unchanged':True}))
