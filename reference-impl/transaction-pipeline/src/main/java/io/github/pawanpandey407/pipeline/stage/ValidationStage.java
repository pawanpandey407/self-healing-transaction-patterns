package io.github.pawanpandey407.pipeline.stage;

import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Schema and business rule checks. First stage in the pipeline. */
@Component
@Order(1)
public class ValidationStage extends PipelineStage {

    public ValidationStage(PipelineProperties properties) {
        super(properties);
    }

    @Override
    public String name() {
        return "validation";
    }
}
