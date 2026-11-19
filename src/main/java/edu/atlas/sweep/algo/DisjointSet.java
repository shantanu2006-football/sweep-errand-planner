package edu.atlas.sweep.algo;

/**
 * Disjoint-set union with union by rank + path compression (Unit 2, CO2).
 * Used by Kruskal's MST inside the TSP approximation.
 * Any sequence of m operations on n elements: O(m * alpha(n)) amortized.
 */
public final class DisjointSet {
    private final int[] parent;
    private final byte[] rank;
    private int sets;

    public DisjointSet(int n) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0");
        parent = new int[n];
        rank = new byte[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        sets = n;
    }

    public int find(int x) {
        int r = x;
        while (parent[r] != r) r = parent[r];
        while (parent[x] != r) { int nx = parent[x]; parent[x] = r; x = nx; } // compress
        return r;
    }

    /** @return true if x and y were in different sets (and are now merged). */
    public boolean union(int x, int y) {
        int a = find(x), b = find(y);
        if (a == b) return false;
        if (rank[a] < rank[b]) { int t = a; a = b; b = t; }
        parent[b] = a;
        if (rank[a] == rank[b]) rank[a]++;
        sets--;
        return true;
    }

    public int sets() { return sets; }
}
