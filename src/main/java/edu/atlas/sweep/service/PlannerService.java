package edu.atlas.sweep.service;

import edu.atlas.sweep.algo.*;
import edu.atlas.sweep.domain.City;
import edu.atlas.sweep.domain.RoadNetwork;
import edu.atlas.sweep.domain.City.Listing;
import edu.atlas.sweep.domain.City.Store;
import edu.atlas.sweep.web.Api.*;

import java.util.*;

/**
 * The errand-planning pipeline. Each stage is one assessed algorithm:
 *
 *   1. resolve + validate the list    (Trie for "did you mean")
 *   2. pre-screen store inventories   (Bloom filter, then exact confirm)
 *   3. choose which stores to visit   (weighted greedy set cover, exact DP baseline)
 *   4. order the visits               (MST double-tree + 2-opt, Held-Karp / NN baselines)
 *   5. judge prices                   (randomized quickselect medians, deal scores)
 */
public final class PlannerService {

    public static final int MAX_LINES = 20;
    public static final int MAX_QTY = 50;
    static final double MINUTES_PER_STOP = 8;

    private final City city;
    private final long seed;

    public PlannerService(City city, long seed) {
        this.city = city;
        this.seed = seed;
    }

    /** Thrown for bad input; the controller turns it into HTTP 400 with every problem listed. */
    public static final class ValidationException extends RuntimeException {
        public final List<String> details;
        public ValidationException(String msg, List<String> details) { super(msg); this.details = details; }
    }

    private record Need(int itemId, String name, int qty) {}

