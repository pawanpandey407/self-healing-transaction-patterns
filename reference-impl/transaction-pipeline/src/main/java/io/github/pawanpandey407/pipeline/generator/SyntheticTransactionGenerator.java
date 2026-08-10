package io.github.pawanpandey407.pipeline.generator;

import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import io.github.pawanpandey407.pipeline.core.TransactionPipeline;
import io.github.pawanpandey407.pipeline.model.Transaction;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Emits synthetic transactions at a fixed interval.
 *
 * The steady rate is deliberate: it gives detection work a known
 * baseline, so any drop in throughput or shift in the failure mix is
 * signal, not noise from an uneven load generator.
 */
@Component
public class SyntheticTransactionGenerator {

    private final PipelineProperties properties;
    private final TransactionPipeline pipeline;

    public SyntheticTransactionGenerator(PipelineProperties properties, TransactionPipeline pipeline) {
        this.properties = properties;
        this.pipeline = pipeline;
    }

    @Scheduled(fixedRateString = "${pipeline.generator.interval-ms}")
    public void generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String clientId = "client-" + (random.nextInt(properties.getGenerator().getClients()) + 1);
        BigDecimal amount = BigDecimal.valueOf(random.nextDouble(1.00, 500.00))
                .setScale(2, RoundingMode.HALF_UP);
        pipeline.process(new Transaction(clientId, amount));
    }
}
