import sys
from PIL import Image, ImageChops

a = Image.open(sys.argv[1]).convert("RGB")
b = Image.open(sys.argv[2]).convert("RGB")
if a.size != b.size:
    print(f"size mismatch {a.size} vs {b.size}")
    sys.exit(1)

diff = ImageChops.difference(a, b)
gray = diff.convert("L")
# threshold
thr = 24
bbox = gray.point(lambda p: 255 if p > thr else 0).getbbox()
# count changed pixels
hist = gray.point(lambda p: 255 if p > thr else 0).histogram()
changed = hist[255]
total = a.size[0] * a.size[1]
print(f"changed_px={changed} ({100*changed/total:.3f}%)  bbox(L,T,R,B)={bbox}")
