package edu.atlas.sweep.algo;

import java.util.*;

/**
 * Metric TSP (Unit 5, CO4 / CO6). Node 0 is the depot (the user's home); a tour starts and
 * ends there.
 *
 * doubleTree(): Kruskal MST (edges ordered by our randomized quicksort, cycles rejected
 *   with DisjointSet) -> preorder walk from node 0 -> shortcut repeated vertices.
 *   With the triangle inequality, tour <= 2 * MST <= 2 * OPT, so this is a 2-approximation.
 *   Time O(n^2 log n) (sorting the n(n-1)/2 edges), space O(n^2).
 *
 * twoOpt(): local search that reverses a segment whenever that shortens the tour. It only
 *   ever decreases length, so the 2x bound is kept. O(n^2) per pass.
 *
 * nearestNeighbour(): greedy baseline, O(n^2), no constant-factor guarantee (Theta(log n)
 *   worst case on metric instances).
 *
 * heldKarp(): exact dynamic program, O(n^2 2^n) time, O(n 2^n) space; n <= 13 here. TSP is
 *   NP-hard, which is why the exact method is only used as a ground-truth baseline.
 */
public final class MetricTsp {

    private MetricTsp() {}

    public record Edge(int a, int b, double w) {}

    public record Tour(List<Integer> order, double length) {}

    public record ApproxTrace(List<Edge> mst, double mstWeight, Tour preorder, Tour improved, int twoOptMoves) {}

    public static ApproxTrace doubleTreeTwoOpt(double[][] d, Random rng) {
        validate(d);
        List<Edge> mst = kruskal(d, rng);
        double mstWeight = mst.stream().mapToDouble(Edge::w).sum();
        List<Integer> pre = preorder(mst, d.length);
        Tour preTour = new Tour(pre, length(pre, d));
        int[] moves = new int[1];
        List<Integer> better = twoOpt(pre, d, moves);
        return new ApproxTrace(mst, mstWeight, preTour, new Tour(better, length(better, d)), moves[0]);
    }

    static List<Edge> kruskal(double[][] d, Random rng) {
        int n = d.length;
        int edgeCount = n * (n - 1) / 2;
        int[] ea = new int[edgeCount], eb = new int[edgeCount];
        double[] w = new double[edgeCount];
        int t = 0;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) { ea[t] = i; eb[t] = j; w[t] = d[i][j]; t++; }
        int[] order = new int[edgeCount];
        for (int i = 0; i < edgeCount; i++) order[i] = i;
        RandomizedSelect.quickSortIndices(order, w, rng);

