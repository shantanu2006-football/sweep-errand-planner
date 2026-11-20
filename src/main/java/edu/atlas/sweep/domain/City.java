package edu.atlas.sweep.domain;

import edu.atlas.sweep.algo.BloomFilter;
import edu.atlas.sweep.algo.Trie;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The loaded dataset: items, stores, inventory, plus the two indexes built over it
 * (the item-name trie and one Bloom filter per store).
 */
public final class City {

    public record Item(int id, String name, String category, int basePrice) {}

    public record Store(int id, String name, String type, String area, double x, double y, int open, int close) {
        /** Open at hour h (0-23). close == 24 means open past midnight / 24h. */
        public boolean openAt(int h) { return h >= open && h < close; }
    }

    public record Listing(int price, int stock) {}

    /** Road-detour factor applied to straight-line distance (documented assumption). */
    public static final double ROAD_FACTOR = 1.3;
    public static final double WIDTH_KM = 8.0, HEIGHT_KM = 6.0;
    public static final double BLOOM_FP_TARGET = 0.01;

    private final Map<Integer, Item> items = new LinkedHashMap<>();
    private final Map<Integer, Store> stores = new LinkedHashMap<>();
    private final Map<Integer, Map<Integer, Listing>> inventory = new HashMap<>(); // store -> item -> listing
    private final Map<Integer, BloomFilter> blooms = new HashMap<>();
    private final Map<String, Integer> byName = new HashMap<>();
    private final Trie trie = new Trie();
    private RoadNetwork roads;

    public static City load(Path dir, long seed) {
        City c = new City();
        for (String[] r : csv(dir.resolve("items.csv"))) {
            Item it = new Item(Integer.parseInt(r[0]), r[1], r[2], Integer.parseInt(r[3]));
            c.items.put(it.id(), it);
            c.byName.put(Trie.normalize(it.name()), it.id());
            c.trie.insert(it.name(), it.id());
        }
        for (String[] r : csv(dir.resolve("stores.csv"))) {
            Store s = new Store(Integer.parseInt(r[0]), r[1], r[2], r[3], Double.parseDouble(r[4]),
                    Double.parseDouble(r[5]), Integer.parseInt(r[6]), Integer.parseInt(r[7]));
            c.stores.put(s.id(), s);
        }
        for (String[] r : csv(dir.resolve("inventory.csv"))) {
            int sid = Integer.parseInt(r[0]), iid = Integer.parseInt(r[1]);
            if (!c.stores.containsKey(sid) || !c.items.containsKey(iid))
                throw new IllegalStateException("inventory row references unknown store/item: " + String.join(",", r));
            c.inventory.computeIfAbsent(sid, k -> new HashMap<>())
                    .put(iid, new Listing(Integer.parseInt(r[2]), Integer.parseInt(r[3])));
        }
        c.roads = RoadNetwork.load(dir.resolve("roads.json"), c.stores.values());
        Random rng = new Random(seed);
        for (Store s : c.stores.values()) {
            Map<Integer, Listing> inv = c.inventory.getOrDefault(s.id(), Map.of());
            BloomFilter bf = new BloomFilter(Math.max(1, inv.size()), BLOOM_FP_TARGET, rng);
            for (int iid : inv.keySet()) bf.add(key(iid));
            c.blooms.put(s.id(), bf);
        }
        return c;
    }

    /** Bloom keys are item ids as strings: stable, compact and independent of display names. */
    public static String key(int itemId) { return "item:" + itemId; }

