"""Samsung TV (Tizen) package: www/index.html + config.xml + icon -> dist/tv-streamy.wgt
The .wgt is not signed: the installer (Tizen Studio, Apps2Samsung...) signs it with your Samsung certificate.
Run after tools/build.py.   Usage: python3 tools/build_tizen.py [version x.y.z]"""
import os, sys, zipfile
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
version = sys.argv[1] if len(sys.argv) > 1 else '1.0.0'
html = open(os.path.join(root, 'www', 'index.html'), encoding='utf-8').read()
# Samsung's web APIs (AVPlay player...) are provided by the TV
html = html.replace('<head>', '<head>\n<script src="$WEBAPIS/webapis/webapis.js"></script>', 1)
cfg = open(os.path.join(root, 'tizen', 'config.xml'), encoding='utf-8').read().replace('@@VERSION@@', version)
os.makedirs(os.path.join(root, 'dist'), exist_ok=True)
out = os.path.join(root, 'dist', 'tv-streamy.wgt')
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('config.xml', cfg)
    z.writestr('index.html', html)
    z.write(os.path.join(root, 'tizen', 'icon.png'), 'icon.png')
print(out, os.path.getsize(out))

# Petit outil à installer une fois sur la TV : affiche l'ID de chaque application installée
ids = os.path.join(root, 'tizen-ids')
out2 = os.path.join(root, 'dist', 'ids-apps.wgt')
with zipfile.ZipFile(out2, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('config.xml', open(os.path.join(ids, 'config.xml'), encoding='utf-8').read().replace('@@VERSION@@', version))
    z.write(os.path.join(ids, 'index.html'), 'index.html')
    z.write(os.path.join(ids, 'icon.png'), 'icon.png')
print(out2, os.path.getsize(out2))
