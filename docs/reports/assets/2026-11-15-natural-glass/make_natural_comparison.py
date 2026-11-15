from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageChops
import json
root=Path(__file__).parent
moods=[('mist','雾灰'),('sand','暖砂'),('sage','鼠尾草'),('plum','暮紫')]
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
w,h,gap,margin=248,552,18,26
canvas=Image.new('RGB',(4*w+3*gap+2*margin,2*(h+46)+2*margin+48),'#ecefe9')
d=ImageDraw.Draw(canvas)
d.text((margin,15),'DSH · 四套自然配色 / 实际 Flutter 页面',font=font,fill='#26392e')
for row,mode in enumerate(['light','dark']):
 for col,(slug,label) in enumerate(moods):
  shot=Image.open(root/('natural-'+slug+'-'+mode+'.png')).convert('RGB')
  shot=shot.resize((w,h),Image.Resampling.LANCZOS)
  x=margin+col*(w+gap);y=64+row*(h+46)
  d.text((x,y),label+(' · 浅色' if mode=='light' else ' · 深色'),font=small,fill='#26392e')
  canvas.paste(shot,(x,y+29))
canvas.save(root/'natural-palettes.png')
a=Image.open(root/'natural-title.png').convert('RGB')
b=Image.open(root/'natural-title-after.png').convert('RGB')
diff=ImageChops.difference(a,b)
# The left drawer owns the long session title. Exclude any right-side input caret.
crop=diff.crop((0,90,290,600))
changed=sum(1 for pixel in crop.getdata() if max(pixel)>8)
(root/'natural-title-motion.json').write_text(json.dumps({'comparison':'same page; 3200 ms between screenshots','changedDrawerPixelsAbove8':changed,'titleRegion':[0,90,290,600],'source':'pixel difference; animation behavior separately covered by 15 widget tests'},ensure_ascii=False,indent=2),encoding='utf-8')
if changed==0: raise RuntimeError('No drawer title pixels changed')
print(json.dumps({'comparison':'natural-palettes.png','motionPixels':changed}))