    public PlanResponse plan(PlanRequest req) {
        List<String> problems = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        if (req == null) throw new ValidationException("Request body is missing", List.of("Send a JSON body"));

        // ---------- parameters ----------
        double hx = 0, hy = 0;
        if (req.home() == null || req.home().x() == null || req.home().y() == null) problems.add("home location is required");
        else {
            hx = req.home().x(); hy = req.home().y();
            if (!(hx >= 0 && hx <= City.WIDTH_KM && hy >= 0 && hy <= City.HEIGHT_KM))
                problems.add("home must be inside the Powai service area (0-8 km by 0-6 km)");
        }
        int hour = req.hour() == null ? 18 : req.hour();
        if (hour < 0 || hour > 23) problems.add("hour must be between 0 and 23");
        double lambda = range(req.lambda(), 1.0, 0, 5, "lambda (price weight)", problems);
        double kmCost = range(req.kmCost(), 12, 0, 100, "kmCost", problems);
        double stopCost = range(req.stopCost(), 40, 0, 500, "stopCost", problems);
        double speed = range(req.speedKmh(), 18, 3, 60, "speedKmh", problems);

        // ---------- 1. resolve the list ----------
        List<Need> needs = new ArrayList<>();
        Map<Integer, Integer> seen = new HashMap<>();
        if (req.items() == null || req.items().isEmpty()) problems.add("the list is empty - add at least one item");
        else {
            if (req.items().size() > MAX_LINES) problems.add("at most " + MAX_LINES + " lines per trip (got " + req.items().size() + ")");
            for (Line line : req.items()) {
                if (line == null || line.name() == null || line.name().isBlank()) { problems.add("a line has no item name"); continue; }
                int qty = line.qty() == null ? 1 : line.qty();
                if (qty < 1 || qty > MAX_QTY) { problems.add("quantity for '" + line.name() + "' must be 1-" + MAX_QTY); continue; }
                Optional<Integer> id = city.itemIdByName(line.name());
                if (id.isEmpty()) {
                    List<String> dym = city.trie().suggest(firstWord(line.name()), 3).ids().stream()
                            .map(i -> city.item(i).name()).toList();
                    problems.add("unknown item '" + line.name() + "'" + (dym.isEmpty() ? "" : " - did you mean: " + String.join(", ", dym) + "?"));
                    continue;
                }
                Integer at = seen.get(id.get());
                if (at != null) { // duplicate line: merge rather than double-buy silently
                    Need old = needs.get(at);
                    needs.set(at, new Need(old.itemId(), old.name(), Math.min(MAX_QTY, old.qty() + qty)));
                    notices.add("merged duplicate line for " + old.name() + " (qty now " + needs.get(at).qty() + ")");
                } else {
                    seen.put(id.get(), needs.size());
                    needs.add(new Need(id.get(), city.item(id.get()).name(), qty));
                }
            }
        }
        if (!problems.isEmpty()) throw new ValidationException("The trip request has problems", problems);

        Random rng = new Random(seed);
        int n = needs.size();
        RoadNetwork.Session roads = city.roads().session(hx, hy);   // home joined to the road graph

        // ---------- 2. Bloom pre-screen + exact confirmation ----------
        List<Store> open = city.stores().stream().filter(s -> s.openAt(hour)).toList();
        long t0 = System.nanoTime();
        int checks = 0, rejected = 0, passed = 0, fps = 0, confirmed = 0;
        double expectedFpSum = 0;
        List<Screen> screens = new ArrayList<>();
        boolean[][] can = new boolean[n][open.size()];
        for (int i = 0; i < n; i++) {
            Need need = needs.get(i);
            List<Integer> no = new ArrayList<>(), yes = new ArrayList<>(), fp = new ArrayList<>(), low = new ArrayList<>();
            for (int j = 0; j < open.size(); j++) {
                Store s = open.get(j);
                BloomFilter bf = city.bloom(s.id());
                checks++;
                expectedFpSum += bf.theoreticalFpRate();
                if (!bf.mightContain(City.key(need.itemId()))) { rejected++; no.add(s.id()); continue; }
                passed++;
                Listing l = city.listing(s.id(), need.itemId());           // authoritative check
                if (l == null) { fps++; fp.add(s.id()); continue; }
                confirmed++;
                if (l.stock() < need.qty()) { low.add(s.id()); continue; }
                yes.add(s.id());
                can[i][j] = true;
            }
            screens.add(new Screen(need.itemId(), need.name(), no, yes, fp, low));
        }
        long screenNs = System.nanoTime() - t0;
        int negatives = checks - confirmed;
        ScreenStats stats = new ScreenStats(checks, rejected, passed, fps, confirmed,
                negatives == 0 ? 0 : (double) fps / negatives, checks == 0 ? 0 : expectedFpSum / checks, screenNs);

        // ---------- 3. set cover: which stores ----------
        int m = open.size();
        int[][] covers = new int[m][];
        double[] cost = new double[m];
        double[][] premium = new double[m][n];
        int[] minPrice = new int[n];
        Arrays.fill(minPrice, Integer.MAX_VALUE);
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++)
            if (can[i][j]) minPrice[i] = Math.min(minPrice[i], city.listing(open.get(j).id(), needs.get(i).itemId()).price());
        for (int j = 0; j < m; j++) {
            Store s = open.get(j);
            List<Integer> c = new ArrayList<>();
            for (int i = 0; i < n; i++) if (can[i][j]) {
                c.add(i);
                premium[j][i] = (double) (city.listing(s.id(), needs.get(i).itemId()).price() - minPrice[i]) * needs.get(i).qty();
            }
            covers[j] = c.stream().mapToInt(Integer::intValue).toArray();
            cost[j] = stopCost + kmCost * 2 * roads.dist(roads.home, roads.vertexOf(s.id())); // round trip by road (Dijkstra)
        }
        SetCover.Instance inst = new SetCover.Instance(n, covers, cost, premium);
        long t1 = System.nanoTime();
        SetCover.Result cover = SetCover.greedy(inst, lambda);
        long coverNs = System.nanoTime() - t1;
        Double exactCost = null, ratio = null;
        if (n <= 16) {
            double g0 = SetCover.greedy(inst, 0).cost();
            exactCost = SetCover.exact(inst).cost();
            ratio = exactCost == 0 ? 1.0 : g0 / exactCost;
        }
        int candidates = (int) Arrays.stream(covers).filter(c -> c.length > 0).count();
        List<CoverStep> coverSteps = cover.steps().stream().map(st -> new CoverStep(open.get(st.set()).id(),
                st.newlyCovered().stream().map(i -> needs.get(i).name()).toList(), round(st.ratio(), 2))).toList();
        CoverReport coverReport = new CoverReport(coverSteps, round(cover.cost(), 2), exactCost == null ? null : round(exactCost, 2),
                ratio == null ? null : round(ratio, 4), round(SetCover.harmonic(Math.max(1, n)), 3), lambda == 0, candidates, coverNs);

        List<Unmet> unmet = new ArrayList<>();
        for (int i : cover.uncoverable()) unmet.add(new Unmet(needs.get(i).name(), explainUnmet(needs.get(i), hour, screens.get(i))));

