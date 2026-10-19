"""Native-coordinate Colab mask editor; imports no model libraries into notebook kernel."""
import base64,io,json,uuid
from pathlib import Path
from PIL import Image
ALLOWED={'person_mask.png','pearl_mask.png','person_trimap.png','pearl_trimap.png','person_alpha.png','pearl_alpha.png',
         'person_protect.png','pearl_protect.png','person_unobserved.png','background_hole.png'}

def display_editor(work,filename):
    from google.colab import output
    from IPython.display import HTML,JSON,display
    if filename not in ALLOWED:raise ValueError('unsupported editable mask')
    work=Path(work);source=Image.open(work/'source.png').convert('RGB');size=source.size
    path=work/filename
    mask=Image.open(path).convert('L') if path.exists() else Image.new('L',size)
    if mask.size!=size:raise ValueError('mask size mismatch')
    def data(im):
        b=io.BytesIO();im.save(b,format='PNG');return 'data:image/png;base64,'+base64.b64encode(b.getvalue()).decode()
    ident='fd'+uuid.uuid4().hex
    def save(payload):
        raw=base64.b64decode(payload.split(',',1)[1],validate=True)
        if len(raw)>64*1024*1024:raise ValueError('edited mask too large')
        with Image.open(io.BytesIO(raw)) as im:
            if im.size!=size:raise ValueError('editor changed native pixel coordinates')
            im=im.convert('L')
            if 'trimap' in filename:
                import numpy as np
                a=np.array(im);im=Image.fromarray(np.where(a<64,0,np.where(a<192,128,255)).astype('uint8'),'L')
            im.save(path)
        return JSON({'saved':filename,'size':size,'review_invalidated':'rerun approve-masks after edits; old hashes cannot pass'})
    output.register_callback(ident,save)
    template=Path(__file__).with_name('editor.html').read_text()
    for key,value in {'ID':ident,'SOURCE':data(source),'MASK':data(mask),'NAME':filename}.items():template=template.replace('{{'+key+'}}',value)
    display(HTML(template))
