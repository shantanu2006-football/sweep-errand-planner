package edu.atlas.sweep.algo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Dijkstra single-source shortest paths over a road graph, driven by our own binary
 * min-heap (Unit 2, CO2). Lazy deletion: a vertex may sit in the heap several times;
 * stale entries (key > current distance) are skipped when popped.
 *
 * Time O((V + E) log V), space O(V + E). Requires non-negative edge weights, which
 * road lengths are; negative weights are rejected.
 *
 * Shortest-path distances on an undirected graph form a metric (symmetric, and
 * d(a,c) <= d(a,b) + d(b,c) because joining two shortest paths is a valid path),
 * so the metric-TSP 2-approximation stays valid on real road distances.
 */
public final class Dijkstra {

    private Dijkstra() {}

    /** Undirected adjacency-list graph. Per-vertex arrays are copy-on-write, so copy() is cheap and safe. */
    public static final class Graph {
        final List<int[]> to = new ArrayList<>();
        final List<double[]> w = new ArrayList<>();

        public Graph(int n) {
            for (int i = 0; i < n; i++) { to.add(new int[0]); w.add(new double[0]); }
        }

        private Graph(List<int[]> to, List<double[]> w) { this.to.addAll(to); this.w.addAll(w); }

        public int size() { return to.size(); }

        public int addVertex() { to.add(new int[0]); w.add(new double[0]); return to.size() - 1; }

        public void addEdge(int a, int b, double weight) {
            if (weight < 0 || Double.isNaN(weight)) throw new IllegalArgumentException("negative edge weight");
            append(a, b, weight);
            append(b, a, weight);
        }

        /** Removes the undirected edge a-b if present (used when splitting a road segment). */
        public void removeEdge(int a, int b) { drop(a, b); drop(b, a); }

        public int[] neighbours(int u) { return to.get(u); }
        public double[] weights(int u) { return w.get(u); }

        /** Copy that can be extended (home attachment) without touching the shared base graph. */
        public Graph copy() { return new Graph(new ArrayList<>(to), new ArrayList<>(w)); }

        private void append(int a, int b, double weight) {
            int[] t = Arrays.copyOf(to.get(a), to.get(a).length + 1);
            double[] ww = Arrays.copyOf(w.get(a), w.get(a).length + 1);
            t[t.length - 1] = b; ww[ww.length - 1] = weight;
            to.set(a, t); w.set(a, ww);
        }

        private void drop(int a, int b) {
            int[] t = to.get(a); double[] ww = w.get(a);
            for (int i = 0; i < t.length; i++) if (t[i] == b) {
                int[] nt = new int[t.length - 1]; double[] nw = new double[t.length - 1];
                System.arraycopy(t, 0, nt, 0, i); System.arraycopy(t, i + 1, nt, i, t.length - i - 1);
                System.arraycopy(ww, 0, nw, 0, i); System.arraycopy(ww, i + 1, nw, i, t.length - i - 1);
                to.set(a, nt); w.set(a, nw);
                return;
            }
        }
    }

    public record Result(double[] dist, int[] parent, int pops, int relaxations) {
        /** Vertex sequence source..target, or empty if unreachable. */
        public List<Integer> pathTo(int target) {
            if (Double.isInfinite(dist[target])) return List.of();
            List<Integer> p = new ArrayList<>();
            for (int v = target; v != -1; v = parent[v]) p.add(v);
            Collections.reverse(p);
            return p;
        }
    }

    public static Result shortestPaths(Graph g, int source) {
        int n = g.size();
        if (source < 0 || source >= n) throw new IllegalArgumentException("source out of range");
        double[] dist = new double[n];
        int[] parent = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);
        dist[source] = 0;
        MinHeap heap = new MinHeap(Math.max(16, n));
        heap.push(0, source);
        int pops = 0, relax = 0;
        while (!heap.isEmpty()) {
            double d = heap.peekKey();
            int u = heap.pop();
            pops++;
            if (d > dist[u]) continue;                 // stale entry
            int[] nb = g.neighbours(u);
            double[] ws = g.weights(u);
            for (int i = 0; i < nb.length; i++) {
                double nd = d + ws[i];
                if (nd < dist[nb[i]]) {
                    dist[nb[i]] = nd;
                    parent[nb[i]] = u;
                    heap.push(nd, nb[i]);
                    relax++;
                }
            }
        }
        return new Result(dist, parent, pops, relax);
    }

    /**
     * Array-backed binary min-heap of (key, value) pairs. push / pop O(log n) by sift-up /
     * sift-down; the array doubles when full (amortized O(1) growth).
     */
    public static final class MinHeap {
        private double[] keys;
        private int[] vals;
        private int size;

        public MinHeap(int capacity) {
            keys = new double[Math.max(1, capacity)];
            vals = new int[Math.max(1, capacity)];
        }

        public boolean isEmpty() { return size == 0; }
        public int size() { return size; }

        public void push(double key, int val) {
            if (size == keys.length) { keys = Arrays.copyOf(keys, size * 2); vals = Arrays.copyOf(vals, size * 2); }
            int i = size++;
            while (i > 0) {                         // sift up
                int p = (i - 1) >>> 1;
                if (keys[p] <= key) break;
                keys[i] = keys[p]; vals[i] = vals[p]; i = p;
            }
            keys[i] = key; vals[i] = val;
        }

        public double peekKey() {
            if (size == 0) throw new IllegalStateException("empty heap");
            return keys[0];
        }

        public int pop() {
            if (size == 0) throw new IllegalStateException("empty heap");
            int top = vals[0];
            double k = keys[--size];
            int v = vals[size];
            int i = 0;
            while (true) {                          // sift down
                int c = 2 * i + 1;
                if (c >= size) break;
                if (c + 1 < size && keys[c + 1] < keys[c]) c++;
                if (keys[c] >= k) break;
                keys[i] = keys[c]; vals[i] = vals[c]; i = c;
            }
            if (size > 0) { keys[i] = k; vals[i] = v; }
            return top;
        }
    }
}
