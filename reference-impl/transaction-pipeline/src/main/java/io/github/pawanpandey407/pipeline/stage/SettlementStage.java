package io.github.pawanpandey407.pipeline.stage;

import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Simulated settlement handoff. Third and final stage in the pipeline. */
@Component
@Order(3)
public class SettlementStage extends PipelineStage {

    public SettlementStage(PipelineProperties properties) {
        super(properties);
    }

    @Override
    public String name() {
        return "settlement";
    }
}
