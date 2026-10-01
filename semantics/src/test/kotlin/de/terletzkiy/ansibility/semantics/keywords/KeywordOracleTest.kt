package de.terletzkiy.ansibility.semantics.keywords

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.schema.DocSnapshot
import de.terletzkiy.ansibility.semantics.schema.DocSnapshotTest
import de.terletzkiy.ansibility.semantics.schema.PluginKind
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.validate.GoldenJson
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * ANS-K001/K002 against real ansible-core: every playbook of `keyword-oracle/<version>.json` (recorded by
 * `gen_keywords.py` with 2.18.8 in the target repo's image and 2.21.4 locally) is walked like the plugin walks a
 * task file, and the port must report a finding exactly when ansible-core fails to load or run it. A mismatch is a
 * port bug; the tables are never edited by hand.
 */
class KeywordOracleTest {

    @ParameterizedTest
    @ValueSource(strings = ["2.18.8", "2.21.4"])
    fun `port agrees with ansible-core`(version: String) {
        val table = GoldenJson.parse(javaClass.getResource("/keyword-oracle/$version.json")!!.readText()) as Map<*, *>
        assertEquals(version, table["ansible_core"])
        val snapshot = snapshot(version)
        val walker = OracleWalker(snapshot, CoreSemantics(CoreVersion.parse(version)!!))
        val cases = (table["cases"] as List<*>).map { it as Map<*, *> }
        assertTrue(cases.size > 400, "oracle table looks truncated: ${cases.size} cases")
        val exempt = HashSet<String>()
        val mismatches = cases.mapNotNull { case ->
            val name = case["case"] as String
            val findings = walker.findings(YamlText.parse(case["yaml"] as String))
            val rejected = case["outcome"] != "ok"
            when {
                rejected && findings.isEmpty() && name in EXEMPT -> null.also { exempt += name }
                rejected && findings.isEmpty() -> "$name: ansible-core fails (${case["message"]}), the port reports nothing"
                !rejected && findings.isNotEmpty() -> "$name: ansible-core accepts it, the port reports $findings"
                else -> null
            }
        }
        assertTrue(exempt.all { it in EXEMPT }, "unexpected exemptions $exempt")
        val failing = cases.count { it["outcome"] != "ok" }
        println("$version: ${cases.size} cases, $failing rejected by ansible-core, ${mismatches.size} mismatches")
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n", "port disagrees with ansible-core $version:\n"))
    }

    private fun snapshot(version: String): DocSnapshot {
        val name = if (version.startsWith("2.18")) "core-2.18.8.json.gz" else "core-latest.json.gz"
        return SNAPSHOTS.getOrPut(name) { DocSnapshotTest.dataFile(name).inputStream().use { DocSnapshot.load(it) } }
    }

    /**
     * The walk the plugin does over a task file's model, on a parsed playbook: plays, `import_playbook` entries, role
     * entries, blocks, tasks, handlers and `loop_control`, with each keyword checked by [KeywordValidator] and each
     * key that is not a keyword reported as ansible-core would fail on it.
     */
    private class OracleWalker(private val snapshot: DocSnapshot, semantics: CoreSemantics) {
        private val validator = KeywordValidator(semantics)
        private val version = semantics.version

        private fun keywords(owner: PlaybookObject): Set<String> =
            snapshot.keywords.values.filter { owner.docName in it.appliesTo && it.name != DocSnapshot.WITH_LOOKUP }.map { it.name }.toSet() +
                UnknownKeywords.undocumentedKeys(owner, version)

        fun findings(document: YValue): List<String> {
            val found = ArrayList<String>()
            for (item in (document as? YSeq)?.items.orEmpty()) {
                val map = item as? YMap ?: continue
                if (map.keys.any { it == IMPORT || it.endsWith(".$IMPORT") }) entry(PlaybookObject.PLAYBOOK_INCLUDE, map, found) else play(map, found)
            }
            return found
        }

        private fun play(map: YMap, found: MutableList<String>) {
            entry(PlaybookObject.PLAY, map, found)
            for (role in (map["roles"] as? YSeq)?.items.orEmpty()) {
                val roleMap = role as? YMap ?: continue
                for (e in roleMap.entries) if (e.key.text in keywords(PlaybookObject.ROLE)) check(PlaybookObject.ROLE, e.key.text, e.value, found)
            }
            for (section in listOf("pre_tasks", "tasks", "post_tasks")) items(map[section], handler = false, found)
            items(map["handlers"], handler = true, found)
        }

        /** A mapping whose keys are all keywords of [owner] (plays, imports, blocks): the rest are unknown. */
        private fun entry(owner: PlaybookObject, map: YMap, found: MutableList<String>) {
            val known = keywords(owner)
            for (e in map.entries) {
                val key = if (owner == PlaybookObject.PLAYBOOK_INCLUDE && e.key.text.endsWith(".$IMPORT")) IMPORT else e.key.text
                if (key in known) check(owner, key, e.value, found) else found += "K002 ${UnknownKeywords.notAnAttribute(key, owner.className)}"
            }
        }

        private fun items(list: YValue?, handler: Boolean, found: MutableList<String>) {
            for (item in (list as? YSeq)?.items.orEmpty()) {
                val map = item as? YMap ?: continue
                if (map.keys.any { it == "block" }) {
                    entry(PlaybookObject.BLOCK, map, found)
                    for (section in listOf("block", "rescue", "always")) items(map[section], handler, found)
                } else {
                    task(map, handler, found)
                }
            }
        }

        private fun task(map: YMap, handler: Boolean, found: MutableList<String>) {
            val owner = if (handler) PlaybookObject.HANDLER else PlaybookObject.TASK
            val known = keywords(owner)
            val actionKeys = ArrayList<String>()
            if (map.keys.any { it == "action" || it == "local_action" }) actionKeys += "action"
            for (e in map.entries) {
                val key = e.key.text
                when {
                    key in known -> check(owner, key, e.value, found)
                    key.startsWith("with_") -> if (snapshot.plugin(key.removePrefix("with_"), PluginKind.LOOKUP).doc == null) {
                        found += "K002 ${UnknownKeywords.notAnAttribute(key, owner.className)}"
                    }
                    else -> actionKeys += key
                }
            }
            if (actionKeys.size >= 2) found += "K002 ${UnknownKeywords.conflictingActions(actionKeys[0], actionKeys[1])}"
            val module = actionKeys.firstOrNull { it.endsWith("include_tasks") || it.endsWith("include_role") }
            if (module != null) {
                val allowed = UnknownKeywords.dynamicIncludeKeywords(handler)
                for (key in map.keys) if (key in known && key !in allowed) found += "K002 include $key"
            }
            val loopControl = map["loop_control"] as? YMap ?: return
            val loopKeys = keywords(PlaybookObject.LOOP_CONTROL)
            for (e in loopControl.entries) {
                if (e.key.text in loopKeys) check(PlaybookObject.LOOP_CONTROL, e.key.text, e.value, found)
                else found += "K002 ${UnknownKeywords.notAnAttribute(e.key.text, PlaybookObject.LOOP_CONTROL.className)}"
            }
        }

        private fun check(owner: PlaybookObject, name: String, value: YValue, found: MutableList<String>) {
            for (rejection in validator.check(owner, name, snapshot.keyword(name), value)) found += "K001 ${rejection.reason}"
        }
    }

    private companion object {
        const val IMPORT = "import_playbook"

        /** Failures the port does not report on purpose, with the reason. */
        val EXEMPT = mapOf(
            "task.retries=\"{{ x }}y\"" to "templated values are never judged (the rendered value is unknown)",
            "play.validate_argspec" to "2.21 accepts the keyword; the scratch playbook lacks the argument spec file it then requires",
        )
        val SNAPSHOTS = HashMap<String, DocSnapshot>()
    }
}
