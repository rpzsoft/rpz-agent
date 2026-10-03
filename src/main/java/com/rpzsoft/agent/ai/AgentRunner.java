package com.rpzsoft.agent.ai;

import com.rpzsoft.agent.common.ConfigRegistry;
import com.rpzsoft.agent.common.EventSink;
import com.rpzsoft.agent.common.IdGen;
import com.rpzsoft.agent.common.Json;
import com.rpzsoft.agent.common.Sanitizer;
import com.rpzsoft.agent.common.SecretMasker;
import com.rpzsoft.agent.common.UsageSink;
import com.rpzsoft.agent.sandbox.SandboxManager;
import com.rpzsoft.agent.session.SessionStore;
import com.rpzsoft.agent.workspace.CheckpointService;
import com.rpzsoft.agent.workspace.FileWorkspace;
import com.rpzsoft.agent.workspace.WorkspaceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The agent loop: assemble context, stream a model turn, execute tool calls
 * with permission gating, persist everything and checkpoint the workspace.
 *
 * Wired only to ports ({@link SessionStore}, {@link WorkspaceProvider},
 * {@link SandboxManager}, {@link EventSink}, {@link CheckpointService},
 * {@link ModelClient}, {@link SecretMasker}, {@link UsageSink}) so it has no
 * dependency on a host application.
 */
public class AgentRunner {

    private static final ConfigRegistry cfg = ConfigRegistry.getInstance();
    private static final Logger logger = LoggerFactory.getLogger(AgentRunner.class);
    /** bounded nudges when the model returns no text and no tool calls */
    private static final int MAX_EMPTY_REPLIES = 2;

