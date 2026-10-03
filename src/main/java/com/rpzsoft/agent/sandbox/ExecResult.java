package com.rpzsoft.agent.sandbox;

public class ExecResult {

    public int exitCode;
    public String stdout = "";
    public String stderr = "";
    public boolean timedOut;
    public boolean aborted;

    public boolean ok() {
        return !timedOut && !aborted && exitCode == 0;
    }

    public String output() {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isEmpty()) sb.append(stdout);
        if (stderr != null && !stderr.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(stderr);
        }
        return sb.toString();
    }
}