    private static List<String[]> csv(Path p) {
        try {
            List<String> lines = Files.readAllLines(p);
            List<String[]> out = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) if (!lines.get(i).isBlank()) out.add(lines.get(i).split(",", -1));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read dataset file " + p.toAbsolutePath(), e);
        }
    }

    /*
     * Grid km -> real Powai coordinates, used only for the Google Maps hand-off.
     *
     * The drawn map is schematic (about 2.5x more spread out than real Powai), so a single
     * linear transform cannot fit it. We anchor 7 named areas to their real coordinates:
     *   1. least-squares affine fit grid -> (lat, lng) over the anchors
     *   2. inverse-distance-weighted correction of the anchors' residuals (weights 1/d^2),
     *      so every anchor maps exactly and points between them blend smoothly
     *   3. anything that lands inside the real Powai Lake is pushed out to the shore
     */
    private static final double[][] ANCHORS = {   // grid x, grid y, lat, lng
        {2.36, 2.97, 19.1272, 72.9046},   // Powai Lake (centre)
        {2.95, 0.95, 19.1334, 72.9133},   // IIT Bombay
        {4.95, 3.28, 19.1176, 72.9106},   // Hiranandani Gardens
        {6.75, 2.05, 19.1102, 72.8970},   // Chandivali
        {0.62, 3.95, 19.1296, 72.9283},   // Kanjurmarg West
        {7.10, 3.00, 19.1170, 72.8910},   // Raheja Vihar
        {6.40, 4.60, 19.1060, 72.8880},   // Saki Vihar Road
    };
    private static final double[] LAT_FIT = fit(2), LNG_FIT = fit(3);
    static final double LAKE_LAT = 19.1265, LAKE_LNG = 72.9050, LAKE_RY_KM = 0.83, LAKE_RX_KM = 0.84;

    public static double lat(double x, double y) { return toLatLng(x, y)[0]; }
    public static double lng(double x, double y) { return toLatLng(x, y)[1]; }

    public static double[] toLatLng(double x, double y) {
        double la = LAT_FIT[0] + LAT_FIT[1] * x + LAT_FIT[2] * y;
        double ln = LNG_FIT[0] + LNG_FIT[1] * x + LNG_FIT[2] * y;
        double wsum = 0, dla = 0, dln = 0;
        for (double[] a : ANCHORS) {
            double d2 = (a[0] - x) * (a[0] - x) + (a[1] - y) * (a[1] - y);
            double rla = a[2] - (LAT_FIT[0] + LAT_FIT[1] * a[0] + LAT_FIT[2] * a[1]);
            double rln = a[3] - (LNG_FIT[0] + LNG_FIT[1] * a[0] + LNG_FIT[2] * a[1]);
            if (d2 < 1e-12) { dla = rla; dln = rln; wsum = 1; break; }
            double w = 1 / d2;
            wsum += w; dla += w * rla; dln += w * rln;
        }
        la += dla / wsum; ln += dln / wsum;
        // keep shops and homes out of the water: push to 8% beyond the lake's elliptical shore
        double ey = (la - LAKE_LAT) * KM_PER_DEG_LAT / LAKE_RY_KM, ex = (ln - LAKE_LNG) * KM_PER_DEG_LNG / LAKE_RX_KM;
        double r = Math.hypot(ex, ey);
        if (r < 1.08 && !(Math.abs(x - ANCHORS[0][0]) < 1e-9 && Math.abs(y - ANCHORS[0][1]) < 1e-9)) {
            double k = r < 1e-9 ? 0 : 1.08 / r;
            if (r < 1e-9) { ex = 1.08; ey = 0; k = 1; }
            la = LAKE_LAT + ey * k * LAKE_RY_KM / KM_PER_DEG_LAT;
            ln = LAKE_LNG + ex * k * LAKE_RX_KM / KM_PER_DEG_LNG;
        }
        return new double[]{Math.round(la * 1e6) / 1e6, Math.round(ln * 1e6) / 1e6};
    }

    /** Least-squares plane t = c0 + c1 x + c2 y over the anchors (3x3 normal equations). */
    private static double[] fit(int col) {
        double[][] m = new double[3][4];
        for (double[] a : ANCHORS) {
            double[] r = {1, a[0], a[1]};
            for (int i = 0; i < 3; i++) { for (int j = 0; j < 3; j++) m[i][j] += r[i] * r[j]; m[i][3] += r[i] * a[col]; }
        }
        for (int c = 0; c < 3; c++) {                    // Gauss-Jordan with partial pivoting
            int p = c;
            for (int r = c + 1; r < 3; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            double[] t = m[c]; m[c] = m[p]; m[p] = t;
            for (int r = 0; r < 3; r++) if (r != c) {
                double f = m[r][c] / m[c][c];
                for (int k = c; k < 4; k++) m[r][k] -= f * m[c][k];
            }
        }
        return new double[]{m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2]};
    }

    static final double KM_PER_DEG_LAT = 110.57, KM_PER_DEG_LNG = 105.2;

    public double distance(double x1, double y1, double x2, double y2) {
        return ROAD_FACTOR * Math.hypot(x1 - x2, y1 - y2);
    }

    public Collection<Item> items() { return items.values(); }
    public Collection<Store> stores() { return stores.values(); }
    public Item item(int id) { return items.get(id); }
    public Store store(int id) { return stores.get(id); }
    public Optional<Integer> itemIdByName(String name) { return Optional.ofNullable(byName.get(Trie.normalize(name))); }
    public Listing listing(int storeId, int itemId) { return inventory.getOrDefault(storeId, Map.of()).get(itemId); }
    public Map<Integer, Listing> inventoryOf(int storeId) { return inventory.getOrDefault(storeId, Map.of()); }
    public BloomFilter bloom(int storeId) { return blooms.get(storeId); }
    public Trie trie() { return trie; }
    public RoadNetwork roads() { return roads; }

    /** Every price for an item across all stores (input for quickselect medians / deal scores). */
    public double[] pricesOf(int itemId) {
        return inventory.values().stream().map(m -> m.get(itemId)).filter(Objects::nonNull)
                .mapToDouble(Listing::price).toArray();
    }
}
