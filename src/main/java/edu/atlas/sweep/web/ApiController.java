package edu.atlas.sweep.web;

import edu.atlas.sweep.algo.BloomFilter;
import edu.atlas.sweep.algo.Trie;
import edu.atlas.sweep.domain.City;
import edu.atlas.sweep.service.BenchmarkService;
import edu.atlas.sweep.service.ListParser;
import edu.atlas.sweep.service.PlannerService;
import edu.atlas.sweep.web.Api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final City city;
    private final PlannerService planner;
    private final BenchmarkService bench;
    private final ListParser parser;

    public ApiController(City city, PlannerService planner, BenchmarkService bench, ListParser parser) {
        this.city = city;
        this.planner = planner;
        this.bench = bench;
        this.parser = parser;
    }

    /** Free-text list -> catalogue items (trie candidates, KMP scoring, Hinglish/brand aliases). */
    @PostMapping("/parse")
    public ParseResponse parse(@RequestBody(required = false) ParseRequest req) {
        return parser.parse(req == null ? null : req.text());
    }

    /** Autocomplete: trie lookup, timed against a linear scan so the UI can show the gap. */
    @GetMapping("/suggest")
    public SuggestResponse suggest(@RequestParam("q") String q, @RequestParam(value = "k", defaultValue = "8") int k) {
        if (k < 1 || k > 25) throw new IllegalArgumentException("k must be 1-25");
        if (q.length() > 60) throw new IllegalArgumentException("query too long (max 60 characters)");
        long t0 = System.nanoTime();
        Trie.Lookup lk = city.trie().suggest(q, k);
        long trieNs = System.nanoTime() - t0;
        String p = Trie.normalize(q);
        long t1 = System.nanoTime();
        int scanHits = 0;
        for (City.Item it : city.items()) {
            String nm = Trie.normalize(it.name());
            if (!p.isEmpty() && (nm.startsWith(p) || nm.contains(" " + p)) && ++scanHits >= k) break;
        }
        long scanNs = System.nanoTime() - t1;
        List<Suggestion> out = lk.ids().stream().map(id -> {
            City.Item it = city.item(id);
            double[] prices = city.pricesOf(id);
            int min = (int) Arrays.stream(prices).min().orElse(0);
            return new Suggestion(id, it.name(), it.category(), min, prices.length);
        }).toList();
        return new SuggestResponse(q, out, lk.nodesVisited(), trieNs, scanNs, city.trie().nodeCount());
    }

    @GetMapping("/stores")
    public List<StoreDto> stores() {
        return city.stores().stream().map(s -> {
            BloomFilter bf = city.bloom(s.id());
            return new StoreDto(s.id(), s.name(), s.type(), s.area(), s.x(), s.y(), s.open(), s.close(),
                    city.inventoryOf(s.id()).size(), bf.m(), bf.k(), bf.sizeBytes(), City.lat(s.y()), City.lng(s.x()));
        }).toList();
    }

    @GetMapping("/items")
    public List<ItemDto> items() {
        return city.items().stream().map(i -> new ItemDto(i.id(), i.name(), i.category(), i.basePrice())).toList();
    }

    @PostMapping("/plan")
    public PlanResponse plan(@RequestBody(required = false) PlanRequest req) {
        return planner.plan(req);
    }

    /** Shows exactly which bits one Bloom lookup touched, and whether the exact check agrees. */
    @GetMapping("/bloom")
    public BloomProbe bloom(@RequestParam("store") int storeId, @RequestParam("item") int itemId) {
        City.Store s = city.store(storeId);
        City.Item it = city.item(itemId);
        if (s == null) throw new NoSuchElementException("no store with id " + storeId);
        if (it == null) throw new NoSuchElementException("no item with id " + itemId);
        BloomFilter bf = city.bloom(storeId);
        int[] pos = bf.positions(City.key(itemId));
        boolean[] set = new boolean[pos.length];
        for (int i = 0; i < pos.length; i++) set[i] = bf.isBitSet(pos[i]);
        boolean maybe = bf.mightContain(City.key(itemId));
        boolean stocked = city.listing(storeId, itemId) != null;
        String verdict = !maybe ? "definitely not stocked (a bit is 0)"
                : stocked ? "possibly stocked, confirmed by inventory" : "false positive, rejected by inventory check";
        StringBuilder bits = new StringBuilder(bf.m());
        for (int i = 0; i < bf.m(); i++) bits.append(bf.isBitSet(i) ? '1' : '0');
        return new BloomProbe(storeId, s.name(), itemId, it.name(), bf.m(), bf.k(), pos, set, maybe, stocked, verdict,
                bits.toString(), bf.bitCount(), (double) bf.bitCount() / bf.m());
    }

    @GetMapping("/bench/{name}")
    public Map<String, Object> bench(@PathVariable String name, @RequestParam(value = "fresh", defaultValue = "false") boolean fresh) {
        long t = System.nanoTime();
        List<Map<String, Object>> rows = bench.run(name, fresh);
        return Map.of("name", name, "rows", rows, "elapsedMs", (System.nanoTime() - t) / 1_000_000);
    }

    // ------------------------------------------------------------ error handling
    @ExceptionHandler(PlannerService.ValidationException.class)
    public ResponseEntity<ApiError> invalid(PlannerService.ValidationException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage(), e.details));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badArg(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("Invalid request", List.of(e.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiError("Request body is not valid JSON for a trip",
                List.of("Check field types: home {x,y} numbers, items [{name, qty}], hour integer")));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException e) {
        return ResponseEntity.status(404).body(new ApiError("Not found", List.of(e.getMessage())));
    }
}
