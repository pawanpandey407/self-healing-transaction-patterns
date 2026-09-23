package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.config.RecoveryProperties;
import io.github.pawanpandey407.pipeline.core.AdmissionGate;
import io.github.pawanpandey407.pipeline.detection.Verdict;
import io.github.pawanpandey407.pipeline.model.Transaction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * The admission gate that holds an isolated client's new transactions.
 *
 * Every other client passes straight through, which is the point of the
 * action: a client-specific fault, such as a broken client configuration
 * or a client-specific vendor path, must not consume shared capacity or
 * stop anyone else's processing.
 */
@Component
public class ClientQuarantine implements AdmissionGate {

    private static final int HISTORY = 50;

    private final RecoveryProperties props;
    private final ConcurrentMap<String, QuarantineEpisode> active = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<QuarantineEpisode> closed = new ConcurrentLinkedDeque<>();

    public ClientQuarantine(RecoveryProperties props) {
        this.props = props;
    }

    @Override
    public boolean admit(Transaction transaction) {
        QuarantineEpisode episode = active.get(transaction.getClientId());
        if (episode == null) {
            return true;
        }
        // NOT_ACTIVE means the episode closed between the lookup and the
        // hold; the client is healthy again, so process normally.
        return episode.hold(transaction, props.getQuarantineCapacity()) == QuarantineEpisode.HoldResult.NOT_ACTIVE;
    }

    public boolean isIsolated(String clientId) {
        return active.containsKey(clientId);
    }

    public int isolatedCount() {
        return active.size();
    }

    /** Opens an episode unless the client is already isolated or the cap is reached. */
    synchronized Optional<QuarantineEpisode> open(String clientId, Verdict trigger,
                                                  long restProcessed, long restFailed) {
        if (active.containsKey(clientId) || active.size() >= props.getMaxIsolatedClients()) {
            return Optional.empty();
        }
        QuarantineEpisode episode = new QuarantineEpisode(clientId, trigger, restProcessed, restFailed);
        active.put(clientId, episode);
        return Optional.of(episode);
    }

    List<QuarantineEpisode> activeEpisodes() {
        return new ArrayList<>(active.values());
    }

    Optional<QuarantineEpisode> episodeFor(String clientId) {
        return Optional.ofNullable(active.get(clientId));
    }

    /**
     * Moves an episode from active to closed. Shares the lock with views(),
     * so a snapshot never catches it between the two and misses it.
     */
    synchronized void retire(QuarantineEpisode episode) {
        active.remove(episode.clientId(), episode);
        closed.addFirst(episode);
        while (closed.size() > HISTORY) {
            closed.pollLast();
        }
    }

    /** Active episodes first, then closed ones, newest first. */
    public synchronized List<QuarantineEpisode.View> views() {
        List<QuarantineEpisode.View> out = new ArrayList<>();
        active.values().forEach(e -> out.add(e.view()));
        closed.forEach(e -> out.add(e.view()));
        return out;
    }
}