        // ---------- assign each item to the cheapest chosen store ----------
        List<Store> chosen = cover.chosen().stream().map(open::get).toList();
        Map<Integer, List<Pick>> picksByStore = new LinkedHashMap<>();
        for (Store s : chosen) picksByStore.put(s.id(), new ArrayList<>());
        int basket = 0, savedVsMedian = 0;
        Set<Integer> uncoverable = new HashSet<>(cover.uncoverable());
        for (int i = 0; i < n; i++) {
            if (uncoverable.contains(i)) continue;
            Need need = needs.get(i);
            Store best = null;
            int bestPrice = Integer.MAX_VALUE;
            for (Store s : chosen) {
                Listing l = city.listing(s.id(), need.itemId());
                if (l != null && l.stock() >= need.qty() && l.price() < bestPrice) { best = s; bestPrice = l.price(); }
            }
            double[] prices = city.pricesOf(need.itemId());
            int median = (int) Math.round(RandomizedSelect.median(prices, rng));
            double deal = RandomizedSelect.rankFraction(prices, bestPrice);
            int cMin = (int) RandomizedSelect.select(prices.clone(), 0, rng);
            int cMax = (int) RandomizedSelect.select(prices.clone(), prices.length - 1, rng);
            picksByStore.get(best.id()).add(new Pick(need.name(), need.itemId(), need.qty(), bestPrice, best.id(),
                    median, round(deal, 3), cMin, cMax));
            basket += bestPrice * need.qty();
            savedVsMedian += (median - bestPrice) * need.qty();
        }
        // a store can end up with no picks if a later, cheaper store took all its items
        List<Store> visiting = chosen.stream().filter(s -> !picksByStore.get(s.id()).isEmpty()).toList();
        if (visiting.size() < chosen.size()) notices.add("dropped " + (chosen.size() - visiting.size()) + " store(s) whose items are cheaper at another chosen stop");

        // ---------- 4. route ----------
        RouteResult rr = route(roads, visiting, rng);
        List<Stop> stops = new ArrayList<>();
        double cum = 0;
        for (int idx = 1; idx < rr.order.size(); idx++) {
            Store s = visiting.get(rr.order.get(idx) - 1);
            int prevV = idx == 1 ? roads.home : roads.vertexOf(visiting.get(rr.order.get(idx - 1) - 1).id());
            double leg = roads.dist(prevV, roads.vertexOf(s.id()));
            cum += leg;
            List<Pick> picks = picksByStore.get(s.id());
            int sub = picks.stream().mapToInt(p -> p.price() * p.qty()).sum();
            int eta = (int) Math.round(cum / speed * 60 + (idx - 1) * MINUTES_PER_STOP);
            double arriveH = hour + eta / 60.0;
            // risky if we arrive after closing, or with under 15 minutes to shop before it shuts
            boolean risk = s.close() < 24 && arriveH > s.close() - 0.25;
            if (risk) notices.add(s.name() + " closes at " + s.close() + ":00 and you'd reach it around " + clock(arriveH) + " - leave earlier or swap it");
            stops.add(new Stop(s.id(), s.name(), s.type(), s.x(), s.y(), picks, sub, round(leg, 2), eta,
                    City.lat(s.x(), s.y()), City.lng(s.x(), s.y()), clock(arriveH), s.close(), risk));
        }
        double km = rr.report.km();
        int minutes = (int) Math.round(km / speed * 60 + visiting.size() * MINUTES_PER_STOP);
        double travel = kmCost * km + stopCost * visiting.size();
        Totals totals = new Totals(basket, round(km, 2), visiting.size(), minutes, round(travel, 2),
                round(basket + travel, 2), savedVsMedian);

