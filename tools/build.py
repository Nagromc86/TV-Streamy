"""Assemble www/index.html from src/app.html: embedded fonts + version stamp.
Usage: python3 tools/build.py <node_modules dir with @fontsource packages>"""
import base64, os, sys, datetime
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
nm = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, 'node_modules')
src = open(os.path.join(root, 'src', 'app.html'), encoding='utf-8').read()
fonts = ''
for fam, f, w in [('Bricolage Grotesque', 'bricolage-grotesque/files/bricolage-grotesque-latin-700-normal.woff2', 700),
                  ('Bricolage Grotesque', 'bricolage-grotesque/files/bricolage-grotesque-latin-800-normal.woff2', 800),
                  ('Figtree', 'figtree/files/figtree-latin-400-normal.woff2', 400),
                  ('Figtree', 'figtree/files/figtree-latin-600-normal.woff2', 600),
                  ('Figtree', 'figtree/files/figtree-latin-700-normal.woff2', 700)]:
    b = base64.b64encode(open(os.path.join(nm, '@fontsource', f), 'rb').read()).decode()
    fonts += "@font-face{font-family:'%s';font-weight:%d;font-style:normal;font-display:swap;src:url(data:font/woff2;base64,%s) format('woff2')}\n" % (fam, w, b)
try:
    from zoneinfo import ZoneInfo
    now = datetime.datetime.now(ZoneInfo('Europe/Paris'))
except Exception:
    now = datetime.datetime.now()
out = src.replace('/*@@FONTS@@*/', fonts).replace('/*@@VERSION@@*/', now.strftime('%d/%m/%Y %H:%M'))
os.makedirs(os.path.join(root, 'www'), exist_ok=True)
open(os.path.join(root, 'www', 'index.html'), 'w', encoding='utf-8').write(out)
print('www/index.html', len(out.encode()))
