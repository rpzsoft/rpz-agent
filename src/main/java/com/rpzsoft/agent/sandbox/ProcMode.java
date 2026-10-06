package com.rpzsoft.agent.sandbox;

/**
 * How a {@link ChrootSandboxProvider} exposes /proc to the sandboxed shell.
 *
 * <p>The work directory is the hard boundary (the shell runs inside a chroot);
 * this only controls what the shell sees under /proc, i.e. whether it can observe
 * running processes. /proc is live and sensitive (other PIDs, memory maps,
 * environment), so the defaults lean toward the least exposure.</p>
 */
public enum ProcMode {

    /** Empty /proc (tmpfs) inside an isolated mount namespace — sees no processes. */
    DENY,

    /** Fresh /proc in a new PID namespace — sees only this sandbox's own processes. */
    NAMESPACE,

    /** Expose the host /proc — sees every host process (leaky; opt-in). */
    HOST,

    /** Expose exactly one host PID via bind-mount. */
    BIND,

    /** No chroot/namespace — a plain local process (legacy behaviour). */
    OFF;

    /**
     * Parses a config value. Unknown values fall back to {@link #DENY} (safe).
     * {@code bind:<pid>} selects {@link #BIND} with the given PID.
     */
    public static ProcMode from(String value) {
        if (value == null) return DENY;
        String v = value.trim();
        if (v.isEmpty()) return DENY;
        switch (v.toLowerCase()) {
            case "deny":
                return DENY;
            case "namespace":
            case "own":
                return NAMESPACE;
            case "host":
                return HOST;
            case "off":
            case "none":
            case "process":
                return OFF;
            default:
                break;
        }
        if (v.startsWith("bind:")) {
            String pid = v.substring("bind:".length()).trim();
            try {
                Integer.parseInt(pid);
                return BIND;
            } catch (NumberFormatException e) {
                return DENY;
            }
        }
        return DENY;
    }
}
