package edu.atlas.sweep.domain;

import edu.atlas.sweep.algo.Dijkstra;
import edu.atlas.sweep.algo.Dijkstra.Graph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Powai road graph built from data/roads.json (polylines in km).
 *
 * Build: every polyline segment is split at its crossings with every other segment and at
 * T-junctions, points closer than 1 m are merged into one vertex, and consecutive points
 * along a segment become an edge weighted by its length in km. O(S^2) over S segments at
 * start-up (S is about 100), done once.
 *
 * Attach: a shop or the home is joined to the nearest point on any road (a short access
 * lane); that point splits the edge it lies on. Shops are attached once at load; home is
 * attached per request on a cheap copy of the graph.
 */
public final class RoadNetwork {

    public record Road(String name, int width, List<double[]> pts) {}

    private final List<Road> roads;
    private final Graph base;
    private final List<double[]> xy = new ArrayList<>();            // vertex coordinates
    private final Map<Integer, Integer> storeVertex = new HashMap<>();

    private RoadNetwork(List<Road> roads) {
        this.roads = roads;
        this.base = new Graph(0);
    }

    public static RoadNetwork load(Path file, Collection<City.Store> stores) {
        RoadNetwork rn = new RoadNetwork(parse(file));
        rn.build();
        for (City.Store s : stores) rn.storeVertex.put(s.id(), rn.attach(rn.base, rn.xy, s.x(), s.y()));
        rn.checkConnected();
        return rn;
    }

    public List<Road> roads() { return roads; }
    public int vertexCount() { return base.size(); }
    public int storeVertex(int storeId) { return storeVertex.get(storeId); }

    /** A per-request view: the base graph plus the home vertex, with cached Dijkstra runs. */
    public final class Session {
        final Graph g;
        final List<double[]> pts;
        public final int home;
        private final Map<Integer, Dijkstra.Result> cache = new HashMap<>();
        public int dijkstraRuns, heapPops;

        Session(double hx, double hy) {
            g = base.copy();
            pts = new ArrayList<>(xy);
            home = attach(g, pts, hx, hy);
        }

        public Dijkstra.Result from(int v) {
            return cache.computeIfAbsent(v, k -> {
                Dijkstra.Result r = Dijkstra.shortestPaths(g, k);
                dijkstraRuns++; heapPops += r.pops();
                return r;
            });
        }

        public int vertexOf(int storeId) { return storeVertex.get(storeId); }

        public double dist(int a, int b) { return from(a).dist()[b]; }

        /** Road polyline from vertex a to vertex b as [x, y] points. */
        public List<double[]> path(int a, int b) {
            List<double[]> out = new ArrayList<>();
            for (int v : from(a).pathTo(b)) out.add(pts.get(v));
            return out;
        }
    }

    public Session session(double hx, double hy) { return new Session(hx, hy); }

