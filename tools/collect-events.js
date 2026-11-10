#!/usr/bin/env node
// Calendrier des grands événements sportifs : collecte (ESPN, API publique non officielle) -> data/events.json
// Lancé par GitHub Actions (accès internet), lu par l'appli pour les « affiches à ne pas manquer ».
const fs = require('fs'), path = require('path');
const OUT = path.join(__dirname, '..', 'data');
const LEAGUES = [
  ['soccer', 'fra.1', 'Ligue 1', 'football'], ['soccer', 'uefa.champions', 'Ligue des champions', 'football'], ['soccer', 'uefa.europa', 'Ligue Europa', 'football'],
  ['soccer', 'eng.1', 'Premier League', 'football'], ['soccer', 'esp.1', 'LaLiga', 'football'], ['soccer', 'ita.1', 'Serie A', 'football'], ['soccer', 'ger.1', 'Bundesliga', 'football'],
  ['soccer', 'fra.coupe_de_france', 'Coupe de France', 'football'], ['soccer', 'fifa.world', 'Coupe du monde', 'football'], ['soccer', 'uefa.nations', 'Ligue des nations', 'football'],
  ['basketball', 'nba', 'NBA', 'basket'], ['football', 'nfl', 'NFL', 'nfl'], ['hockey', 'nhl', 'NHL', 'hockey'], ['mma', 'ufc', 'UFC', 'combat'],
  ['tennis', 'atp', 'Tennis ATP', 'tennis'], ['tennis', 'wta', 'Tennis WTA', 'tennis'], ['racing', 'f1', 'Formule 1', 'auto'],
  ['rugby', '270559', 'Top 14', 'rugby'], ['rugby', '271937', 'Champions Cup', 'rugby'], ['rugby', '180659', 'Tournoi des 6 Nations', 'rugby'],
];
const ymd = d => d.toISOString().slice(0, 10).replace(/-/g, '');
const get = async u => { const r = await fetch(u, { headers: { 'User-Agent': 'Mozilla/5.0 TVStreamy-collector' }, signal: AbortSignal.timeout(30000) }); if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); };
(async () => {
  const from = new Date(), to = new Date(Date.now() + 21 * 864e5), report = {}, events = [], raw = {};
  for (const [sp, lg, label, sport] of LEAGUES) {
    const u = `https://site.api.espn.com/apis/site/v2/sports/${sp}/${lg}/scoreboard?dates=${ymd(from)}-${ymd(to)}&limit=300`;
    try {
      const d = await get(u);
      const ev = d.events || [];
      report[label] = { ok: true, n: ev.length, keys: ev[0] ? Object.keys(ev[0]) : [] };
      raw[label] = ev.slice(0, 2);
      for (const e of ev) {
        const c = (e.competitions || [])[0] || {};
        const teams = (c.competitors || []).map(t => ({ n: (t.team && (t.team.displayName || t.team.name)) || (t.athlete && t.athlete.displayName) || null, logo: (t.team && t.team.logo) || (t.athlete && t.athlete.flag && t.athlete.flag.href) || null, rec: t.records && t.records[0] && t.records[0].summary || null, rank: t.curatedRank && t.curatedRank.current || null }));
        events.push({ id: e.id, sport, comp: label, start: e.date, name: e.name, short: e.shortName, teams, note: (c.notes && c.notes[0] && c.notes[0].headline) || null, type: c.type && c.type.text || null, status: e.status && e.status.type && e.status.type.name, bouts: sp === 'mma' ? (e.competitions || []).length : undefined, season: e.season && e.season.slug || null });
      }
    } catch (err) { report[label] = { ok: false, err: String(err.message) }; }
    await new Promise(r => setTimeout(r, 400));
  }
  // classements (football) pour repérer les chocs
  const rank = {};
  for (const [lg, label] of [['fra.1', 'Ligue 1'], ['eng.1', 'Premier League'], ['esp.1', 'LaLiga'], ['ita.1', 'Serie A'], ['ger.1', 'Bundesliga']]) {
    try {
      const d = await get(`https://site.api.espn.com/apis/v2/sports/soccer/${lg}/standings`);
      const ent = (((d.children || [])[0] || {}).standings || {}).entries || [];
      rank[label] = ent.map(x => ({ n: x.team.displayName, rank: ((x.stats || []).find(s => s.name === 'rank') || {}).value, pts: ((x.stats || []).find(s => s.name === 'points') || {}).value })).sort((a, b) => a.rank - b.rank);
      report['standings ' + label] = { ok: true, n: ent.length };
    } catch (err) { report['standings ' + label] = { ok: false, err: String(err.message) }; }
  }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, 'events.json'), JSON.stringify({ generatedAt: new Date().toISOString(), source: 'ESPN (API publique)', events, rank }));
  fs.writeFileSync(path.join(OUT, 'events-report.json'), JSON.stringify({ report, sample: raw }, null, 1).slice(0, 400000));
  console.log(JSON.stringify(report));
})();
