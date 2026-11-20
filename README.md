# Sweep: one trip, every errand

Sweep plans a single trip for a whole errand list. You type what you need, set where you live and when you're leaving, and Sweep:

0. reads a pasted WhatsApp list in Hinglish or with brand names ("2 doodh, ande, dolo, 3 copy, surf excel") (**trie candidates + KMP scoring**, Unit 4)
1. finds items as you type (**trie**, Unit 4)
2. screens 36 Powai shops' stock in microseconds (**Bloom filters** + exact confirmation, Unit 6)
3. picks the fewest, cheapest set of shops that covers the list (**greedy weighted set cover**, Unit 5, checked against an exact DP)
4. measures real road distances over a Powai street graph (**Dijkstra with our own binary heap**, Unit 2) and
4b. orders the stops into a short loop from home (**metric-TSP 2-approximation**: Kruskal MST with **union-find**, preorder shortcut, 2-opt; Units 2 and 5, checked against Held-Karp)
5. tells you whether each price is a deal (**randomized quickselect** medians, Unit 6)

Then it hands the trip over: **one-tap Google Maps directions** with every stop as a waypoint, a **WhatsApp-ready plan**, and warnings for shops that would close before you arrive. The route can be viewed on an animated 2D map or flown through in a **procedural 3D Powai** (Three.js, bundled locally so the demo works offline), with a night mode.

The backend is Java 21 / Spring Boot 4 and implements every assessed algorithm itself. The frontend is plain HTML/CSS/JS served by the same app. It calls the REST API and animates the result.

| Course outcome | Where it is covered |
|---|---|
| CO1 complexity | Complexity table in the app (How it works), docs/REPORT |
| CO2 data structures | `DisjointSet` (Kruskal), trie nodes |
| CO3 string matching / indexing | `Trie` over word-start suffixes, `Kmp` (paste parser) |
| CO4 optimisation / approximation | `SetCover`, `MetricTsp` |
| CO5 randomized / probabilistic | `BloomFilter` (random salt), `RandomizedSelect` (quickselect + quicksort) |
| CO6 complexity classes | Exact DP baselines; NP-hardness discussion in the report |

---

## Run it

**Prerequisites:** JDK 21 or newer (tested on JDK 26). Nothing else; the Maven wrapper downloads Maven.

```bash
./mvnw spring-boot:run          # macOS / Linux
mvnw.cmd spring-boot:run        # Windows
```

Open **http://localhost:8080**. Port is configurable with `PORT=9090 ./mvnw spring-boot:run`.

Tests and a runnable jar:

```bash
./mvnw test                     # 70 JUnit tests
./mvnw -DskipTests package && java -jar target/sweep-1.0.0.jar
```

Environment variables (all optional, no secrets): `PORT` (default 8080), `SWEEP_DATA_DIR` (default `data`).

Regenerate the dataset (deterministic, seed 42): `python3 tools/generate_dataset.py`

## Demo script (8 minutes)

0. **Paste a WhatsApp list.** Press *Use example* and then *Read list*. The Hinglish message becomes catalogue items with confidence scores. Add them to the list.
1. **Plan tab.** "Weekly groceries" is preloaded. Press **Sweep it** and narrate the five stages as the ticker shows them: Bloom wave, set-cover picks with items flying to shops, MST, route, runner.
2. Point at the receipt (deal tags) and the comparison: Sweep vs "cheapest everywhere" (8 stops, 18 km) vs "nearest store first".
2b. Switch to **3D city**: the route grows as a light trail and the camera flies the loop. Toggle dark mode for the night city. Open **Directions in Google Maps** or **Send on WhatsApp**.
3. Drag the home pin somewhere else and re-run; the plan changes. Slide **price vs distance** to 0 (pure set cover, H(n) guarantee) and then to 3.
4. **Edge cases:** tap "2 AM emergency". Paracetamol is found at a 24-hour pharmacy, and the notebook is reported as "only stocked at stores closed at 2:00". Set a quantity to 50 to get "no open store has 50 in stock". Type a misspelt item to get a 400 with "did you mean".
5. **X-ray tab.** Click a red cell (false positive) to watch the k probe bits light up in the store's real bit array. Then show set-cover ratios vs the exact DP, MST → preorder → 2-opt → Held-Karp bars, and the quickselect deal meters.
6. **Lab tab.** Benchmarks run live on the server: trie vs scan, Bloom FP vs theory, quickselect on adversarial input, greedy vs exact set cover, route quality vs optimum.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/parse` `{"text": "2 doodh, dolo, 3 copy"}` | Free-text list → items (trie + KMP + aliases), with confidence and alternatives |
| GET | `/api/suggest?q=dal&k=8` | Trie autocomplete, with timing against a linear scan |
| GET | `/api/stores` | Shops with hours and Bloom sketch size |
| GET | `/api/items` | Catalogue |
| POST | `/api/plan` | Full pipeline (body below) |
| GET | `/api/bloom?store=31&item=55` | The k bit positions, the store's bit array, and the exact-check verdict |
| GET | `/api/bench/{trie\|kmp\|bloom\|select\|setcover\|tsp}?fresh=true` | Server-side benchmarks |

```bash
curl -s -X POST localhost:8080/api/plan -H 'content-type: application/json' \
     -d @data/samples/weekly-groceries.json