    // ------------------------------------------------------------------ build
    private void build() {
        List<double[][]> segs = new ArrayList<>();
        for (Road r : roads) for (int i = 1; i < r.pts().size(); i++) segs.add(new double[][]{r.pts().get(i - 1), r.pts().get(i)});
        // split parameters per segment: 0, 1 and every crossing / touching point
        List<TreeSet<Double>> cuts = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) cuts.add(new TreeSet<>(List.of(0.0, 1.0)));
        for (int i = 0; i < segs.size(); i++) for (int j = i + 1; j < segs.size(); j++) {
            double[] tu = intersect(segs.get(i), segs.get(j));
            if (tu != null) { cuts.get(i).add(tu[0]); cuts.get(j).add(tu[1]); }
        }
        // T-junctions: an endpoint lying on (or within 60 m of) another segment's interior
        List<double[][]> connectors = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) for (double[] end : segs.get(i)) for (int j = 0; j < segs.size(); j++) {
            if (i == j) continue;
            double t = project(segs.get(j), end[0], end[1]);
            double[] q = at(segs.get(j), t);
            double gap = Math.hypot(q[0] - end[0], q[1] - end[1]);
            if (t > 1e-6 && t < 1 - 1e-6 && gap < 0.06) { cuts.get(j).add(t); if (gap > 1e-3) connectors.add(new double[][]{end, q}); }
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < segs.size(); i++) {
            Integer prev = null;
            double[] prevPt = null;
            for (double t : cuts.get(i)) {
                double[] p = at(segs.get(i), t);
                int v = vertex(index, p);
                if (prev != null && prev != v) {
                    double len = Math.hypot(p[0] - prevPt[0], p[1] - prevPt[1]);
                    if (!hasEdge(prev, v)) base.addEdge(prev, v, len);
                }
                prev = v; prevPt = p;
            }
        }
        for (double[][] c : connectors) {
            int a = vertex(index, c[0]), b = vertex(index, c[1]);
            if (a != b && !hasEdge(a, b)) base.addEdge(a, b, Math.hypot(c[0][0] - c[1][0], c[0][1] - c[1][1]));
        }
        heal();
    }

    /** Links every stray component to the largest one by its closest vertex pair (O(V^2), start-up only). */
    private void heal() {
        while (true) {
            int n = base.size();
            int[] comp = new int[n];
            Arrays.fill(comp, -1);
            int c = 0, bigC = 0, bigSize = 0;
            for (int s = 0; s < n; s++) {
                if (comp[s] >= 0) continue;
                Deque<Integer> st = new ArrayDeque<>(List.of(s));
                comp[s] = c;
                int size = 0;
                while (!st.isEmpty()) { int u = st.pop(); size++; for (int w : base.neighbours(u)) if (comp[w] < 0) { comp[w] = c; st.push(w); } }
                if (size > bigSize) { bigSize = size; bigC = c; }
                c++;
            }
            if (c <= 1) return;
            int stray = comp[0] == bigC ? -1 : 0;
            for (int i = 0; i < n && stray < 0; i++) if (comp[i] != bigC) stray = i;
            int sc = comp[stray], ba = -1, bb = -1;
            double bd = Double.POSITIVE_INFINITY;
            for (int a = 0; a < n; a++) if (comp[a] == sc) for (int b = 0; b < n; b++) if (comp[b] == bigC) {
                double d = Math.hypot(xy.get(a)[0] - xy.get(b)[0], xy.get(a)[1] - xy.get(b)[1]);
                if (d < bd) { bd = d; ba = a; bb = b; }
            }
            base.addEdge(ba, bb, bd);
        }
    }

    private int vertex(Map<String, Integer> index, double[] p) {
        String key = Math.round(p[0] * 1000) + ":" + Math.round(p[1] * 1000);   // merge within ~1 m
        return index.computeIfAbsent(key, k -> { xy.add(p); return base.addVertex(); });
    }

    private boolean hasEdge(int a, int b) {
        for (int x : base.neighbours(a)) if (x == b) return true;
        return false;
    }

    /** Joins (x, y) to the nearest road point; returns the new vertex for (x, y). */
    private static int attach(Graph g, List<double[]> pts, double x, double y) {
        int bestA = -1, bestB = -1;
        double bestD = Double.POSITIVE_INFINITY, bestT = 0;
        for (int a = 0; a < g.size(); a++) for (int b : g.neighbours(a)) {
            if (b < a) continue;
            double[][] s = {pts.get(a), pts.get(b)};
            double t = project(s, x, y);
            double[] q = at(s, t);
            double d = Math.hypot(q[0] - x, q[1] - y);
            if (d < bestD) { bestD = d; bestA = a; bestB = b; bestT = t; }
        }
        int me = g.addVertex();
        pts.add(new double[]{x, y});
        if (bestA < 0) return me;                         // empty graph: isolated vertex
        double[] a = pts.get(bestA), b = pts.get(bestB);
        double[] q = at(new double[][]{a, b}, bestT);
        int joint;
        if (bestT < 1e-6) joint = bestA;
        else if (bestT > 1 - 1e-6) joint = bestB;
        else {                                            // split edge a-b at q
            joint = g.addVertex();
            pts.add(q);
            g.removeEdge(bestA, bestB);
            g.addEdge(bestA, joint, Math.hypot(q[0] - a[0], q[1] - a[1]));
            g.addEdge(joint, bestB, Math.hypot(b[0] - q[0], b[1] - q[1]));
        }
        g.addEdge(me, joint, bestD);                      // access lane / driveway
        return me;
    }

    private void checkConnected() {
        Dijkstra.Result r = Dijkstra.shortestPaths(base, storeVertex.values().iterator().next());
        for (Map.Entry<Integer, Integer> e : storeVertex.entrySet())
            if (Double.isInfinite(r.dist()[e.getValue()]))
                throw new IllegalStateException("road network: store " + e.getKey() + " is not reachable");
    }

    // ------------------------------------------------------------------ geometry
    static double[] intersect(double[][] p, double[][] q) {
        double rx = p[1][0] - p[0][0], ry = p[1][1] - p[0][1], sx = q[1][0] - q[0][0], sy = q[1][1] - q[0][1];
        double den = rx * sy - ry * sx;
        if (Math.abs(den) < 1e-12) return null;           // parallel
        double qpx = q[0][0] - p[0][0], qpy = q[0][1] - p[0][1];
        double t = (qpx * sy - qpy * sx) / den, u = (qpx * ry - qpy * rx) / den;
        if (t < -1e-9 || t > 1 + 1e-9 || u < -1e-9 || u > 1 + 1e-9) return null;
        return new double[]{Math.max(0, Math.min(1, t)), Math.max(0, Math.min(1, u))};
    }

    static double project(double[][] s, double x, double y) {
        double dx = s[1][0] - s[0][0], dy = s[1][1] - s[0][1], L = dx * dx + dy * dy;
        return L == 0 ? 0 : Math.max(0, Math.min(1, ((x - s[0][0]) * dx + (y - s[0][1]) * dy) / L));
    }

    static double[] at(double[][] s, double t) {
        return new double[]{s[0][0] + t * (s[1][0] - s[0][0]), s[0][1] + t * (s[1][1] - s[0][1])};
    }

    // ------------------------------------------------------------------ tiny JSON reader for roads.json
    private static final Pattern ROAD = Pattern.compile("\\{\"name\":\"([^\"]*)\",\"w\":(\\d+),\"pts\":\\[(.*?)]]}");
    private static final Pattern PT = Pattern.compile("\\[(-?[\\d.]+),(-?[\\d.]+)");

    static List<Road> parse(Path file) {
        String json;
        try { json = Files.readString(file).replaceAll("\\s+", ""); }
        catch (IOException e) { throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e); }
        List<Road> out = new ArrayList<>();
        Matcher m = ROAD.matcher(json);
        while (m.find()) {
            List<double[]> pts = new ArrayList<>();
            Matcher pm = PT.matcher(m.group(3) + "]");
            while (pm.find()) pts.add(new double[]{Double.parseDouble(pm.group(1)), Double.parseDouble(pm.group(2))});
            if (pts.size() >= 2) out.add(new Road(m.group(1), Integer.parseInt(m.group(2)), pts));
        }
        if (out.isEmpty()) throw new IllegalStateException("no roads parsed from " + file);
        return out;
    }
}
