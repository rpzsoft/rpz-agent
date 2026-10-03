package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.IdGen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Blocks tool execution until the user approves or denies it (or the request
 * times out, which denies).
 */
public class PermissionGate {

    private final Map<String, Boolean> decisions = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, CountDownLatch> waiting = new ConcurrentHashMap<String, CountDownLatch>();

    public String request() {
        String id = IdGen.uuid();
        waiting.put(id, new CountDownLatch(1));
        return id;
    }

    public boolean await(String id, long timeoutMs) {
        CountDownLatch latch = waiting.get(id);
        if (latch == null) return false;
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Boolean decision = decisions.remove(id);
        waiting.remove(id);
        return decision != null && decision;
    }

    public void resolve(String id, boolean allow) {
        decisions.put(id, allow);
        CountDownLatch latch = waiting.get(id);
        if (latch != null) latch.countDown();
    }

    public boolean isPending(String id) {
        return waiting.containsKey(id);
    }
}
