#!/usr/bin/env bash
#
# Launch the RPZ agent server.
#
# Resolves the JDK, locates the runnable jar (distribution or build tree),
# builds the classpath, applies JVM options, and forwards remaining args to
# com.rpzsoft.agent.cli.AgentServer.
#
# Usage:
#   ./run-agent.sh [java-options...] [-- <app-args...>]
#
#   java-options : any args before "--" are passed to the JVM (e.g. -Xmx512m).
#   app-args     : args after "--" go to the agent (e.g. --port 5011).
#                  With no "--", all args are forwarded to the agent.
#
# Environment:
#   JAVA              Explicit java executable to use (overrides discovery).
#   JAVA_HOME         Used to locate java when JAVA is unset.
#   JAVA_OPTS         Extra JVM options (applied before AGENT_OPTS).
#   AGENT_OPTS        Extra JVM options (applied after JAVA_OPTS).
#   AGENT_USE_CLASSPATH  Set to force -cp mode instead of -jar (see below).
#   AGENT_CONFIG      Path to an application.properties to pass as --config.
#
# Two execution modes:
#   * -jar  (default)  run the self-contained shaded jar. Its classpath is the
#                      jar itself; all dependencies are bundled.
#   * -cp            build an explicit classpath from Maven dependencies and
#                      run com.rpzsoft.agent.cli.AgentServer against target/classes.
#                      Useful when building without shade or for faster iteration.

set -euo pipefail

PROG="$(basename "$0")"

die() { printf '%s: %s\n' "$PROG" "$*" >&2; exit 1; }

# --- split JVM options from app args ---------------------------------------
JAVA_ARGS=()
APP_ARGS=()
mode="jar"
split=0
for arg in "$@"; do
    if [ "$split" -eq 0 ]; then
        case "$arg" in
            --use-classpath) mode="cp" ;;
            --)              split=1 ;;
            *)               JAVA_ARGS+=("$arg") ;;
        esac
    else
        APP_ARGS+=("$arg")
    fi
done

# --- resolve the java executable -------------------------------------------
resolve_java() {
    if [ -n "${JAVA:-}" ]; then
        command -v "$JAVA" >/dev/null 2>&1 || command -v "$JAVA" 2>/dev/null || die "JAVA='$JAVA' is not executable"
        printf '%s' "$JAVA"
        return
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        printf '%s' "${JAVA_HOME}/bin/java"
        return
    fi
    if command -v java >/dev/null 2>&1; then
        command -v java
        return
    fi
    die "no java found: set JAVA or JAVA_HOME, or put java on PATH"
}

JAVACMD="$(resolve_java)"

# Validate it actually runs and is a JDK (not just a JRE) by checking for javac.
if ! "$JAVACMD" -version >/dev/null 2>&1; then
    die "cannot execute java: $JAVACMD"
fi
if ! "$JAVACMD" -XshowSettings:properties -version >/dev/null 2>&1; then
    die "java does not support -version property query: $JAVACMD"
fi

# --- locate the project root ------------------------------------------------
# Works both from an extracted distribution (bin/run-agent.sh -> ../lib/*.jar)
# and from the source tree (bin/run-agent.sh -> ../target/*.jar).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

find_jar() {
    local j
    for j in "${ROOT_DIR}"/lib/agent-*-distribution.jar \
             "${ROOT_DIR}"/target/agent.jar \
             "${ROOT_DIR}"/target/*-jar.jar; do
        [ -f "$j" ] && { printf '%s' "$j"; return 0; }
    done
    return 1
}

# --- build the classpath for -cp mode --------------------------------------
build_classpath() {
    # Prefer a previously written classpath file from the build.
    if [ -f "${ROOT_DIR}/target/classpath.txt" ]; then
        cat "${ROOT_DIR}/target/classpath.txt"
        return
    fi
    if [ -d "${ROOT_DIR}/target/classes" ]; then
        local deps
        deps="$("$JAVACMD" -version >/dev/null 2>&1 && \
            mvn -q -o -pl . dependency:build-classpath \
                -Dmdep.outputFile="${ROOT_DIR}/target/classpath.txt" >/dev/null 2>&1 && \
            cat "${ROOT_DIR}/target/classpath.txt" 2>/dev/null)"
    fi
    # Fall back to the compiled classes dir when deps can't be resolved offline.
    if [ ! -d "${ROOT_DIR}/target/classes" ]; then
        die "target/classes not found; run 'mvn package' first"
    fi
    printf '%s' "${ROOT_DIR}/target/classes"
}

# --- assemble the command ---------------------------------------------------
if [ "$mode" = "cp" ] || [ "${AGENT_USE_CLASSPATH:-}" = "1" ]; then
    CP="$(build_classpath)"
    MAIN_CLASS="com.rpzsoft.agent.cli.AgentServer"
    CMD=(java)
    if [ -n "${AGENT_CONFIG:-}" ]; then
        CMD+=(-Drapps.config="${AGENT_CONFIG}")
    fi
    CMD+=(-cp "$CP" "$MAIN_CLASS")
else
    JAR="$(find_jar)" || die "no runnable jar found; run 'mvn package' first"
    MAIN_CLASS="$JAR"
    CMD=(java)
    if [ -n "${AGENT_CONFIG:-}" ]; then
        CMD+=(-Drapps.config="${AGENT_CONFIG}")
    fi
    CMD+=(-jar "$JAR")
fi

# --- JVM options ------------------------------------------------------------
# Sane defaults for a Netty-based HTTP server; overridable via JAVA_OPTS/AGENT_OPTS.
DEFAULT_JAVA_ARGS=(
    -Dfile.encoding=UTF-8
    -Djava.awt.headless=true
    -Djava.net.preferIPv4Stack=true
    -Duser.timezone=UTC
)

RUNTIME_ARGS=("${DEFAULT_JAVA_ARGS[@]}" ${JAVA_OPTS:-} ${AGENT_OPTS:-} "${JAVA_ARGS[@]}")

exec "${CMD[@]}" "${RUNTIME_ARGS[@]}" "${APP_ARGS[@]}"
