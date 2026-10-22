# OSM Overpass(osm_stations_raw.json) + Wikidata SPARQL(wd.json) → app/src/main/assets/stations.json
# 형식: [["역명(서울 API 조회명)", lat, lng], ...]
import json, math, re
src = json.load(open("wd.json", encoding="utf-8"))
def norm(n):
    n = n.strip()
    n = re.sub(r"\s*\(.*?\)\s*$", "", n)      # "신촌역 (경의선)" → "신촌역"
    if len(n) > 2 and n.endswith("역"): n = n[:-1]
    return n.strip()
def dist(a, b):
    dy = (a[0]-b[0]) * 111000; dx = (a[1]-b[1]) * 88000
    return math.hypot(dx, dy)
out = []
records = []
osm = json.load(open("osm_stations_raw.json", encoding="utf-8"))
for el in osm.get("elements", []):
    t = el.get("tags", {})
    raw = t.get("name:ko") or t.get("name") or ""
    if not raw or t.get("usage") == "freight" or "disused" in t: continue
    records.append((raw, el["lat"], el["lon"]))
for b in src["results"]["bindings"]:
    m = re.match(r"Point\(([-\d.]+) ([-\d.]+)\)", b["coord"]["value"])
    if m: records.append((b["name"]["value"], float(m.group(2)), float(m.group(1))))
skip = re.compile(r"(신호장|조차장|화물|기지|폐역)")
for raw, lat, lng in records:
    if skip.search(raw): continue
    name = norm(raw)
    if not name: continue
    if any(o[0] == name and dist((o[1], o[2]), (lat, lng)) < 400 for o in out): continue
    out.append([name, round(lat, 6), round(lng, 6)])
out.sort()
import os
os.makedirs("../app/src/main/assets", exist_ok=True)
json.dump(out, open("../app/src/main/assets/stations.json", "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
print(len(out), "stations")
for n in ["부천", "서울", "강남", "총신대입구", "신촌", "소사", "역곡", "수원", "판교", "인천"]:
    print(n, [o for o in out if o[0] == n])
