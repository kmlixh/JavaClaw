package com.janyee.agent.infra.chat;

import com.janyee.agent.runtime.chat.PendingRunLaunch;
import com.janyee.agent.runtime.chat.PendingRunLaunchStore;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryPendingRunLaunchStore implements PendingRunLaunchStore {

    private final ConcurrentMap<String, PendingRunLaunch> launches = new ConcurrentHashMap<>();

    @Override
    public void save(PendingRunLaunch launch) {
        launches.put(launch.runId(), launch);
    }

    @Override
    public Optional<PendingRunLaunch> find(String runId) {
        return Optional.ofNullable(launches.get(runId));
    }

    @Override
    public Optional<PendingRunLaunch> consume(String runId) {
        return Optional.ofNullable(launches.remove(runId));
    }
}
