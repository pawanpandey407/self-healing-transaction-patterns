package io.github.pawanpandey407.pipeline.web;

import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Runtime failure injection.
 *
 * Injection scenarios need to break things after the detection module
 * has learned healthy baselines, which startup configuration cannot do.
 * This controller mutates the live stage settings; it is a lab tool for
 * the reference pipeline, not a pattern for production configuration.
 */
@RestController
@RequestMapping("/inject")
public class InjectionController {

    private final PipelineProperties properties;

    public InjectionController(PipelineProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public Map<String, PipelineProperties.Stage> current() {
        return properties.getStages();
    }

    @PostMapping("/stage/{stage}")
    public ResponseEntity<PipelineProperties.Stage> setStageFailure(
            @PathVariable String stage,
            @RequestParam double probability) {
        PipelineProperties.Stage settings = properties.getStages().get(stage);
        if (settings == null) {
            return ResponseEntity.notFound().build();
        }
        settings.setFailureProbability(probability);
        return ResponseEntity.ok(settings);
    }

    @PostMapping("/stage/{stage}/client/{clientId}")
    public ResponseEntity<PipelineProperties.Stage> setClientFailure(
            @PathVariable String stage,
            @PathVariable String clientId,
            @RequestParam double probability) {
        PipelineProperties.Stage settings = properties.getStages().get(stage);
        if (settings == null) {
            return ResponseEntity.notFound().build();
        }
        settings.getClientFailureProbability().put(clientId, probability);
        return ResponseEntity.ok(settings);
    }

    @DeleteMapping("/stage/{stage}/client/{clientId}")
    public ResponseEntity<PipelineProperties.Stage> clearClientFailure(
            @PathVariable String stage,
            @PathVariable String clientId) {
        PipelineProperties.Stage settings = properties.getStages().get(stage);
        if (settings == null) {
            return ResponseEntity.notFound().build();
        }
        settings.getClientFailureProbability().remove(clientId);
        return ResponseEntity.ok(settings);
    }

    @PostMapping("/stage/{stage}/latency")
    public ResponseEntity<PipelineProperties.Stage> setStageLatency(
            @PathVariable String stage,
            @RequestParam long minMs,
            @RequestParam long maxMs) {
        PipelineProperties.Stage settings = properties.getStages().get(stage);
        if (settings == null) {
            return ResponseEntity.notFound().build();
        }
        settings.setMinLatencyMs(minMs);
        settings.setMaxLatencyMs(maxMs);
        return ResponseEntity.ok(settings);
    }
}