    private final SessionStore store;
    private final WorkspaceProvider workspaces;
    private final SandboxManager sandboxes;
    private final EventSink events;
    private final CheckpointService checkpoints;
    private final ToolRegistry tools;
    private final PermissionEngine permissions;
    private final PermissionGate gate;
    private final ModelClient model;
    private final SecretMasker masker;
    private final UsageSink usage;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(100), new ThreadFactory() {
        private final AtomicLong ids = new AtomicLong();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "agent-" + ids.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<String, AgentSession>();
    private final Map<String, String> compacting = new ConcurrentHashMap<String, String>();
    private final Map<String, Json> pendingPermissions = new ConcurrentHashMap<String, Json>();
    private final Map<String, java.util.LinkedHashSet<String>> focusBySession =
            new ConcurrentHashMap<String, java.util.LinkedHashSet<String>>();

    public AgentRunner(SessionStore store, WorkspaceProvider workspaces, SandboxManager sandboxes,
                       EventSink events, CheckpointService checkpoints, ToolRegistry tools,
                       PermissionEngine permissions, PermissionGate gate, ModelClient model,
                       SecretMasker masker, UsageSink usage) {
        this.store = store;
        this.workspaces = workspaces;
        this.sandboxes = sandboxes;
        this.events = events == null ? EventSink.NOOP : events;
        this.checkpoints = checkpoints;
        this.tools = tools;
        this.permissions = permissions;
        this.gate = gate;
        this.model = model;
        this.masker = masker == null ? SecretMasker.NOOP : masker;
        this.usage = usage == null ? UsageSink.NOOP : usage;
    }

    /** Records the file currently open in the editor so it is included in context. */
    public void focus(String sessionId, String path) {
        if (sessionId == null || path == null) return;
        java.util.LinkedHashSet<String> set = focusBySession.get(sessionId);
        if (set == null) {
            set = new java.util.LinkedHashSet<String>();
            focusBySession.put(sessionId, set);
        }
        synchronized (set) {
            set.remove(path);
            set.add(path);
            while (set.size() > 3) {
                java.util.Iterator<String> it = set.iterator();
                it.next();
                it.remove();
            }
        }
    }

    public boolean isRunning(String sessionId) {
        AgentSession s = sessions.get(sessionId);
        return s != null && s.running;
    }

    public boolean isCompacting(String sessionId) {
        return compacting.containsKey(sessionId);
    }

    public void abort(String sessionId) {
        AgentSession s = sessions.get(sessionId);
        logger.info("abort requested session={}", sessionId);
        if (s != null) s.abort();
    }

    /** @return "started", "busy" or "not-found". */
    public String submit(String sessionId, String userId) {
        AgentSession existing = sessions.get(sessionId);
        if (existing != null && existing.running) {
            events.publish(sessionId, event("error", "sessionId", sessionId,
                    "message", "An agent run is already in progress for this session"));
            return "busy";
        }
        Json record = store.get(sessionId);
        if (record == null) return "not-found";
        if (compacting.containsKey(sessionId)) {
            events.publish(sessionId, event("error", "sessionId", sessionId,
                    "message", "Context compaction is in progress; try again in a moment"));
            return "busy";
        }
        final AgentSession session = new AgentSession(record.getString("directory"), sessionId, userId);
        sessions.put(sessionId, session);
        try {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        AgentRunner.this.run(session);
                    } catch (Throwable t2) {
                        logger.warn("agent run failed: {}", t2.getMessage(), t2);
                        events.publish(session.sessionId, event("error", "sessionId", session.sessionId,
                                "message", Sanitizer.publicError(t2)));
                    } finally {
                        session.running = false;
                        sandboxes.unbind(session.sessionId);
                        events.publish(session.sessionId, event("message.end", "sessionId", session.sessionId,
                                "status", session.aborted ? "aborted" : "done"));
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            session.running = false;
            events.publish(sessionId, event("error", "sessionId", sessionId,
                    "message", "Server is busy, please retry in a moment"));
            return "busy";
        }
        return "started";
    }

    /** Pending permission requests for a session (for `GET .../permission`). */
    public List<Json> pendingPermissions(String sessionId) {
        List<Json> out = new ArrayList<Json>();
        for (Json request : pendingPermissions.values()) {
            if (sessionId.equals(request.getString("sessionId"))) out.add(request);
        }
        return out;
    }

    /** @return true when a waiting tool call was resolved. */
    public boolean resolvePermission(String permissionId, boolean allow) {
        if (permissionId == null || !gate.isPending(permissionId)) return false;
        pendingPermissions.remove(permissionId);
        gate.resolve(permissionId, allow);
        return true;
    }

    /**
     * Folds older history into a persisted summary so future turns send less
     * context. Runs on the agent executor; progress and the result are published
     * as `compact.start`/`compact.done` events.
     *
     * @return "started" or "busy"
     */
    public String compact(String sessionId, String userId) {
        if (isRunning(sessionId)) return "busy";
        if (compacting.putIfAbsent(sessionId, sessionId) != null) return "busy";
        try {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        runCompaction(sessionId, userId);
                    } catch (Throwable t) {
                        logger.warn("compaction failed: {}", t.getMessage(), t);
                        events.publish(sessionId, event("compact.done", "sessionId", sessionId,
                                "status", "error", "message", "Context compaction failed."));
                    } finally {
                        compacting.remove(sessionId);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            compacting.remove(sessionId);
            return "busy";
        }
        return "started";
    }

    /**
     * Reverts a user message: removes it and every later message, then rolls the
     * workspace back to the state before that turn when a checkpoint exists.
     *
     * @return a Json with at least a `status` of reverted/running/compacting/not-found/not-user
     */
    public Json revert(String sessionId, String messageId, String userId) {
        Json out = new Json();
        if (isRunning(sessionId)) {
            out.put("status", "running");
            return out;
        }
        if (compacting.containsKey(sessionId)) {
            out.put("status", "compacting");
            return out;
        }
        Json record = store.get(sessionId);
        if (record == null) {
            out.put("status", "not-found");
            return out;
        }
        List<Json> messages = store.messages(sessionId);
        int targetIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).getString("id"))) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) {
            out.put("status", "not-found");
            return out;
        }
        Json target = messages.get(targetIndex);
        if (!"user".equals(target.getString("role"))) {
            out.put("status", "not-user");
            return out;
        }
        Long created = target.getLong("createdAt");
        long targetAt = created == null ? 0L : created.longValue();

        FileWorkspace workspace = workspaces.get(record.getString("directory"));
        // The first checkpoint after this message belongs to its run; the commit
        // it was built on is the workspace state before the message.
        String restoreSha = null;
        List<Json> cps = record.getJsonArray("checkpoints");
        if (cps != null) {
            for (Json cp : cps) {
                Long at = cp.getLong("createdAt");
                if (at != null && at.longValue() > targetAt) {
                    try {
                        restoreSha = checkpoints.parentOf(workspace.dir(), cp.getString("sha"));
                    } catch (Exception e) {
                        logger.warn("revert: could not resolve checkpoint parent: {}", e.getMessage());
                    }
                    break;
                }
            }
        }

        Json remaining = new Json(true);
        for (int i = 0; i < targetIndex; i++) remaining.add(messages.get(i));
        Json patch = new Json();
        patch.put("messages", remaining);
        store.update(sessionId, patch);
        int removed = messages.size() - targetIndex;

        boolean filesRestored = false;
        if (restoreSha != null) {
            try {
                sandboxes.stop(sessionId);
                checkpoints.restore(workspace.dir(), restoreSha);
                filesRestored = true;
            } catch (Exception e) {
                logger.warn("revert: restore failed: {}", e.getMessage());
            }
        }

        events.publish(sessionId, event("revert.done", "sessionId", sessionId,
                "messageId", messageId, "removedMessages", removed,
                "restored", filesRestored ? restoreSha : null));
        out.put("status", "reverted");
        out.put("removedMessages", removed);
        if (filesRestored) out.put("restored", restoreSha);
        return out;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void run(AgentSession session) throws Exception {
        final String sessionId = session.sessionId;
        FileWorkspace workspace = workspaces.get(session.directory);
        sandboxes.bind(sessionId, workspace);
        ContextBuilder context = new ContextBuilder(workspace);
        ToolContext tctx = new ToolContext(sessionId, workspace, sandboxes, events,
                session, masker);
        final ToolRegistry runTools = tools;

        List<Json> history = store.messages(sessionId);
        String userText = "";
        for (Json m : history) {
            if ("user".equals(m.getString("role"))) userText = m.getString("content");
        }

        Json messages = context.build();
        context.appendHistory(messages, history);
        context.appendContext(messages, focusFiles(sessionId));
        events.publish(sessionId, event("message.start", "sessionId", sessionId, "model", model.model()));

        boolean done = false;
        boolean persistedAssistant = false;
        int verifyAttempts = 0;
        int applyAttempts = 0;
        int emptyAttempts = 0;
        long runStart = System.currentTimeMillis();
        for (int turn = 0; !session.aborted; turn++) {
            int maxTurns = intValue("rapps.agent.maxTurns", 24);
            if (maxTurns > 0 && turn >= maxTurns) {
                events.publish(sessionId, event("error", "sessionId", sessionId,
                        "message", "Reached the " + maxTurns + "-turn limit for this run. "
                                + "Send \"continue\" to keep going."));
                break;
            }
            AssistantResult result = callModel(messages, session, runTools);
            if (session.aborted) break;
            if (result.error != null) {
                logger.warn("model call failed for {}", sessionId, result.error);
                events.publish(sessionId, event("error", "sessionId", sessionId,
                        "message", modelErrorMessage(result.error)));
                break;
            }

            Json assistant = new Json();
            assistant.put("id", IdGen.uuid());
            assistant.put("sessionId", sessionId);
            assistant.put("role", "assistant");
            assistant.put("content", masker.mask(result.text.toString()));
            Json calls = new Json(true);
            for (ToolCall c : result.toolCalls) {
                validateArguments(c, result.truncated());
                Json cj = new Json();
                cj.put("id", c.id == null ? IdGen.uuid() : c.id);
                cj.put("name", c.name);
                // never persist/send malformed arguments: providers re-parse them
                cj.put("arguments", c.invalidReason == null ? c.arguments : "{}");
                if (c.invalidReason != null) cj.put("argumentsInvalid", true);
                calls.add(cj);
            }
            assistant.put("toolCalls", calls);
            assistant.put("promptTokens", result.promptTokens);
            assistant.put("completionTokens", result.completionTokens);
            assistant.put("cachedTokens", result.cachedTokens);
            assistant.put("createdAt", System.currentTimeMillis());
            boolean emptyReply = result.toolCalls.isEmpty() && result.text.length() == 0;
            if (!emptyReply) {
                store.appendMessage(sessionId, assistant);
                persistedAssistant = true;
                messages.add(ContextBuilder.toProviderFormat(assistant));
            }
            recordUsage(sessionId, result);

            if (result.toolCalls.isEmpty()) {
                if (emptyReply) {
                    // reasoning-heavy models can burn the whole output budget before
                    // emitting anything; never treat that as task completion
                    if (emptyAttempts < MAX_EMPTY_REPLIES) {
                        emptyAttempts++;
                        Json note = new Json();
                        note.put("role", "user");
                        note.put("content", emptyReplyNote(result.truncated()));
                        messages.add(note);
                        events.publish(sessionId, event("message.retry", "sessionId", sessionId,
                                "reason", "empty-response", "attempt", emptyAttempts + 1,
                                "message", "The model returned no output"));
                        continue;
                    }
                    events.publish(sessionId, event("error", "sessionId", sessionId,
                            "message", "The model returned no output (its output budget was likely "
                                    + "consumed before any reply). Send \"continue\" or ask again."));
                    break;
                }
                // small models often answer with a code block instead of editing files;
                // push them back into the tool loop a bounded number of times
                if (applyAttempts < 2 && looksLikeEditRequest(userText)
                        && looksLikeUnappliedCode(result.text.toString())) {
                    applyAttempts++;
                    Json note = new Json();
                    note.put("role", "user");
                    note.put("content", "You replied with a code block but did not apply any changes. "
                            + "Apply it now with the tools: edit/ast_edit for existing files, write only "
                            + "for new files. Do not answer with code blocks.");
                    messages.add(note);
                    events.publish(sessionId, event("message.retry", "sessionId", sessionId,
                            "reason", "unapplied-code"));
                    continue;
                }
                assistant.put("durationMs", System.currentTimeMillis() - runStart);
                // already persisted above for a non-empty reply; update in place
                // instead of appending a duplicate (append-only store)
                store.updateMessage(sessionId, assistant);
                done = true;
                break;
            }

            for (ToolCall call : result.toolCalls) {
                if (session.aborted) break;
                executeTool(session, tctx, messages, call, result.truncated(), runTools);
            }
            if (session.aborted) break;

            List<String> errors = verifyChangedFiles(tctx);
            if (errors.isEmpty()) {
                tctx.changedFiles.clear();
            } else if (verifyAttempts < 2) {
                verifyAttempts++;
                publishVerify(sessionId, errors, "failed");
                Json note = new Json();
                note.put("role", "user");
                note.put("content", "AUTO-VERIFY FAILED after your changes:\n" + join(errors)
                        + "\nFix these syntax errors with edit/ast_edit before continuing.");
                messages.add(note);
            } else {
                publishVerify(sessionId, errors, "unresolved");
            }
        }
        if (!done && session.aborted) {
            logger.info("agent run aborted for {}", sessionId);
        }
        if (session.aborted && !persistedAssistant) {
            // marker so crash recovery does not re-run an aborted prompt
            Json marker = new Json();
            marker.put("id", IdGen.uuid());
            marker.put("sessionId", sessionId);
            marker.put("role", "assistant");
            marker.put("content", "");
            marker.put("aborted", true);
            marker.put("createdAt", System.currentTimeMillis());
            store.appendMessage(sessionId, marker);
        }

        String label = userText == null || userText.trim().isEmpty()
                ? "Agent turn" : shorten(userText.trim());
        commitCheckpoint(session, workspace, label);
    }

    private void commitCheckpoint(AgentSession session, FileWorkspace workspace, String label) {
        String sha;
        try {
            checkpoints.init(workspace.dir());
            sha = checkpoints.commit(workspace.dir(), label);
        } catch (Exception e) {
            logger.warn("checkpoint commit failed for {}: {}", session.sessionId, e.getMessage());
            events.publish(session.sessionId, event("checkpoint.error", "sessionId", session.sessionId,
                    "message", "The workspace checkpoint for this turn could not be saved."));
            return;
        }
        if (sha == null) return;
        Json record = store.get(session.sessionId);
        Json cps = new Json(true);
        if (record != null) {
            List<Json> existing = record.getJsonArray("checkpoints");
            if (existing != null) for (Json c : existing) cps.add(c);
        }
        Json cp = new Json();
        cp.put("sha", sha);
        cp.put("label", label);
        cp.put("createdAt", System.currentTimeMillis());
        cps.add(cp);
        Json patch = new Json();
        patch.put("checkpoints", cps);
        store.update(session.sessionId, patch);
        events.publish(session.sessionId, event("checkpoint.created", "sessionId", session.sessionId,
                "sha", sha, "label", label));
    }

    private void runCompaction(String sessionId, String userId) {
        events.publish(sessionId, event("compact.start", "sessionId", sessionId, "userId", userId));
        List<Json> history = store.messages(sessionId);

        ContextBuilder.CompactResult plan = ContextBuilder.plan(history);
        if (plan == null) {
            events.publish(sessionId, event("compact.done", "sessionId", sessionId,
                    "status", "nothing-to-compact", "compactedMessages", 0));
            return;
        }

        AssistantResult summaryResult = modelSummary(plan.transcript);
        boolean modelUsed = summaryResult != null && summaryResult.error == null
                && summaryResult.text.length() > 0;
        if (modelUsed) {
            plan.useModelSummary(summaryResult.text.toString());
        } else {
            logger.info("using extractive summary for session={} (model summary unavailable)", sessionId);
        }

        Json message = new Json();
        message.put("id", IdGen.uuid());
        message.put("sessionId", sessionId);
        message.put("role", "summary");
        message.put("content", plan.summary());
        message.put("compactedMessages", plan.compactedMessages);
        message.put("keptMessages", plan.keptMessages);
        message.put("tokensBefore", plan.tokensBefore);
        message.put("tokensAfter", plan.tokensAfter);
        message.put("modelSummary", modelUsed);
        message.put("createdAt", System.currentTimeMillis());
        store.appendMessage(sessionId, message);

        if (modelUsed) recordUsage(sessionId, summaryResult);

        events.publish(sessionId, event("compact.done", "sessionId", sessionId, "status", "compacted",
                "compactedMessages", plan.compactedMessages,
                "keptMessages", plan.keptMessages,
                "tokensBefore", plan.tokensBefore,
                "tokensAfter", plan.tokensAfter,
                "modelSummary", modelUsed));
    }

    /**
     * One silent, tool-less model call that summarizes the transcript. Returns
     * null on failure or empty output; callers fall back to the extractive
     * summary so compaction always succeeds.
     */
    private AssistantResult modelSummary(String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) return null;
        final AssistantResult result = new AssistantResult();
        final CountDownLatch done = new CountDownLatch(1);
        ModelStream stream = new ModelStream() {
            @Override
            public void onDelta(String text) {
                if (text != null && !text.isEmpty()) result.text.append(text);
            }

            @Override
            public void onToolCallDelta(int index, String id, String name, String argsDelta) {
                // summarization must not call tools
            }

            @Override
            public void onUsage(int promptTokens, int completionTokens) {
                result.promptTokens += promptTokens;
                result.completionTokens += completionTokens;
            }

            @Override
            public void onUsage(int promptTokens, int completionTokens, int cachedTokens) {
                result.promptTokens += promptTokens;
                result.completionTokens += completionTokens;
                result.cachedTokens += cachedTokens;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onFinishReason(String reason) {
                result.finishReason = reason;
            }

            @Override
            public void onComplete() {
                done.countDown();
            }

            @Override
            public void onError(Throwable t) {
                result.error = t;
                done.countDown();
            }
        };
        Json messages = new Json(true);
        Json system = new Json();
        system.put("role", "system");
        system.put("content", ContextBuilder.TRANSCRIPT_INSTRUCTION);
        messages.add(system);
        Json user = new Json();
        user.put("role", "user");
        user.put("content", transcript);
        messages.add(user);
        try {
            model.stream(messages, new Json(true), stream);
        } catch (Exception e) {
            logger.info("summary model call failed: {}", e.getMessage());
            return null;
        }
        if (done.getCount() > 0) done.countDown();
        try {
            if (!done.await(90, TimeUnit.SECONDS)) {
                logger.info("summary model call timed out");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (result.error != null) {
            logger.info("summary model call failed: {}", result.error.getMessage());
            return null;
        }
        return result;
    }

    /**
     * Calls the model with bounded retries for transient failures (connection
     * errors, timeouts, 429/5xx). A failed attempt emits nothing, so the retry
     * starts from a clean result.
     */
    private AssistantResult callModel(Json messages, AgentSession session, ToolRegistry runTools)
            throws Exception {
        int retries = intValue("rapps.agent.model.retries", 2);
        long baseBackoff = longValue("rapps.agent.model.retry.backoffMs", 1000L);
        int maxAttempts = Math.max(1, retries + 1);

        AssistantResult result = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            result = callModelOnce(messages, session, runTools);
            if (result.error == null) return result;
            boolean retryable = result.error instanceof ModelException
                    && ((ModelException) result.error).isRetryable()
                    && !session.aborted;
            if (!retryable || attempt >= maxAttempts) return result;

            long delay = baseBackoff * attempt;
            logger.info("model call failed ({}), retrying {}/{} in {}ms",
                    result.error.getMessage(), attempt + 1, maxAttempts, delay);
            events.publish(session.sessionId, event("message.retry", "sessionId", session.sessionId,
                    "reason", "model-error", "attempt", attempt + 1, "maxAttempts", maxAttempts,
                    "delayMs", delay, "message", "AI service error"));
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
        }
        return result;
    }

    private AssistantResult callModelOnce(Json messages, final AgentSession session,
                                          ToolRegistry runTools) throws Exception {
        final AssistantResult result = new AssistantResult();
        final CountDownLatch done = new CountDownLatch(1);
        final Map<Integer, ToolCall> calls = new TreeMap<Integer, ToolCall>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final String sessionId = session.sessionId;

        ModelStream stream = new ModelStream() {
            @Override
            public void onDelta(String text) {
                if (text == null || text.isEmpty()) return;
                result.text.append(text);
                events.publish(sessionId, event("message.delta", "sessionId", sessionId, "text", text));
            }

            @Override
            public void onToolCallDelta(int index, String id, String name, String argsDelta) {
                ToolCall c = calls.get(index);
                if (c == null) {
                    c = new ToolCall();
                    c.id = id;
                    calls.put(index, c);
                }
                if (id != null) c.id = id;
                if (name != null) c.name = name;
                if (argsDelta != null) c.arguments += argsDelta;
            }

            @Override
            public void onUsage(int promptTokens, int completionTokens) {
                result.promptTokens += promptTokens;
                result.completionTokens += completionTokens;
            }

            @Override
            public void onUsage(int promptTokens, int completionTokens, int cachedTokens) {
                result.promptTokens += promptTokens;
                result.completionTokens += completionTokens;
                result.cachedTokens += cachedTokens;
            }

            @Override
            public boolean isCancelled() {
                return session.aborted;
            }

            @Override
            public void onFinishReason(String reason) {
                result.finishReason = reason;
            }

            @Override
            public void onComplete() {
                done.countDown();
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                done.countDown();
            }
        };

        model.stream(messages, runTools.toolSchemas(), stream);
        // ModelClient.stream is synchronous and returns on completion, failure or
        // cancellation. Cancellation calls neither onComplete nor onError, so
        // release the latch here as a safety net.
        if (done.getCount() > 0) done.countDown();
        if (!done.await(240, TimeUnit.SECONDS)) {
            result.error = new RuntimeException("model request timed out");
            return result;
        }
        if (error.get() != null) {
            result.error = error.get();
            return result;
        }
        for (ToolCall c : calls.values()) {
            if (c.name != null) result.toolCalls.add(c);
        }
        return result;
    }

    private void executeTool(AgentSession session, ToolContext tctx, Json messages, ToolCall call,
                             boolean truncated, ToolRegistry runTools) {
        String sessionId = session.sessionId;
        if (call.invalidReason != null) {
            appendToolMessage(sessionId, messages, call, "ERROR: " + call.invalidReason);
            return;
        }
        Tool tool = runTools.get(call.name);
        if (tool == null) {
            appendToolMessage(sessionId, messages, call, "ERROR: unknown tool " + call.name);
            return;
        }
        Json args;
        try {
            args = call.arguments == null || call.arguments.trim().isEmpty()
                    ? new Json() : Json.fromJson(call.arguments);
        } catch (Exception e) {
            appendToolMessage(sessionId, messages, call,
                    "ERROR: could not parse tool arguments: " + e.getMessage()
                            + (truncated
                            ? " The response hit the output token limit, so the tool call was cut off. "
                            + "Keep each write under ~120 lines and extend large files with edit/ast_edit."
                            : ""));
            return;
        }

        PermissionEngine.Mode mode = permissions.check(call.name, args);
        if (mode == PermissionEngine.Mode.DENY) {
            appendToolMessage(sessionId, messages, call,
                    "ERROR: tool '" + call.name + "' is disabled by policy");
            return;
        }
        if (mode == PermissionEngine.Mode.ASK) {
            String permissionId = gate.request();
            Json request = event("permission.request", "sessionId", sessionId,
                    "permissionId", permissionId, "tool", call.name,
                    "arguments", args.toString());
            pendingPermissions.put(permissionId, request);
            events.publish(sessionId, request);
            boolean allowed = gate.await(permissionId, longValue("rapps.agent.permissions.timeoutMs", 120000L));
            pendingPermissions.remove(permissionId);
            if (!allowed) {
                appendToolMessage(sessionId, messages, call, "ERROR: permission denied by user");
                return;
            }
        }

        events.publish(sessionId, event("tool.start", "sessionId", sessionId,
                "toolCallId", call.id, "name", call.name, "arguments", args.toString()));
        tctx.toolCallId = call.id;
        String output;
        boolean ok = true;
        try {
            output = tool.execute(tctx, args);
        } catch (Exception e) {
            logger.debug("tool {} failed: {}", call.name, e.getMessage());
            output = "ERROR: " + e.getMessage();
            ok = false;
        }
        output = masker.mask(output);
        output = Sanitizer.clean(output);
        usage.onToolCall(sessionId, call.name, ok);

        String snippet = ToolOutput.truncate(output,
                ToolLimits.get().historyToolBytes(), ToolLimits.get().direction());
        events.publish(sessionId, event("tool.end", "sessionId", sessionId,
                "toolCallId", call.id, "name", call.name, "result", snippet));
        appendToolMessage(sessionId, messages, call, output);
    }

    private void appendToolMessage(String sessionId, Json messages, ToolCall call, String output) {
        Json tool = new Json();
        tool.put("id", IdGen.uuid());
        tool.put("sessionId", sessionId);
        tool.put("role", "tool");
        tool.put("toolCallId", call.id);
        tool.put("name", call.name);
        // full result stays in the tool_calls record; history gets the trimmed copy
        tool.put("content", output == null ? "" : ToolOutput.truncate(output,
                ToolLimits.get().historyToolBytes(), ToolLimits.get().direction()));
        tool.put("createdAt", System.currentTimeMillis());
        store.appendMessage(sessionId, tool);
        messages.add(ContextBuilder.toProviderFormat(tool));
    }

    private void recordUsage(String sessionId, AssistantResult result) {
        if (result.promptTokens == 0 && result.completionTokens == 0) return;
        usage.onModelCall(sessionId, model.model(), result.promptTokens, result.completionTokens,
                result.cachedTokens, 0L);
    }

    private List<String> verifyChangedFiles(ToolContext tctx) {
        List<String> errors = new ArrayList<String>();
        int checked = 0;
        for (String path : tctx.changedFiles) {
            if (checked++ >= 10) break;
            String error = ValidationService.checkJs(tctx, path);
            if (error != null) errors.add(path + ":\n" + error);
        }
        return errors;
    }

    private void publishVerify(String sessionId, List<String> errors, String status) {
        Json verify = event("verify", "sessionId", sessionId, "status", status);
        Json list = new Json(true);
        for (String error : errors) list.add(error);
        verify.put("errors", list);
        events.publish(sessionId, verify);
    }

    private java.util.Collection<String> focusFiles(String sessionId) {
        java.util.LinkedHashSet<String> set = focusBySession.get(sessionId);
        if (set == null) return java.util.Collections.emptyList();
        synchronized (set) {
            return new ArrayList<String>(set);
        }
    }

    /** Friendly, client-safe text for a failed model call. */
    static String modelErrorMessage(Throwable error) {
        if (error instanceof ModelException) {
            ModelException model = (ModelException) error;
            if (model.getStatus() == 429) {
                return "The AI service is rate-limiting requests. Please try again in a moment.";
            }
            if (model.isRetryable()) {
                return "The AI service is temporarily unavailable. Please try again in a moment.";
            }
            return "The AI service rejected this request. Check the model configuration and try again.";
        }
        return Sanitizer.publicError(error);
    }

    /** True when the reply contains a fenced code block (likely unapplied code). */
    static boolean looksLikeUnappliedCode(String text) {
        return text != null && text.contains("```");
    }

    /** Nudge used when the model returns no text and no tool calls. */
    static String emptyReplyNote(boolean truncated) {
        return truncated
                ? "Your previous response hit the output token limit before producing any text "
                + "or tool call. Continue the task now: keep reasoning short, reply briefly and "
                + "use the tools."
                : "Your previous response was empty. Continue the task now: reply briefly and "
                + "use the tools.";
    }

    /** Cheap check that the user asked for a change rather than an explanation. */
    static boolean looksLikeEditRequest(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        String[] verbs = {"make", "change", "set", "update", "add", "remove", "delete", "fix",
                "adjust", "build", "create", "redesign", "move", "rename", "replace",
                "implement", "apply", "turn", "switch", "increase", "decrease"};
        for (String verb : verbs) {
            if (lower.matches(".*\\b" + verb + "\\b.*")) return true;
        }
        return false;
    }

    static void validateArguments(ToolCall call, boolean truncated) {
        if (call.arguments == null || call.arguments.trim().isEmpty()) {
            call.arguments = "{}";
            return;
        }
        try {
            Json parsed = Json.fromJson(call.arguments);
            if (!parsed.isArray()) return;
        } catch (Exception e) {
            // fall through to the invalid reason below
        }
        call.invalidReason = "tool arguments were not valid JSON; the response was cut off"
                + (truncated ? " by the output token limit (finish_reason=length)" : "")
                + ". Keep each write under ~120 lines and extend large files with edit/ast_edit.";
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(value);
        }
        return sb.toString();
    }

    private static String shorten(String text) {
        String first = text.split("\\n")[0];
        return first.length() > 72 ? first.substring(0, 72) : first;
    }

    private static int intValue(String key, int fallback) {
        try {
            String value = cfg.getProperty(key);
            return value == null || value.trim().isEmpty() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long longValue(String key, long fallback) {
        try {
            String value = cfg.getProperty(key);
            return value == null || value.trim().isEmpty() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Json event(String type, Object... kv) {
        Json e = new Json();
        e.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) e.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return e;
    }

    static class ToolCall {
        String id;
        String name;
        String arguments = "";
        String invalidReason;
    }

    static class AssistantResult {
        final StringBuilder text = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        int promptTokens;
        int completionTokens;
        int cachedTokens;
        String finishReason;
        Throwable error;

        boolean truncated() {
            return "length".equalsIgnoreCase(finishReason);
        }
    }
}
