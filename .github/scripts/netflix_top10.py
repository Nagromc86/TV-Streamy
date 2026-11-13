"""Netflix's official weekly Top 10 for France (films and series), from the data Netflix publishes on Tudum.
Writes a small JSON that the app downloads once a day: {"week": "...", "films": [...], "tv": [...]}.
Usage: python3 .github/scripts/netflix_top10.py out.json"""
import csv, io, json, sys, urllib.request

URL = 'https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv'
req = urllib.request.Request(URL, headers={'User-Agent': 'Mozilla/5.0 (TV Streamy top10 job)'})
import time
raw = None
for attempt in range(4):  # le téléchargement (~33 Mo) est parfois coupé en route : on réessaie
    try:
        raw = urllib.request.urlopen(req, timeout=120).read().decode('utf-8', 'replace')
        break
    except Exception as e:
        print('Essai %d échoué : %s' % (attempt + 1, e))
        if attempt == 3:
            raise
        time.sleep(20 * (attempt + 1))
rows = [r for r in csv.DictReader(io.StringIO(raw), delimiter='\t') if (r.get('country_iso2') or '').upper() == 'FR']
if not rows:
    sys.exit('no France rows: columns are %s' % raw.split('\n', 1)[0])
week = max(r['week'] for r in rows)
out = {'week': week, 'films': [], 'tv': []}
for r in sorted((r for r in rows if r['week'] == week), key=lambda r: int(r['weekly_rank'])):
    item = {'rank': int(r['weekly_rank']), 'title': r['show_title'].strip(), 'season': (r.get('season_title') or '').strip(),
            'weeks': int(r.get('cumulative_weeks_in_top_10') or 0)}
    out['tv' if r['category'].lower().startswith('tv') else 'films'].append(item)
json.dump(out, open(sys.argv[1], 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(week, len(out['films']), 'films', len(out['tv']), 'séries')
for k in ('films', 'tv'):
    print(k, [x['title'] for x in out[k]])
