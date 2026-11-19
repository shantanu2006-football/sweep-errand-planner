package edu.atlas.sweep.algo;

import java.util.*;

/**
 * Prefix trie over item names (Unit 4, CO3).
 *
 * Every word-start suffix of a name is inserted, so "toor dal 1kg" is reachable from
 * "toor", "dal" and "1kg". Ids are stored only at terminal nodes (storing them on every
 * node would cost O(total chars x ids) memory), and a bounded DFS collects them.
 *
 * insert:  O(L) per suffix, L = suffix length
 * suggest: O(|p| + V) where V = nodes visited by the DFS, stopped once k distinct ids are found
 * space:   O(total characters inserted) nodes
 */
public final class Trie {

    private static final class Node {
        final TreeMap<Character, Node> next = new TreeMap<>(); // sorted -> lexicographic DFS
        List<Integer> ids;                                      // ids whose suffix ends here
    }

    /** Result of a lookup plus the work it took, so the UI and benchmarks can show it. */
    public record Lookup(List<Integer> ids, int nodesVisited, String matchedPrefix) {}

    private final Node root = new Node();
    private int nodeCount = 1;
    private int size = 0;

    public void insert(String name, int id) {
        String norm = normalize(name);
        if (norm.isEmpty()) throw new IllegalArgumentException("empty name");
        size++;
        for (int start = 0; start < norm.length(); start++) {
            if (start == 0 || norm.charAt(start - 1) == ' ') insertSuffix(norm.substring(start), id);
        }
    }

    private void insertSuffix(String s, int id) {
        Node cur = root;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Node nxt = cur.next.get(c);
            if (nxt == null) {
                nxt = new Node();
                cur.next.put(c, nxt);
                nodeCount++;
            }
            cur = nxt;
        }
        if (cur.ids == null) cur.ids = new ArrayList<>(1);
        if (!cur.ids.contains(id)) cur.ids.add(id);
    }

    /** Up to k distinct ids whose name has a word starting with prefix. */
    public Lookup suggest(String prefix, int k) {
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        String p = normalize(prefix);
        if (p.isEmpty()) return new Lookup(List.of(), 0, "");
        Node cur = root;
        int visited = 0;
        for (int i = 0; i < p.length(); i++) {
            cur = cur.next.get(p.charAt(i));
            visited++;
            if (cur == null) return new Lookup(List.of(), visited, p.substring(0, i));
        }
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        visited += collect(cur, out, k);
        return new Lookup(new ArrayList<>(out), visited, p);
    }

    /** Iterative DFS (explicit stack) so very long names can't overflow the call stack. */
    private int collect(Node start, LinkedHashSet<Integer> out, int k) {
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(start);
        int visited = 0;
        while (!stack.isEmpty() && out.size() < k) {
            Node n = stack.pop();
            visited++;
            if (n.ids != null) for (int id : n.ids) { if (out.size() < k) out.add(id); }
            // push in reverse so the smallest character is popped first
            for (Node child : n.next.descendingMap().values()) stack.push(child);
        }
        return visited;
    }

    public boolean containsPrefix(String prefix) {
        String p = normalize(prefix);
        Node cur = root;
        for (int i = 0; i < p.length() && cur != null; i++) cur = cur.next.get(p.charAt(i));
        return cur != null;
    }

    public int nodeCount() { return nodeCount; }
    public int size() { return size; }

    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
