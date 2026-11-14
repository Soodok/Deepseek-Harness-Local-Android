import sys
from PIL import Image
path = sys.argv[1]
im = Image.open(path).convert('RGB')
px = im.getpixel((540, 2100))
open_ = not (px[2] > 200 and px[0] < 160)
print('open' if open_ else 'closed', px)