        DisjointSet dsu = new DisjointSet(n);
        List<Edge> mst = new ArrayList<>(Math.max(0, n - 1));
        for (int idx : order) {
            if (dsu.union(ea[idx], eb[idx])) {
                mst.add(new Edge(ea[idx], eb[idx], w[idx]));
                if (mst.size() == n - 1) break;
            }
        }
        return mst;
    }

    static List<Integer> preorder(List<Edge> mst, int n) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (Edge e : mst) { adj.get(e.a()).add(e.b()); adj.get(e.b()).add(e.a()); }
        for (List<Integer> l : adj) Collections.sort(l);
        List<Integer> out = new ArrayList<>(n);
        boolean[] seen = new boolean[n];
        Deque<Integer> st = new ArrayDeque<>();
        st.push(0);
        while (!st.isEmpty()) {
            int u = st.pop();
            if (seen[u]) continue;  // the shortcut step: skip already-visited vertices
            seen[u] = true;
            out.add(u);
            List<Integer> nb = adj.get(u);
            for (int i = nb.size() - 1; i >= 0; i--) if (!seen[nb.get(i)]) st.push(nb.get(i));
        }
        return out;
    }

    /** First-improvement 2-opt on a closed tour that starts at node 0 (node 0 stays first). */
    static List<Integer> twoOpt(List<Integer> tour, double[][] d, int[] moves) {
        int n = tour.size();
        int[] t = tour.stream().mapToInt(Integer::intValue).toArray();
        if (n < 4) return tour;
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 1; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    int a = t[i - 1], b = t[i], c = t[j], e = t[(j + 1) % n];
                    double delta = d[a][c] + d[b][e] - d[a][b] - d[c][e];
                    if (delta < -1e-9) {
                        for (int l = i, r = j; l < r; l++, r--) { int tmp = t[l]; t[l] = t[r]; t[r] = tmp; }
                        moves[0]++;
                        improved = true;
                    }
                }
            }
        }
        List<Integer> out = new ArrayList<>(n);
        for (int v : t) out.add(v);
        return out;
    }

    public static Tour nearestNeighbour(double[][] d) {
        validate(d);
        int n = d.length;
        boolean[] seen = new boolean[n];
        List<Integer> order = new ArrayList<>(n);
        int cur = 0;
        seen[0] = true;
        order.add(0);
        for (int step = 1; step < n; step++) {
            int best = -1;
            for (int v = 0; v < n; v++) if (!seen[v] && (best < 0 || d[cur][v] < d[cur][best])) best = v;
            seen[best] = true;
            order.add(best);
            cur = best;
        }
        return new Tour(order, length(order, d));
    }

    public static Tour heldKarp(double[][] d) {
        validate(d);
        int n = d.length;
        if (n > 16) throw new IllegalArgumentException("Held-Karp limited to n <= 16");
        if (n == 1) return new Tour(List.of(0), 0);
        int full = 1 << (n - 1);                       // subsets of nodes 1..n-1
        double[][] dp = new double[full][n];
        int[][] par = new int[full][n];
        for (double[] row : dp) Arrays.fill(row, Double.POSITIVE_INFINITY);
        for (int v = 1; v < n; v++) dp[1 << (v - 1)][v] = d[0][v];
        for (int s = 1; s < full; s++) {
            for (int v = 1; v < n; v++) {
                if ((s & (1 << (v - 1))) == 0 || dp[s][v] == Double.POSITIVE_INFINITY) continue;
                for (int u = 1; u < n; u++) {
                    if ((s & (1 << (u - 1))) != 0) continue;
                    int ns = s | (1 << (u - 1));
                    double c = dp[s][v] + d[v][u];
                    if (c < dp[ns][u]) { dp[ns][u] = c; par[ns][u] = v; }
                }
            }
        }
        int last = 1;
        double best = Double.POSITIVE_INFINITY;
        for (int v = 1; v < n; v++) {
            double c = dp[full - 1][v] + d[v][0];
            if (c < best) { best = c; last = v; }
        }
        LinkedList<Integer> order = new LinkedList<>();
        for (int s = full - 1, v = last; v != 0; ) {
            order.addFirst(v);
            int pv = par[s][v];
            s &= ~(1 << (v - 1));
            v = (s == 0) ? 0 : pv;
        }
        order.addFirst(0);
        return new Tour(new ArrayList<>(order), best);
    }

    public static double length(List<Integer> order, double[][] d) {
        double s = 0;
        for (int i = 0; i < order.size(); i++) s += d[order.get(i)][order.get((i + 1) % order.size())];
        return s;
    }

    /** Rejects non-square, negative, asymmetric, or triangle-violating matrices. O(n^3). */
    public static void validate(double[][] d) {
        if (d == null || d.length == 0) throw new IllegalArgumentException("distance matrix is empty");
        int n = d.length;
        for (double[] row : d) if (row.length != n) throw new IllegalArgumentException("distance matrix not square");
        for (int i = 0; i < n; i++) {
            if (Math.abs(d[i][i]) > 1e-9) throw new IllegalArgumentException("d[i][i] must be 0");
            for (int j = 0; j < n; j++) {
                if (d[i][j] < 0 || Double.isNaN(d[i][j])) throw new IllegalArgumentException("negative distance");
                if (Math.abs(d[i][j] - d[j][i]) > 1e-9) throw new IllegalArgumentException("distance matrix not symmetric");
            }
        }
        if (n <= 60) { // full metric check is O(n^3); large benchmark instances are Euclidean by construction
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) for (int k = 0; k < n; k++)
                if (d[i][j] > d[i][k] + d[k][j] + 1e-9)
                    throw new IllegalArgumentException("triangle inequality violated at (" + i + "," + j + "," + k + ")");
        }
    }

    public static double[][] euclidean(double[] xs, double[] ys, double factor) {
        int n = xs.length;
        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
            d[i][j] = factor * Math.hypot(xs[i] - xs[j], ys[i] - ys[j]);
        return d;
    }
}
