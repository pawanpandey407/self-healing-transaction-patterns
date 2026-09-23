package io.github.pawanpandey407.pipeline.web;

import io.github.pawanpandey407.pipeline.recovery.ClientQuarantine;
import io.github.pawanpandey407.pipeline.recovery.RecoveryEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only view of recovery decisions and quarantine episodes, newest first. */
@RestController
public class RecoveryController {

    private final RecoveryEngine engine;
    private final ClientQuarantine quarantine;

    public RecoveryController(RecoveryEngine engine, ClientQuarantine quarantine) {
        this.engine = engine;
        this.quarantine = quarantine;
    }

    @GetMapping("/recovery")
    public Map<String, Object> recovery() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("episodes", quarantine.views());
        out.put("decisions", engine.decisions());
        return out;
    }
}
