"""Fake Xtream Codes server for testing the interface (no real streams).
Run: python3 tools/fake_xtream.py 8899   ->  server http://localhost:8899, user demo / pass demo"""
import base64, json, sys, time, random
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

random.seed(4)
NOW = int(time.time())
LIVE_CATS = [{'category_id': '1', 'category_name': 'FR ◉ Généralistes', 'parent_id': 0},
             {'category_id': '2', 'category_name': 'VIP ❖ Info', 'parent_id': 0},
             {'category_id': '3', 'category_name': 'VIP ❖ Sport', 'parent_id': 0},
             {'category_id': '4', 'category_name': 'AR ◉ Jeunesse', 'parent_id': 0}]
names = {'1': ['Chaîne Une', 'Deux TV', 'Trois Monde', 'Canal Quatre', 'Cinq Plus', 'Six Max'],
         '2': ['Info Continue', 'Le Fil Info', 'Monde 24', 'Éco Direct'],
         '3': ['Sport Arena', 'Foot Plus', 'Tennis Live', 'Moto Racing', 'Rugby Club'],
         '4': ['Petits Loups', 'Dessins Animés', 'Kids Fun']}
LIVE = []
n = 1
for c, L in names.items():
    for nm in L:
        LIVE.append({'num': n, 'name': 'FR | ' + nm, 'stream_type': 'live', 'stream_id': 1000 + n, 'stream_icon': '',
                     'epg_channel_id': nm.lower().replace(' ', '') + '.fr', 'added': str(NOW - n * 3600), 'category_id': c,
                     'tv_archive': 0, 'direct_source': '', 'tv_archive_duration': 0})
        n += 1
VOD_CATS = [{'category_id': '10', 'category_name': 'FR ★ Action', 'parent_id': 0},
            {'category_id': '11', 'category_name': 'FR ★ Comédie', 'parent_id': 0},
            {'category_id': '12', 'category_name': 'AR ★ Animation', 'parent_id': 0}]
titles = ['Le Dernier Convoi', 'Nuit Blanche à Lyon', 'Les Brigands du Sud', 'Opération Sirocco', 'Mon Voisin le Pirate',
          'La Grande Évasion du Lundi', 'Comète', 'Le Jardin des Robots', 'Petit Renard', 'Vents Contraires', 'L\'Échappée',
          'Code Azur', 'Les Vacances de Mamie', 'Tempête sur Brest', 'Le Mystère du Phare']
VOD = []
for i, t in enumerate(titles):
    VOD.append({'num': i + 1, 'name': '%s (%d)' % (t, 2010 + i), 'stream_type': 'movie', 'stream_id': 5000 + i, 'stream_icon': 'http://localhost:8899/img/%d.svg' % (5000 + i),
                'rating': str(round(5.5 + (i * 0.37) % 3.5, 1)), 'rating_5based': 3.5, 'added': str(NOW - i * 86400),
                'category_id': VOD_CATS[i % 3]['category_id'], 'container_extension': 'mp4' if i % 2 else 'mkv',
                'custom_sid': '', 'direct_source': ''})
SER_CATS = [{'category_id': '20', 'category_name': 'FR ★ Drame', 'parent_id': 0},
            {'category_id': '21', 'category_name': 'MA ✪ Policier', 'parent_id': 0}]
stitles = ['Les Gardiens du Port', 'Brigade Nord', 'Saison des Pluies', 'La Ferme d\'en Haut', 'Code Rouge']
SERIES = [{'num': i + 1, 'name': t, 'series_id': 7000 + i, 'cover': 'http://localhost:8899/img/%d.svg' % (7000 + i), 'plot': 'Une série de test numéro %d.' % (i + 1),
           'cast': 'Acteur A, Actrice B', 'director': 'Réal C', 'genre': ['Drame', 'Policier'][i % 2], 'releaseDate': '20%02d-09-01' % (15 + i),
           'last_modified': str(NOW - i * 7200), 'rating': str(6.5 + i * 0.4), 'rating_5based': 3.6, 'backdrop_path': [],
           'youtube_trailer': '', 'episode_run_time': '45', 'category_id': SER_CATS[i % 2]['category_id']} for i, t in enumerate(stitles)]