```

```json
{ "home": {"x": 4.62, "y": 3.38}, "hour": 18, "lambda": 1.0, "kmCost": 18, "stopCost": 40, "speedKmh": 18,
  "items": [{"name": "Milk 1L", "qty": 4}, {"name": "Eggs 12pk", "qty": 1}] }
```

| Field | Meaning | Valid range (default) |
|---|---|---|
| `home` | km east/south on the 8 × 6 km Powai grid | required |
| `hour` | departure hour; closed shops are excluded | 0–23 (18) |
| `lambda` | weight of price premium vs travel in set cover | 0–5 (1.0) |
| `kmCost` | ₹ per km of travel | 0–100 (12) |
| `stopCost` | ₹-equivalent cost of each extra stop | 0–500 (40) |
| `speedKmh` | travel speed for time estimates | 3–60 (18) |
| `items` | up to 20 lines, qty 1–50; duplicate lines are merged | required |
| `mode` | `walk` / `cycle` / `auto`, used for the Google Maps travel mode | optional |

The plan response also includes `share.mapsUrl` (Google Maps directions, home → stops → home) and `share.text` (a WhatsApp-ready summary). Each stop has `arrival` (clock time) and `closingRisk`.

Errors come back as `400 {"error": "...", "details": ["every problem found"]}`. See `data/samples/invalid.json`.

## Data

`data/` holds a realistic synthetic dataset generated by `tools/generate_dataset.py` (seed 42):

- `items.csv`: 176 items in 12 categories with MRP-style base prices
- `stores.csv`: 36 shops of 9 types around real Powai localities, with opening hours (some pharmacies are 24 h)
- `inventory.csv`: 770 listings with price (base × type multiplier × U(0.88, 1.15)) and stock (about 6% deliberately low)

- `roads.json`: 29 roads and lanes (polylines in km). The backend turns them into a graph and routes along them with Dijkstra (`GET /api/roads` returns what the map draws).

Assumptions: coordinates are approximate positions on a flat km grid. Distances are shortest road paths, which form a metric, so the TSP 2-approximation holds.

## Project layout

```
src/main/java/edu/atlas/sweep/
  algo/      Trie, Kmp, Dijkstra (+ binary heap), BloomFilter, RandomizedSelect, DisjointSet, SetCover, MetricTsp   <- assessed algorithms
  domain/    City (dataset loader, indexes), RoadNetwork (road graph, shop/home attachment)
  service/   PlannerService (pipeline), ListParser (paste a list), BenchmarkService
  web/       ApiController (REST, validation, errors), Api (DTOs)
src/main/resources/static/   index.html, app.css, app.js, city3d.js, vendor/three.module.min.js (MIT)
src/test/java/...            70 JUnit tests (5+ per algorithm, edge + stress cases)
data/                        dataset + sample requests
docs/                        proposal, technical report (HTML + PDF), screenshots
tools/generate_dataset.py
```

## Author

Shantanu Puthran (2405111). Individual submission: all algorithms, backend, frontend, tests, data and documentation.
