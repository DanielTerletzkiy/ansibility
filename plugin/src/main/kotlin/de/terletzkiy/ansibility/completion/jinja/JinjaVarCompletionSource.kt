package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.progress.ProgressManager
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.dispatch.SiteDispatch
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.vars.JinjaTextSites

/**
 * Jinja variable completion (plan F1.3, F1.6, F1.7, F2.3 text level, X10), registered as the `completionSource`
 * `ansibilityJinjaVars`.
 *
 * Where: inside `{{ }}`/`{% %}` of Jinja-bearing YAML scalars, in bare implicit expressions (`when`, `that` …) and in
 * template files of any file type (a `.j2` typed as YAML, plain text, PyCharm's Jinja2 …). The Jinja text and the
 * host-offset mapping come from the variables area's text locator ([JinjaTextSites]); what the caret completes is
 * decided by [JinjaCompletionPosition]. Everything else (filter and test names, outer template text, keys and values
 * of plain YAML) returns at once.
 *
 * What: variable names by tier ([JinjaNames]), members after `.` and quoted keys after `['` ([JinjaMembers]), and,
 * right after `hostvars[`/`groups[`/`ansible_facts[` …, the quoted keys of the owner before the names. Items carry a
 * [JinjaLookupItem], so Ctrl+Q in the popup shows the variable card.
 *
 * Needs indexes (not `DumbAware`); never calls `runRemainingContributors` or `stopHere` (the dispatcher does).
 */
class JinjaVarCompletionSource : CompletionSource {
    override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        if (site is AnsibleSite.JinjaFilter || site is AnsibleSite.JinjaTest) return
        val host = SiteDispatch.hostPosition(parameters.originalFile, parameters.offset)
        val analysis = JinjaTextSites.analysisAt(host.file, host.offset) ?: return
        val mode = if (analysis.container == JinjaContainer.YAML_EXPRESSION) JinjaLexMode.EXPRESSION else JinjaLexMode.TEMPLATE
        val position = JinjaCompletionPosition.at(analysis.text, analysis.textOffset, mode) ?: return
        ProgressManager.checkCanceled()
        val scope = JinjaCompletionScope.create(host.file, host.offset, analysis) ?: return
        val matching = result.withPrefixMatcher(position.prefix)
        val accept: (String) -> Boolean = { matching.prefixMatcher.prefixMatches(it) }
        for (candidate in candidates(scope, position, accept)) {
            ProgressManager.checkCanceled()
            matching.addElement(JinjaLookupElements.create(candidate, scope))
        }
    }

    internal companion object {
        /** Every candidate for [position] in [scope] that [accept] lets through. */
        fun candidates(scope: JinjaCompletionScope, position: JinjaCompletionPosition, accept: (String) -> Boolean): List<JinjaCandidate> =
            when (position) {
                is JinjaCompletionPosition.Name -> bracketKeys(scope, position) + JinjaNames(scope, accept).collect()
                is JinjaCompletionPosition.Member -> JinjaMembers(scope).members(position.chain, keys = false, accept)
                is JinjaCompletionPosition.Key -> {
                    val closing = closingQuote(scope, position)
                    JinjaMembers(scope).members(position.chain, keys = true, accept).map { it.withInsert(InsertStyle.CloseKey(closing)) }
                }
            }

        /** Right after `owner[`: the owner's keys, quoted, before the variable names (which index dynamically). */
        private fun bracketKeys(scope: JinjaCompletionScope, position: JinjaCompletionPosition.Name): List<JinjaCandidate> {
            val owner = position.bracketOwner ?: return emptyList()
            if (position.prefix.isNotEmpty()) return emptyList()
            val quote = if (singleQuotedHost(scope)) '"' else '\''
            return JinjaMembers(scope).members(owner, keys = true) { true }.map { candidate ->
                val quoted = "$quote${candidate.lookupString}$quote"
                JinjaCandidate(
                    quoted, Tier.MEMBER, candidate.rank + 1000, candidate.typeText, candidate.tail, candidate.icon, candidate.doc,
                    candidate.bold, candidate.deprecated, candidate.grey, InsertStyle.CloseBracket, quoted,
                )
            }
        }

        /** The host spelling of the key's opening quote (escaped inside double-quoted YAML, doubled in single-quoted). */
        private fun closingQuote(scope: JinjaCompletionScope, position: JinjaCompletionPosition.Key): String {
            val quoteAt = scope.analysis.textOffset - position.prefix.length - 1
            if (quoteAt < 0) return position.quote.toString()
            val start = scope.analysis.toHost(quoteAt)
            val end = scope.analysis.toHost(quoteAt + 1)
            val text = scope.hostText
            return if (start in 0 until end && end <= text.length) text.subSequence(start, end).toString() else position.quote.toString()
        }

        /** True when the Jinja sits in a single-quoted YAML scalar, where `'` would need doubling. */
        private fun singleQuotedHost(scope: JinjaCompletionScope): Boolean {
            if (scope.analysis.container == JinjaContainer.TEMPLATE_FILE) return false
            val start = scope.analysis.toHost(0) - 1
            return start >= 0 && start < scope.hostText.length && scope.hostText[start] == '\''
        }

        private fun JinjaCandidate.withInsert(style: InsertStyle) = JinjaCandidate(
            lookupString, tier, rank, typeText, tail, icon, doc, bold, deprecated, grey, style, presentable,
        )
    }
}
