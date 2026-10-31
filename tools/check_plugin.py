"""Fails if the app calls a native method that the Android plugin does not have.
Run: python3 tools/check_plugin.py"""
import re, sys
js = open('src/app.html', encoding='utf-8').read()
java = open('plugins/iptv-player/android/src/main/java/fr/nagromc86/iptvplayer/PlayerPlugin.java', encoding='utf-8').read()
called = set(re.findall(r"nat\('(\w+)'", js)) | set(re.findall(r"nativePromise\('IptvPlayer','(\w+)'", js))
missing = sorted(m for m in called if not re.search(r'@PluginMethod\s+public void ' + m + r'\(', java))
if missing:
    print('Méthodes natives manquantes dans PlayerPlugin.java : ' + ', '.join(missing))
    sys.exit(1)
print('OK : ' + ', '.join(sorted(called)))
