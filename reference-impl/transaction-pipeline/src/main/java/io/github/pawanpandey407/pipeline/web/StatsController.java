package io.github.pawanpandey407.pipeline.web;

import io.github.pawanpandey407.pipeline.metrics.PipelineMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Read-only view of the plain counters kept by PipelineMetrics. */
@RestController
public class StatsController {

    private final PipelineMetrics metrics;

    public StatsController(PipelineMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return metrics.snapshot();
    }
}
