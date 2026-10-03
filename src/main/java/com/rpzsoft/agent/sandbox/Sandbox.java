package com.rpzsoft.agent.sandbox;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A command-execution environment rooted at a workspace directory. Providers
 * may keep it as a plain shell, a container or a microVM.
 */
public class Sandbox {

    public enum State {
        STOPPED, STARTING, RUNNING, FAILED
    }

    private final String sessionId;
    private final File dir;
    private State state = State.STOPPED;
    private Process process;
    private long lastActivity = System.currentTimeMillis();
    private final Map<String, String> env = new LinkedHashMap<String, String>();

    public Sandbox(String sessionId, File dir) {
        this.sessionId = sessionId;
        this.dir = dir;
    }

    public String getSessionId() {
        return sessionId;
    }

    public File getDir() {
        return dir;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Process getProcess() {
        return process;
    }

    public void setProcess(Process process) {
        this.process = process;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public void touch() {
        this.lastActivity = System.currentTimeMillis();
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }
}
