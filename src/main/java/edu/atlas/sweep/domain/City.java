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
     * Grid -> approximate real coordinates over Powai, Mumbai (for map hand-off only).
     * x = 0..8 km east maps to lng 72.880..72.956; y = 0..6 km south maps to lat 19.135..19.081.
     */
    public static final double LAT0 = 19.135, LNG0 = 72.880, KM_PER_DEG_LAT = 110.57, KM_PER_DEG_LNG = 105.2;

    public static double lat(double y) { return Math.round((LAT0 - y / KM_PER_DEG_LAT) * 1e6) / 1e6; }
    public static double lng(double x) { return Math.round((LNG0 + x / KM_PER_DEG_LNG) * 1e6) / 1e6; }

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