        // ---------- baselines ----------
        List<Alternative> alts = new ArrayList<>();
        alts.add(new Alternative("Sweep", "Greedy set cover + 2-approx route + 2-opt", visiting.size(), round(km, 2),
                basket, round(basket + travel, 2), minutes, visiting.stream().map(Store::id).toList()));
        alts.add(baselineCheapest(needs, open, can, uncoverable, roads, kmCost, stopCost, speed, rng));
        alts.add(baselineNearest(needs, open, can, uncoverable, roads, kmCost, stopCost, speed, rng));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("lambda", lambda); params.put("kmCost", kmCost); params.put("stopCost", stopCost);
        params.put("speedKmh", speed); params.put("openStores", open.size()); params.put("roadVertices", city.roads().vertexCount());
        return new PlanResponse(new Point(hx, hy), hour, stops, totals, stats, screens, coverReport, rr.report,
                alts, unmet, notices, params, share(hx, hy, hour, stops, totals, unmet, req.mode()));
    }

    private record RouteResult(List<Integer> order, RouteReport report) {}

    private RouteResult route(RoadNetwork.Session roads, List<Store> visit, Random rng) {
        int k = visit.size() + 1;
        int[] v = new int[k];
        v[0] = roads.home;
        for (int i = 0; i < visit.size(); i++) v[i + 1] = roads.vertexOf(visit.get(i).id());
        int runs0 = roads.dijkstraRuns, pops0 = roads.heapPops;
        double[][] d = new double[k][k];                       // road distances, one Dijkstra per stop
        for (int i = 0; i < k; i++) for (int j = 0; j < k; j++) d[i][j] = i == j ? 0 : roads.dist(v[i], v[j]);
        for (int i = 0; i < k; i++) for (int j = i + 1; j < k; j++) { double m = Math.min(d[i][j], d[j][i]); d[i][j] = m; d[j][i] = m; }
        long t = System.nanoTime();
        MetricTsp.ApproxTrace tr = MetricTsp.doubleTreeTwoOpt(d, rng);
        long ns = System.nanoTime() - t;
        MetricTsp.Tour nn = MetricTsp.nearestNeighbour(d);
        Double opt = null, ratio = null;
        if (k <= 13) {
            opt = MetricTsp.heldKarp(d).length();
            ratio = opt < 1e-9 ? 1.0 : tr.improved().length() / opt;
        }
        // the drawn route: concatenated shortest road paths between consecutive stops
        List<double[]> path = new ArrayList<>();
        List<Integer> ord = tr.improved().order();
        for (int i = 0; i < ord.size(); i++) {
            List<double[]> leg = roads.path(v[ord.get(i)], v[ord.get((i + 1) % ord.size())]);
            for (int p = path.isEmpty() ? 0 : 1; p < leg.size(); p++) path.add(new double[]{round(leg.get(p)[0], 4), round(leg.get(p)[1], 4)});
        }
        List<Edge> edges = tr.mst().stream().map(e -> new Edge(e.a() == 0 ? 0 : visit.get(e.a() - 1).id(),
                e.b() == 0 ? 0 : visit.get(e.b() - 1).id(), round(e.w(), 2))).toList();
        List<Integer> orderIds = ord.stream().map(i -> i == 0 ? 0 : visit.get(i - 1).id()).toList();
        List<Integer> preIds = tr.preorder().order().stream().map(i -> i == 0 ? 0 : visit.get(i - 1).id()).toList();
        RouteReport rep = new RouteReport(orderIds, preIds, round(tr.improved().length(), 3), round(tr.preorder().length(), 3),
                round(tr.mstWeight(), 3), tr.twoOptMoves(), edges, round(nn.length(), 3),
                opt == null ? null : round(opt, 3), ratio == null ? null : round(ratio, 4), ns,
                path, roads.dijkstraRuns - runs0, roads.heapPops - pops0, city.roads().vertexCount());
        return new RouteResult(ord, rep);
    }

    /** Baseline A: buy every item wherever it is cheapest, however many stops that takes. */
    private Alternative baselineCheapest(List<Need> needs, List<Store> open, boolean[][] can, Set<Integer> skip,
                                         RoadNetwork.Session roads, double kmCost, double stopCost, double speed, Random rng) {
        LinkedHashSet<Store> stops = new LinkedHashSet<>();
        int basket = 0;
        for (int i = 0; i < needs.size(); i++) {
            if (skip.contains(i)) continue;
            Store best = null; int bp = Integer.MAX_VALUE;
            for (int j = 0; j < open.size(); j++) if (can[i][j]) {
                int p = city.listing(open.get(j).id(), needs.get(i).itemId()).price();
                if (p < bp) { bp = p; best = open.get(j); }
            }
            stops.add(best);
            basket += bp * needs.get(i).qty();
        }
        return alt("Cheapest everywhere", "Each item from the store with the lowest price", new ArrayList<>(stops),
                basket, roads, kmCost, stopCost, speed, rng);
    }

    /** Baseline B: keep going to the nearest store that has anything still missing. */
    private Alternative baselineNearest(List<Need> needs, List<Store> open, boolean[][] can, Set<Integer> skip,
                                        RoadNetwork.Session roads, double kmCost, double stopCost, double speed, Random rng) {
        boolean[] got = new boolean[needs.size()];
        for (int i : skip) got[i] = true;
        List<Store> stops = new ArrayList<>();
        int basket = 0;
        int cur = roads.home;
        while (true) {
            int best = -1; double bd = Double.POSITIVE_INFINITY;
            for (int j = 0; j < open.size(); j++) {
                boolean useful = false;
                for (int i = 0; i < needs.size(); i++) if (!got[i] && can[i][j]) { useful = true; break; }
                double dd = roads.dist(cur, roads.vertexOf(open.get(j).id()));
                if (useful && dd < bd) { bd = dd; best = j; }
            }
            if (best < 0) break;
            Store s = open.get(best);
            stops.add(s);
            for (int i = 0; i < needs.size(); i++) if (!got[i] && can[i][best]) {
                got[i] = true;
                basket += city.listing(s.id(), needs.get(i).itemId()).price() * needs.get(i).qty();
            }
            cur = roads.vertexOf(s.id());
        }
        return alt("Nearest store first", "Go to the closest shop (by road) that has something you still need", stops,
                basket, roads, kmCost, stopCost, speed, rng);
    }

    private Alternative alt(String name, String desc, List<Store> stops, int basket, RoadNetwork.Session roads,
                            double kmCost, double stopCost, double speed, Random rng) {
        double km = stops.isEmpty() ? 0 : route(roads, stops, rng).report.km();
        int minutes = (int) Math.round(km / speed * 60 + stops.size() * MINUTES_PER_STOP);
        double total = basket + kmCost * km + stopCost * stops.size();
        return new Alternative(name, desc, stops.size(), round(km, 2), basket, round(total, 2), minutes,
                stops.stream().map(Store::id).toList());
    }

    private String explainUnmet(Need need, int hour, Screen screen) {
        if (!screen.lowStock().isEmpty()) {
            int maxStock = screen.lowStock().stream().mapToInt(s -> city.listing(s, need.itemId()).stock()).max().orElse(0);
            return "no open store has " + need.qty() + " in stock (most at one store: " + maxStock + ")";
        }
        List<String> closed = city.stores().stream()
                .filter(s -> !s.openAt(hour) && city.listing(s.id(), need.itemId()) != null)
                .map(s -> s.name() + " (opens " + s.open() + ":00)").limit(2).toList();
        if (!closed.isEmpty()) return "only stocked at stores closed at " + hour + ":00 - " + String.join(", ", closed);
        return "not stocked by any store in the area";
    }

    /** Google Maps directions link (home -> stops -> home) and a WhatsApp-ready summary. */
    private Share share(double hx, double hy, int hour, List<Stop> stops, Totals t, List<Unmet> unmet, String mode) {
        String travel = switch (mode == null ? "" : mode) {
            case "walk" -> "walking";
            case "cycle" -> "bicycling";
            default -> "driving";
        };
        String home = City.lat(hx, hy) + "," + City.lng(hx, hy);
        StringBuilder url = new StringBuilder("https://www.google.com/maps/dir/?api=1&origin=").append(home)
                .append("&destination=").append(home).append("&travelmode=").append(travel);
        if (!stops.isEmpty()) {
            url.append("&waypoints=");
            for (int i = 0; i < stops.size(); i++) url.append(i == 0 ? "" : "%7C").append(stops.get(i).lat()).append(",").append(stops.get(i).lng());
        }
        StringBuilder txt = new StringBuilder();
        txt.append("Sweep errand run - leave ").append(clock(hour)).append("\n")
           .append(t.stops()).append(" stops, ").append(String.format(Locale.ROOT, "%.1f", t.km())).append(" km, about ")
           .append(t.minutes()).append(" min\n\n");
        for (int i = 0; i < stops.size(); i++) {
            Stop s = stops.get(i);
            txt.append(i + 1).append(". ").append(s.name()).append(" (~").append(s.arrival()).append(")\n");
            for (Pick p : s.picks()) txt.append("   - ").append(p.qty()).append(" x ").append(p.item()).append("  Rs ").append(p.price() * p.qty()).append("\n");
        }
        for (Unmet u : unmet) txt.append("Not available: ").append(u.item()).append(" (").append(u.reason()).append(")\n");
        txt.append("\nBasket Rs ").append(t.basket()).append(" | trip cost Rs ").append(Math.round(t.total())).append("\n").append("Route: ").append(url);
        return new Share(url.toString(), txt.toString(), travel);
    }

    static String clock(double h) {
        int mins = (int) Math.round(h * 60) % (24 * 60);
        return String.format(Locale.ROOT, "%02d:%02d", mins / 60, mins % 60);
    }

    private static double range(Double v, double def, double lo, double hi, String name, List<String> problems) {
        if (v == null) return def;
        if (v.isNaN() || v < lo || v > hi) { problems.add(name + " must be between " + lo + " and " + hi); return def; }
        return v;
    }

    private static String firstWord(String s) {
        String t = Trie.normalize(s);
        int sp = t.indexOf(' ');
        return sp < 0 ? t : t.substring(0, sp);
    }

    static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
