package io.github.pawanpandey407.pipeline.stage;

import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Simulated issuer authorization. Second stage in the pipeline.
 *
 * Carries the highest baseline failure probability because a small
 * steady decline rate is normal in production authorization traffic.
 * Detection work must separate that baseline from real trouble.
 */
@Component
@Order(2)
public class AuthorizationStage extends PipelineStage {

    public AuthorizationStage(PipelineProperties properties) {
        super(properties);
    }

    @Override
    public String name() {
        return "authorization";
    }
}
