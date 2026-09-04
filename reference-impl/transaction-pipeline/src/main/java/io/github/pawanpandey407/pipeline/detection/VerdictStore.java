package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * Bounded in-memory verdict log, newest first, mirrored to the app log.
 *
 * A persisting condition re-alarms only after the cooldown, not every
 * window; alert channels that repeat themselves every few seconds get
 * muted by the humans reading them, which defeats the point.
 */
@Component
public class VerdictStore {

    private static final Logger log = LoggerFactory.getLogger(VerdictStore.class);
    private static final int CAPACITY = 200;

    private final DetectionProperties props;
    private final ConcurrentLinkedDeque<Verdict> verdicts = new ConcurrentLinkedDeque<>();
    private final ConcurrentMap<String, Long> lastEmittedMs = new ConcurrentHashMap<>();

    public VerdictStore(DetectionProperties props) {
        this.props = props;
    }

    /** Returns true if the verdict was recorded, false if cooled down. */
    public boolean add(Verdict verdict) {
        String key = verdict.primitive() + ":" + verdict.subject();
        long now = System.currentTimeMillis();
        Long last = lastEmittedMs.get(key);
        if (last != null && now - last < props.getCooldownSeconds() * 1000) {
            return false;
        }
        lastEmittedMs.put(key, now);
        log.warn("VERDICT [{}] {} observed={} baseline={} : {}",
                verdict.primitive(), verdict.subject(),
                String.format("%.4f", verdict.observed()),
                String.format("%.4f", verdict.baselineMean()),
                verdict.message());
        verdicts.addFirst(verdict);
        while (verdicts.size() > CAPACITY) {
            verdicts.pollLast();
        }
        return true;
    }

    public List<Verdict> list() {
        return Collections.unmodifiableList(new ArrayList<>(verdicts));
    }
}
