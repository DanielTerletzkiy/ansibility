package de.terletzkiy.ansibility.lint

import de.terletzkiy.ansibility.semantics.json.Json
import java.nio.file.Path

/** ansible-lint's own level of a finding (SARIF `level`). */
enum class LintLevel { ERROR, WARNING, NOTE }

/**
 * One ansible-lint finding (plan amendment R28): [rule] as ansible-lint names it (`name[casing]`, `fqcn[action-core]`),
 * [line] 1-based, [column] 1-based or null, [file] on the host. [autofix] when the rule is tagged `autofix`, so
 * `ansible-lint --fix <rule>` can repair it.
 */
data class LintFinding(
    val rule: String,
    val level: LintLevel,
    val message: String,
    val description: String?,
    val help: String?,
    val url: String?,
    val file: Path,
    val line: Int,
    val column: Int?,
    val autofix: Boolean = false,
) {
    /** The rule without its sub-rule (`name` for `name[casing]`), as `skip_list` and `# noqa` accept both. */
    val baseRule: String get() = rule.substringBefore('[')
}

/** Reads ansible-lint's SARIF output (`-f sarif`), the format both 25.x and 26.x print. */
object LintSarif {
    /**
     * The findings of [output]; [resolve] turns a result's path into a host path (null drops the finding): absolute
     * when the run names its project directory (`originalUriBaseIds.SRCROOT`, in the container for Compose runs), else
     * as printed (relative to the working directory). Text around the JSON (an entrypoint's "no violations" line) is ignored.
     * Throws [LintOutputException] when there is no SARIF document.
     */
    fun parse(output: String, resolve: (String) -> Path?): List<LintFinding> {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end < start) throw LintOutputException(output.trim().take(MAX_EXCERPT))
        val document = try {
            Json.parseObject(output.substring(start, end + 1))
        } catch (e: Exception) {
            throw LintOutputException(output.trim().take(MAX_EXCERPT))
        }
        val runs = document["runs"] as? List<*> ?: return emptyList()
        val findings = ArrayList<LintFinding>()
        for (run in runs.filterIsInstance<Map<*, *>>()) {
            val rules = rulesOf(run)
            val bases = (run["originalUriBaseIds"] as? Map<*, *>).orEmpty().mapNotNull { (key, value) ->
                ((value as? Map<*, *>)?.get("uri") as? String)?.let { key.toString() to it.removePrefix("file://").trimEnd('/') }
            }.toMap()
            for (result in (run["results"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
                val ruleId = result["ruleId"] as? String ?: continue
                val location = (result["locations"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: continue
                val physical = location["physicalLocation"] as? Map<*, *> ?: continue
                val artifact = physical["artifactLocation"] as? Map<*, *> ?: continue
                val uri = (artifact["uri"] as? String)?.removePrefix("file://") ?: continue
                val base = (artifact["uriBaseId"] as? String)?.let(bases::get)
                val region = physical["region"] as? Map<*, *>
                val file = resolve(if (!uri.startsWith("/") && base != null) "$base/$uri" else uri) ?: continue
                val rule = rules[ruleId] ?: rules[ruleId.substringBefore('[')]
                findings += LintFinding(
                    rule = ruleId,
                    level = levelOf(result["level"] as? String ?: rule?.level),
                    message = ((result["message"] as? Map<*, *>)?.get("text") as? String).orEmpty(),
                    description = rule?.description,
                    help = rule?.help,
                    url = rule?.url,
                    file = file,
                    line = (region?.get("startLine") as? Number)?.toInt()?.coerceAtLeast(1) ?: 1,
                    column = (region?.get("startColumn") as? Number)?.toInt(),
                    autofix = rule?.autofix == true,
                )
            }
        }
        return findings
    }

    private class Rule(val level: String?, val description: String?, val help: String?, val url: String?, val autofix: Boolean)

    private fun rulesOf(run: Map<*, *>): Map<String, Rule> {
        val driver = (run["tool"] as? Map<*, *>)?.get("driver") as? Map<*, *> ?: return emptyMap()
        return (driver["rules"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { rule ->
            val id = rule["id"] as? String ?: return@mapNotNull null
            val tags = ((rule["properties"] as? Map<*, *>)?.get("tags") as? List<*>).orEmpty()
            id to Rule(
                level = (rule["defaultConfiguration"] as? Map<*, *>)?.get("level") as? String,
                description = (rule["shortDescription"] as? Map<*, *>)?.get("text") as? String,
                help = (rule["help"] as? Map<*, *>)?.get("text") as? String,
                url = rule["helpUri"] as? String,
                autofix = "autofix" in tags || id.substringBefore('[') in AUTOFIX_RULES,
            )
        }.toMap()
    }

    private fun levelOf(level: String?): LintLevel = when (level) {
        "error" -> LintLevel.ERROR
        "note", "none" -> LintLevel.NOTE
        else -> LintLevel.WARNING
    }

    private const val MAX_EXCERPT = 400

    /**
     * The rules ansible-lint can repair with `--fix` (its transform rules; SARIF does not tag them). `--fix` also
     * reformats the whole YAML file.
     */
    val AUTOFIX_RULES: Set<String> = setOf(
        "command-instead-of-shell", "deprecated-local-action", "fqcn", "jinja", "key-order", "name", "no-free-form",
        "no-jinja-when", "no-log-password", "partial-become", "yaml",
    )
}

/** ansible-lint printed no SARIF document (it failed before linting); [excerpt] is the start of what it printed. */
class LintOutputException(val excerpt: String) : Exception("ansible-lint printed no SARIF output")
