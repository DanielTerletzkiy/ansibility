package de.terletzkiy.ansibility.golden.remote

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A real git repository in a temp directory for the golden mirror's tests (plan amendment R25): made with the system
 * git, isolated from the user's configuration (no global or system config, no signing, no hooks), reached through a
 * `file://` URL, so nothing touches the network. [standard] holds the roles `web` and `db` below `roles/`, plus
 * `playbooks/` and an `ansible.cfg`.
 */
class GitTestRepo(val dir: Path) {
    /** The `file://` URL of the repository. */
    val url: String
        get() = dir.toUri().toString()

    fun write(path: String, text: String): GitTestRepo {
        val file = dir.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return this
    }

    fun commit(message: String, author: String = "alice"): String {
        git("add", "-A")
        git("-c", "user.name=$author", "-c", "user.email=$author@example.org", "commit", "-q", "-m", message)
        return head()
    }

    fun head(ref: String = "HEAD"): String = git("rev-parse", ref).trim()

    /** Lets clients ask for a blob filter (a partial clone); off by default, as on many servers. */
    fun allowFilter(allow: Boolean = true): GitTestRepo {
        git("config", "uploadpack.allowFilter", allow.toString())
        git("config", "uploadpack.allowAnySHA1InWant", allow.toString())
        return this
    }

    fun git(vararg args: String): String = run(dir, *args)

    companion object {
        /** Runs the system git in [dir] without the user's configuration; fails on a non-zero exit. */
        fun run(dir: Path, vararg args: String): String {
            val process = ProcessBuilder(listOf(gitExecutable()) + args)
                .directory(dir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .apply {
                    val env = environment()
                    env["GIT_CONFIG_NOSYSTEM"] = "1"
                    env["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    env["GIT_TERMINAL_PROMPT"] = "0"
                    env["LC_ALL"] = "C"
                    env.remove("GIT_DIR")
                    env.remove("GIT_WORK_TREE")
                }
                .start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS)) { "git ${args.toList()} timed out" }
            check(process.exitValue() == 0) { "git ${args.toList()} failed (${process.exitValue()}): $err" }
            return out
        }

        fun gitExecutable(): String =
            listOf("/usr/local/bin/git", "/opt/homebrew/bin/git", "/usr/bin/git").firstOrNull { File(it).canExecute() } ?: "git"

        /** An empty repository whose default branch is [branch]. */
        fun init(dir: Path, branch: String = "main"): GitTestRepo {
            Files.createDirectories(dir)
            run(dir, "init", "-q", "--initial-branch=$branch")
            run(dir, "config", "commit.gpgsign", "false")
            run(dir, "config", "tag.gpgsign", "false")
            return GitTestRepo(dir)
        }

        /** The standard golden repository: `roles/web`, `roles/db`, `playbooks/site.yml`, `ansible.cfg`, one commit. */
        fun standard(dir: Path, branch: String = "main"): GitTestRepo = init(dir, branch)
            .write("roles/web/tasks/main.yml", "- name: web\n  debug: msg=web\n")
            .write("roles/web/defaults/main.yml", "web_port: 80\n")
            .write("roles/db/tasks/main.yml", "- name: db\n  debug: msg=db\n")
            .write("playbooks/site.yml", "- hosts: all\n  roles: [web, db]\n")
            .write("ansible.cfg", "[defaults]\nroles_path = ./roles\n")
            .also { it.commit("golden: first", author = "alice") }

        /** `git rev-list --count HEAD` in [dir]: the commits a (shallow) clone holds. */
        fun revCount(dir: Path): Int = run(dir, "rev-list", "--count", "HEAD").trim().toInt()

        /** The files below [dir] (relative, `/`-separated), without `.git`. */
        fun files(dir: Path): List<String> = Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) && ".git" !in dir.relativize(it).map { part -> part.toString() } }
                .map { dir.relativize(it).toString().replace(File.separatorChar, '/') }
                .sorted()
                .toList()
        }
    }
}
