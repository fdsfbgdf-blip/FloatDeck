"""Exercise the shipped spatial shader on Mesa EGL ES3, without Android UI."""
import ctypes as C, re, json
from pathlib import Path
E=C.CDLL('libEGL.so.1'); G=C.CDLL('libGL.so.1')
I=C.c_int; U=C.c_uint; P=C.c_void_p; F=C.c_float

def fn(lib,name,restype,args):
    f=getattr(lib,name); f.restype=restype; f.argtypes=args; return f
getproc=fn(E,'eglGetProcAddress',P,[C.c_char_p])
getplatform=C.CFUNCTYPE(P,U,P,C.POINTER(I))(getproc(b'eglGetPlatformDisplayEXT'))
d=getplatform(0x31DD,None,None)
assert fn(E,'eglInitialize',U,[P,P,P])(d,None,None)
assert fn(E,'eglBindAPI',U,[U])(0x30A0)
attrs=(I*13)(0x3024,8,0x3023,8,0x3022,8,0x3033,1,0x3040,0x40,0x3038,0,0)
cfg=P(); n=I()
assert fn(E,'eglChooseConfig',U,[P,C.POINTER(I),C.POINTER(P),I,C.POINTER(I)])(d,attrs,C.byref(cfg),1,C.byref(n)) and n.value
surf=fn(E,'eglCreatePbufferSurface',P,[P,P,C.POINTER(I)])(d,cfg,(I*5)(0x3057,256,0x3056,256,0x3038))
ctx=fn(E,'eglCreateContext',P,[P,P,P,C.POINTER(I)])(d,cfg,None,(I*3)(0x3098,3,0x3038))
assert fn(E,'eglMakeCurrent',U,[P,P,P,P])(d,surf,surf,ctx)

def gl(name,ret,args,*vals): return fn(G,name,ret,args)(*vals)
shadertext=Path('app/src/main/kotlin/app/floatdeck/gl/Shaders.kt').read_text()
sh=[]
for name,typ in [('spatialVertex',0x8B31),('spatialFragment',0x8B30)]:
    body=re.search(r'val '+name+r'\s*=\s*"""(.*?)"""',shadertext,re.S)[1].strip().encode()
    s=gl('glCreateShader',U,[U],typ); text=C.c_char_p(body)
    gl('glShaderSource',None,[U,I,C.POINTER(C.c_char_p),P],s,1,C.byref(text),None)
    gl('glCompileShader',None,[U],s); ok=I()
    gl('glGetShaderiv',None,[U,U,C.POINTER(I)],s,0x8B81,C.byref(ok)); assert ok.value
    sh.append(s)
p=gl('glCreateProgram',U,[])
for s in sh: gl('glAttachShader',None,[U,U],p,s)
gl('glLinkProgram',None,[U],p); ok=I(); gl('glGetProgramiv',None,[U,U,C.POINTER(I)],p,0x8B82,C.byref(ok));assert ok.value
gl('glUseProgram',None,[U],p)
loc=lambda name:gl('glGetUniformLocation',I,[U,C.c_char_p],p,name.encode())
tex=(U*2)(); gl('glGenTextures',None,[I,C.POINTER(U)],2,tex)
# R encodes source X, G source Y. Three horizontal depth bands.
for index in range(2):
    data=bytearray()
    for y in range(256):
        z=[25,125,240][min(2,y*3//256)]
        for x in range(256): data.extend((x,y,0,255) if index==0 else (z,z,z,255))
    buf=(C.c_ubyte*len(data)).from_buffer(data)
    gl('glActiveTexture',None,[U],0x84C0+index);gl('glBindTexture',None,[U,U],0x0DE1,tex[index])
    for key,val in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:gl('glTexParameteri',None,[U,U,I],0x0DE1,key,val)
    gl('glTexImage2D',None,[U,I,I,I,I,I,U,U,P],0x0DE1,0,0x1908,256,256,0,0x1908,0x1401,C.cast(buf,P))
gl('glUniform1i',None,[I,I],loc('uImage'),0);gl('glUniform1i',None,[I,I],loc('uDepth'),1)
gl('glUniform1i',None,[I,I],loc('uClock'),2);gl('glUniform1i',None,[I,I],loc('uMask'),3)
gl('glUniform2f',None,[I,F,F],loc('uCover'),1,1)
gl('glUniform2f',None,[I,F,F],loc('uDepthRange'),0,1)
gl('glUniform2f',None,[I,F,F],loc('uTexel'),1/256,1/256)
gl('glUniform1f',None,[I,F],loc('uSurfaceTolerance'),.003)
gl('glViewport',None,[I,I,I,I],0,0,256,256)

def render(x,y):
    gl('glUniform2f',None,[I,F,F],loc('uMotion'),x,y)
    gl('glDrawArrays',None,[U,I,I],4,0,3)
    pixels=(C.c_ubyte*(256*256*4))()
    gl('glReadPixels',None,[I,I,I,I,U,U,P],0,0,256,256,0x1908,0x1401,C.cast(pixels,P))
    assert gl('glGetError',U,[])==0
    return pixels
base=render(0,0); results={}
for axis in [0,1]:
    for sign in [-1,1]:
        motion=[0,0];motion[axis]=sign*.04
        pixels=render(*motion); shifts=[]
        for y in [213,128,42]:
            off=(y*256+128)*4+axis
            shifts.append(int(pixels[off])-int(base[off]))
        assert abs(shifts[0]) < abs(shifts[1]) < abs(shifts[2]), shifts
        assert all(v*sign<0 for v in shifts), shifts
        results[f'axis{axis}_sign{sign}']=shifts
print(json.dumps({'backend':'Mesa EGL ES3 software, not Android hardware','shader_compile_link':'passed','four_direction_pixel_displacement_far_mid_near':results},indent=2))
# Combined preview: an opaque synthetic clock and registered foreground mask.
extra=(U*2)();gl('glGenTextures',None,[I,C.POINTER(U)],2,extra)
for index in range(2):
    raw=bytearray()
    for y in range(256):
        for x in range(256):
            raw.extend((200,30,80,255) if index==0 else ((255,255,255,255) if y>=171 else (0,0,0,255)))
    buffer=(C.c_ubyte*len(raw)).from_buffer(raw)
    gl('glActiveTexture',None,[U],0x84C2+index);gl('glBindTexture',None,[U,U],0x0DE1,extra[index])
    for key,val in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:gl('glTexParameteri',None,[U,U,I],0x0DE1,key,val)
    gl('glTexImage2D',None,[U,I,I,I,I,I,U,U,P],0x0DE1,0,0x1908,256,256,0,0x1908,0x1401,C.cast(buffer,P))
checks=0
for motion in [(-.04,0),(.04,0),(0,-.04),(0,.04)]:
    gl('glUniform1i',None,[I,I],loc('uClockEnabled'),0)
    plain=render(*motion)
    gl('glUniform1i',None,[I,I],loc('uClockEnabled'),1)
    combined=render(*motion)
    for y in [213,128,42]:
        offset=(y*256+128)*4
        expected=list(plain[offset:offset+3]) if plain[offset+1]>=171 else [200,30,80]
        assert all(abs(combined[offset+i]-expected[i])<=1 for i in range(3)), (motion,y,expected,list(combined[offset:offset+3]))
        checks+=1
print(json.dumps({'combined_clock_occlusion_samples':checks,'result':'passed; foreground and image use identical displaced UV'}))
