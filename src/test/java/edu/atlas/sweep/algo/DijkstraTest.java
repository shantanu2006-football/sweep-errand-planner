package edu.atlas.sweep.algo;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DijkstraTest {
    private static Dijkstra.Graph graph(int n, double[][] edges) {
        Dijkstra.Graph g = new Dijkstra.Graph(n);
        for (double[] e : edges) g.addEdge((int) e[0], (int) e[1], e[2]);
        return g;
    }

    @Test void prefersLongerPathWithFewerKm() {
        // 0-1 direct is 10, but 0-2-1 is 3
        Dijkstra.Graph g = graph(3, new double[][]{{0, 1, 10}, {0, 2, 1}, {2, 1, 2}});
        Dijkstra.Result r = Dijkstra.shortestPaths(g, 0);
        assertEquals(3, r.dist()[1], 1e-12);
        assertEquals(List.of(0, 2, 1), r.pathTo(1));
    }

    @Test void matchesFloydWarshallOnRandomGraphs() {
        Random rnd = new Random(5);
        for (int t = 0; t < 30; t++) {
            int n = 25;
            double[][] fw = new double[n][n];
            for (double[] row : fw) Arrays.fill(row, Double.POSITIVE_INFINITY);
            Dijkstra.Graph g = new Dijkstra.Graph(n);
            for (int i = 0; i < n; i++) fw[i][i] = 0;
            for (int e = 0; e < 60; e++) {
                int a = rnd.nextInt(n), b = rnd.nextInt(n); if (a == b) continue;
                double w = 1 + rnd.nextInt(20);
                g.addEdge(a, b, w);
                fw[a][b] = Math.min(fw[a][b], w); fw[b][a] = fw[a][b];
            }
            for (int k = 0; k < n; k++) for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
                if (fw[i][k] + fw[k][j] < fw[i][j]) fw[i][j] = fw[i][k] + fw[k][j];
            for (int s = 0; s < n; s++) assertArrayEquals(fw[s], Dijkstra.shortestPaths(g, s).dist(), 1e-9);
        }
    }

    @Test void unreachableIsInfiniteWithEmptyPath() {
        Dijkstra.Graph g = graph(3, new double[][]{{0, 1, 1}});
        Dijkstra.Result r = Dijkstra.shortestPaths(g, 0);
        assertTrue(Double.isInfinite(r.dist()[2]));
        assertTrue(r.pathTo(2).isEmpty());
    }

    @Test void edgeSingleVertexAndZeroWeights() {
        assertEquals(0, Dijkstra.shortestPaths(new Dijkstra.Graph(1), 0).dist()[0]);
        Dijkstra.Graph g = graph(3, new double[][]{{0, 1, 0}, {1, 2, 0}});
        assertEquals(0, Dijkstra.shortestPaths(g, 0).dist()[2]);
    }

    @Test void heapPopsInSortedOrder() {
        Dijkstra.MinHeap h = new Dijkstra.MinHeap(2);   // forces growth
        Random r = new Random(1);
        double[] keys = r.doubles(1000).toArray();
        for (int i = 0; i < keys.length; i++) h.push(keys[i], i);
        double prev = -1;
        while (!h.isEmpty()) { double k = h.peekKey(); h.pop(); assertTrue(k >= prev); prev = k; }
        assertThrows(IllegalStateException.class, h::pop);
    }

    @Test void copyDoesNotLeakEdgesIntoBase() {
        Dijkstra.Graph base = graph(2, new double[][]{{0, 1, 5}});
        Dijkstra.Graph c = base.copy();
        int v = c.addVertex(); c.addEdge(v, 0, 1); c.removeEdge(0, 1);
        assertEquals(2, base.size());
        assertEquals(5, Dijkstra.shortestPaths(base, 0).dist()[1], 1e-12);
    }

    @Test void rejectsNegativeWeightsAndBadSource() {
        assertThrows(IllegalArgumentException.class, () -> graph(2, new double[][]{{0, 1, -1}}));
        assertThrows(IllegalArgumentException.class, () -> Dijkstra.shortestPaths(new Dijkstra.Graph(2), 5));
    }
}
