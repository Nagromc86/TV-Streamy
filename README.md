# TV Streamy

Appli Android pour regarder ses abonnements IPTV (Xtream Codes) : plusieurs comptes, TV en direct avec programme,
films et séries, favoris, reprise de lecture, recherche. Les identifiants restent sur le téléphone.

Dernière version : https://github.com/Nagromc86/TV-Streamy/releases/latest/download/tv-streamy.apk

- `src/app.html` : l'interface (assemblée dans `www/index.html` par `tools/build.py`, avec les polices intégrées).
- `plugins/iptv-player` : le lecteur vidéo natif (Media3 / ExoPlayer) : TS, HLS, MP4, MKV, pistes audio et sous-titres.
- `tools/fake_xtream.py` : faux serveur Xtream pour tester l'interface (`python3 tools/fake_xtream.py 8899`, compte demo / demo).
- `.github/workflows/apk.yml` : construit, signe et publie l'APK. La clé de signature est dans les secrets du dépôt
  (`APK_KEYSTORE`, `APK_KEYSTORE_PASSWORD`).
