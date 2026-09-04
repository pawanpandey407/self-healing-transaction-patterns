package io.github.pawanpandey407.pipeline.web;

import io.github.pawanpandey407.pipeline.detection.DetectionEngine;
import io.github.pawanpandey407.pipeline.detection.Verdict;
import io.github.pawanpandey407.pipeline.detection.VerdictStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Read-only view of detection verdicts (newest first) and engine liveness. */
@RestController
public class VerdictController {

    private final VerdictStore store;
    private final DetectionEngine engine;

    public VerdictController(VerdictStore store, DetectionEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    @GetMapping("/verdicts")
    public List<Verdict> verdicts() {
        return store.list();
    }

    @GetMapping("/verdicts/status")
    public Map<String, Object> status() {
        return Map.of(
                "windowsObserved", engine.windowsObserved(),
                "verdictsRecorded", store.list().size());
    }
}
