package io.github.pawanpandey407.pipeline;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({PipelineProperties.class, DetectionProperties.class})
public class PipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}
