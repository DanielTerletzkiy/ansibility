package de.terletzkiy.ansibility.run

import com.intellij.openapi.util.io.NioFiles
import de.terletzkiy.ansibility.run.events.RunCallback
import de.terletzkiy.ansibility.semantics.vault.LabelledSecret
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

/**
 * The secrets of one playbook run. No password touches the disk: they are handed to the child process in
 * [environment], and [dir] (owner-only) holds two scripts that print them, a vault password client for
 * `--vault-id`/`ANSIBLE_VAULT_PASSWORD_FILE` and a become password script for `--become-password-file`. When no vault
 * id is unlocked but a Compose service mounts a vault password file, [vaultPlaceholder] stands in for it (Ansible
 * reads that file at startup; the placeholder prints a fixed non-secret word, so only vaulted values fail). [close]
 * deletes the directory once the process ends.
 */
class RunSecrets private constructor(
    val dir: Path?,
    val vaultLabels: List<String>,
    val hasBecomePassword: Boolean,
    val environment: Map<String, String>,
    private val hasPlaceholder: Boolean = false,
    private val hasCallback: Boolean = false,
) : AutoCloseable {
    val vaultClient: Path? get() = dir?.takeIf { vaultLabels.isNotEmpty() }?.resolve(VAULT_CLIENT)
    val becomeScript: Path? get() = dir?.takeIf { hasBecomePassword }?.resolve(BECOME_SCRIPT)
    val vaultPlaceholder: Path? get() = dir?.takeIf { hasPlaceholder && vaultLabels.isEmpty() }?.resolve(VAULT_PLACEHOLDER)

    /** The directory holding the Ansibility events callback, when the run reports its events. */
    val callbackDir: Path? get() = dir?.takeIf { hasCallback }?.resolve(CALLBACKS)

    override fun close() {
        val dir = dir ?: return
        try {
            NioFiles.deleteRecursively(dir)
        } catch (_: IOException) {
        }
    }

    override fun toString(): String = "RunSecrets(${vaultLabels.joinToString()}, become=$hasBecomePassword)"

    companion object {
        /** Ends in `-client` so that ansible passes it `--vault-id <label>`. */
        const val VAULT_CLIENT = "ansibility-vault-client"
        const val BECOME_SCRIPT = "ansibility-become-pass"
        const val VAULT_PLACEHOLDER = "ansibility-no-vault"
        const val CALLBACKS = "callbacks"
        const val FALLBACK_VARIABLE = "ANSIBILITY_VAULT_FALLBACK"
        const val BECOME_VARIABLE = "ANSIBILITY_BECOME_PASSWORD"
        private const val VAULT_PREFIX = "ANSIBILITY_VAULT_"
        private val STALE_AFTER = TimeUnit.DAYS.toMillis(1)

        val NONE = RunSecrets(null, emptyList(), false, emptyMap())

        /** The environment variable holding the password of vault id [label]; the client script derives the same name. */
        fun variableOf(label: String): String =
            VAULT_PREFIX + label.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_') it else '_' }.joinToString("")

        /**
         * Writes the scripts for [secrets] and [becomePassword] (and the [vaultPlaceholder] when there are no secrets)
         * into a fresh directory under [base], and removes directories of earlier runs that were not cleaned up.
         * [NONE] when there is nothing to pass.
         */
        fun create(
            base: Path,
            secrets: List<LabelledSecret>,
            becomePassword: CharArray?,
            vaultPlaceholder: Boolean = false,
            /** The source of the events callback to add (`callbacks/<RunCallback.FILE>`), or null. */
            callback: String? = null,
        ): RunSecrets {
            val placeholder = vaultPlaceholder && secrets.isEmpty()
            if (secrets.isEmpty() && becomePassword == null && !placeholder && callback == null) return NONE
            Files.createDirectories(base)
            removeStale(base)
            val dir = Files.createTempDirectory(base, "run-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val environment = LinkedHashMap<String, String>()
            for (secret in secrets) {
                environment.putIfAbsent(variableOf(secret.label), secret.secret.read { String(it, Charsets.UTF_8) })
            }
            val fallback = secrets.firstOrNull { it.label == "default" } ?: secrets.firstOrNull()
            fallback?.let { environment[FALLBACK_VARIABLE] = it.secret.read { bytes -> String(bytes, Charsets.UTF_8) } }
            if (secrets.isNotEmpty()) script(dir.resolve(VAULT_CLIENT), vaultClientScript())
            if (becomePassword != null) {
                environment[BECOME_VARIABLE] = String(becomePassword)
                script(dir.resolve(BECOME_SCRIPT), becomeScript())
            }
            if (placeholder) script(dir.resolve(VAULT_PLACEHOLDER), placeholderScript())
            if (callback != null) {
                val callbacks = Files.createDirectory(dir.resolve(CALLBACKS))
                callbacks.resolve(RunCallback.FILE).writeText(callback)
            }
            return RunSecrets(dir, secrets.map { it.label }.distinct(), becomePassword != null, environment, placeholder, callback != null)
        }

        /** Stands in for a mounted vault password file when no vault id is unlocked. */
        fun placeholderScript(): String = """
            |#!/bin/sh
            |# Written by Ansibility for one playbook run and deleted when it ends: no vault id was unlocked, so vaulted
            |# values fail to decrypt while everything else runs.
            |printf '%s\n' "ansibility-no-vault-password"
            |""".trimMargin()

        /** Prints the password of the `--vault-id` it is given (`default` without one), else the fallback secret. */
        fun vaultClientScript(): String = """
            |#!/bin/sh
            |# Written by Ansibility for one playbook run and deleted when it ends. It holds no secret: it prints one
            |# from the environment Ansibility gave the run.
            |label=""
            |while [ "${'$'}#" -gt 0 ]; do
            |  case "${'$'}1" in
            |    --vault-id) label="${'$'}2"; shift; [ "${'$'}#" -gt 0 ] && shift ;;
            |    --vault-id=*) label="${'$'}{1#--vault-id=}"; shift ;;
            |    *) shift ;;
            |  esac
            |done
            |name="$VAULT_PREFIX${'$'}(printf '%s' "${'$'}{label:-default}" | tr -c 'A-Za-z0-9_' '_')"
            |eval "value=\${'$'}{${'$'}name:-}"
            |[ -n "${'$'}value" ] || value="${'$'}{$FALLBACK_VARIABLE:-}"
            |if [ -z "${'$'}value" ]; then
            |  echo "Ansibility: no vault password for id ${'$'}{label:-default}" >&2
            |  exit 1
            |fi
            |printf '%s\n' "${'$'}value"
            |""".trimMargin()

        fun becomeScript(): String = """
            |#!/bin/sh
            |# Written by Ansibility for one playbook run and deleted when it ends; prints the become password it was given.
            |printf '%s\n' "${'$'}{$BECOME_VARIABLE:-}"
            |""".trimMargin()

        private fun script(path: Path, text: String) {
            path.writeText(text)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }

        private fun removeStale(base: Path) {
            val now = System.currentTimeMillis()
            try {
                for (entry in base.listDirectoryEntries("run-*")) {
                    if (entry.isDirectory() && now - entry.getLastModifiedTime().toMillis() > STALE_AFTER) NioFiles.deleteRecursively(entry)
                }
            } catch (_: IOException) {
            }
        }
    }
}
