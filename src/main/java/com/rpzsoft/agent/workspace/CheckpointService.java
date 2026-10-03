package com.rpzsoft.agent.workspace;

import com.rpzsoft.agent.common.Json;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

/**
 * Git-based checkpoints via JGit (no git binary required). A commit is created
 * after every agent turn and manual save boundary; restore is a hard reset that
 * preserves ignored files such as node_modules and .env.
 */
public class CheckpointService {

    private static final Logger logger = LoggerFactory.getLogger(CheckpointService.class);

    public void init(File dir) throws Exception {
        if (new File(dir, ".git").isDirectory()) return;
        Git.init().setDirectory(dir).call().close();
    }

    public boolean isRepo(File dir) {
        return new File(dir, ".git").isDirectory();
    }

    /**
     * Opens the repository, initializing it when the .git directory is missing
     * (e.g. a workspace restored or copied without its history) instead of
     * failing the whole agent run.
     */
    private Git open(File dir) throws Exception {
        if (!isRepo(dir)) {
            logger.info("workspace {} has no git repository, initializing", dir.getName());
            init(dir);
        }
        return Git.open(dir);
    }

    /** @return commit sha, or null when there was nothing to commit. */
    public String commit(File dir, String message) throws Exception {
        Git git = open(dir);
        try {
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            try {
                RevCommit commit = git.commit()
                        .setAuthor("agent", "agent@localhost")
                        .setCommitter("agent", "agent@localhost")
                        .setMessage(message)
                        .setAllowEmpty(false)
                        .call();
                return commit == null ? null : commit.getName();
            } catch (EmptyCommitException e) {
                return null;
            }
        } finally {
            git.close();
        }
    }

    public List<Json> log(File dir, int limit) throws Exception {
        java.util.List<Json> list = new java.util.ArrayList<Json>();
        Git git = open(dir);
        try {
            Iterable<RevCommit> commits = git.log().setMaxCount(limit).call();
            for (RevCommit c : commits) {
                Json j = new Json();
                j.put("sha", c.getName());
                j.put("message", c.getShortMessage());
                j.put("createdAt", c.getCommitTime() * 1000L);
                list.add(j);
            }
        } finally {
            git.close();
        }
        return list;
    }

    public String head(File dir) throws Exception {
        Git git = open(dir);
        try {
            ObjectId id = git.getRepository().resolve("HEAD");
            return id == null ? null : id.getName();
        } finally {
            git.close();
        }
    }

    public String diffFrom(File dir, String sha) throws Exception {
        Git git = open(dir);
        try {
            Repository repo = git.getRepository();
            ObjectId oldId = repo.resolve(sha + "^{tree}");
            if (oldId == null) return "";
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            ObjectReader reader = repo.newObjectReader();
            try {
                oldTree.reset(reader, oldId);
            } finally {
                reader.close();
            }
            List<DiffEntry> entries = git.diff().setOldTree(oldTree).call();
            return format(repo, entries);
        } finally {
            git.close();
        }
    }

    public String diff(File dir, String fromSha, String toSha) throws Exception {
        Git git = open(dir);
        try {
            Repository repo = git.getRepository();
            ObjectId a = repo.resolve(fromSha + "^{tree}");
            ObjectId b = repo.resolve(toSha + "^{tree}");
            if (a == null || b == null) return "";
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            ObjectReader reader = repo.newObjectReader();
            try {
                oldTree.reset(reader, a);
                newTree.reset(reader, b);
            } finally {
                reader.close();
            }
            List<DiffEntry> entries = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
            return format(repo, entries);
        } finally {
            git.close();
        }
    }

    private static String format(Repository repo, List<DiffEntry> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DiffFormatter fmt = new DiffFormatter(out);
        fmt.setRepository(repo);
        try {
            for (DiffEntry e : entries) fmt.format(e);
        } finally {
            fmt.close();
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    /**
     * The commit a checkpoint was built on, i.e. the workspace state just
     * before that turn. Returns null for a root commit or unknown sha.
     */
    public String parentOf(File dir, String sha) throws Exception {
        Git git = open(dir);
        try {
            ObjectId id = git.getRepository().resolve(sha);
            if (id == null) return null;
            RevWalk walk = new RevWalk(git.getRepository());
            try {
                RevCommit commit = walk.parseCommit(id);
                return commit.getParentCount() == 0 ? null : commit.getParent(0).getName();
            } finally {
                walk.close();
            }
        } finally {
            git.close();
        }
    }

    public void restore(File dir, String sha) throws Exception {
        Git git = open(dir);
        try {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(sha).call();
            git.clean().setCleanDirectories(true).setIgnore(true).setForce(true).call();
        } finally {
            git.close();
        }
    }

    public boolean isDirty(File dir) throws Exception {
        Git git = open(dir);
        try {
            return !git.status().call().isClean();
        } finally {
            git.close();
        }
    }
}