# like some real providers: the same series listed twice (here in both categories)
SERIES.append(dict(SERIES[0], category_id='21'))
# other language versions of the same titles
SERIES.append(dict(SERIES[1], series_id=7100, name='VOSTFR ★ Brigade Nord'))
VOD.append(dict(VOD[6], stream_id=5100, name='VOSTFR | Comète (2016) [4K]'))

def b64(s):
    return base64.b64encode(s.encode()).decode()

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path.startswith('/img/'):
            k = u.path[5:-4]; h = (int(k) * 47) % 360
            body = ('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="450"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
                    '<stop offset="0" stop-color="hsl(%d,60%%,45%%)"/><stop offset="1" stop-color="hsl(%d,70%%,12%%)"/></linearGradient></defs>'
                    '<rect width="300" height="450" fill="url(#g)"/><circle cx="220" cy="120" r="70" fill="hsl(%d,80%%,70%%)" opacity=".35"/>'
                    '<text x="24" y="400" font-family="sans-serif" font-weight="800" font-size="34" fill="#fff">%s</text></svg>') % (h, h + 40, h + 180, k)
            body = body.encode(); self.send_response(200); self.send_header('Content-Type', 'image/svg+xml')
            self.send_header('Content-Length', str(len(body))); self.end_headers(); return self.wfile.write(body)
        if u.path != '/player_api.php':
            return self.send({'error': 'not found'}, 404)
        if q.get('username') != 'demo' or q.get('password') != 'demo':
            return self.send({'user_info': {'auth': 0}})
        a = q.get('action', '')
        if a == '':
            return self.send({'user_info': {'username': 'demo', 'auth': 1, 'status': 'Active', 'exp_date': str(NOW + 200 * 86400),
                                            'max_connections': '2', 'active_cons': '0', 'allowed_output_formats': ['m3u8', 'ts']},
                              'server_info': {'url': 'localhost', 'port': '8899', 'timestamp_now': NOW}})
        cat = q.get('category_id')
        if a == 'get_live_categories': return self.send(LIVE_CATS)
        if a == 'get_live_streams': return self.send([x for x in LIVE if not cat or x['category_id'] == cat])
        if a == 'get_vod_categories': return self.send(VOD_CATS)
        if a == 'get_vod_streams': return self.send([x for x in VOD if not cat or x['category_id'] == cat])
        if a == 'get_series_categories': return self.send(SER_CATS)
        if a == 'get_series': return self.send([x for x in SERIES if not cat or x['category_id'] == cat])
        if a == 'get_vod_info':
            v = next((x for x in VOD if str(x['stream_id']) == q.get('vod_id')), None)
            if not v: return self.send([])
            return self.send({'info': {'name': v['name'], 'plot': 'Résumé de test pour « %s ».' % v['name'], 'genre': 'Aventure',
                                       'director': 'Jeanne Martin', 'cast': 'Paul Durand, Léa Petit', 'duration': '01:52:00',
                                       'releasedate': v['name'][-5:-1] + '-05-10', 'rating': v['rating'], 'tmdb_id': '12345', 'backdrop_path': []},
                              'movie_data': {'stream_id': v['stream_id'], 'container_extension': v['container_extension']}})
        if a == 'get_series_info':
            s = next((x for x in SERIES if str(x['series_id']) == q.get('series_id')), None)
            if not s: return self.send([])
            eps = {}
            for season in (1, 2):
                eps[str(season)] = [{'id': str(s['series_id'] * 100 + season * 10 + e), 'episode_num': e, 'title': 'Épisode %d' % e,
                                     'container_extension': 'mkv', 'season': season, 'info': {'duration': '00:45:00', 'plot': ''}} for e in range(1, 7)]
            return self.send({'seasons': [{'season_number': 1}, {'season_number': 2}], 'info': s, 'episodes': eps})
        if a == 'get_short_epg':
            sid = int(q.get('stream_id', '0'))
            start = NOW - (NOW % 1800) - 600
            progs = ['Journal', 'Magazine', 'Film du soir', 'Documentaire', 'Match en direct', 'Série']
            L = []
            for k in range(3):
                t = progs[(sid + k) % len(progs)]
                L.append({'id': str(k), 'title': b64(t), 'description': b64('Description de ' + t), 'start_timestamp': str(start + k * 1800),
                          'stop_timestamp': str(start + (k + 1) * 1800)})
            return self.send({'epg_listings': L})
        return self.send([])

if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    ThreadingHTTPServer(('127.0.0.1', port), H).serve_forever()
