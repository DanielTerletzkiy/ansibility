package de.terletzkiy.ansibility.dispatch

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.ElementPattern
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.coexist.CoexistenceSettings

/**
 * The one completion entry point of the plugin (`completion.contributor language="any" order="first"`, plan A.4).
 * `any` covers YAML, the AnsibleJinja template language and `.j2` files that currently have another type.
 *
 * Outside Ansible roots, and for files of kind OTHER, it returns at once and the platform continues as usual.
 * Otherwise it classifies the caret (the site may be null; each source then decides) and lets every
 * `completionSource` extension add items. When they added any, the remaining contributors (YAML/JSON schema,
 * other Ansible plugins, word completion) run through a filter that drops items whose lookup string duplicates
 * one of ours, or, with X85 [CoexistenceSettings.hideOtherAnsibleCompletions], do not run at all.
 *
 * Two of our own sources may offer the same lookup string (a template-name value is both a spec choice and a file
 * name, say). The items of all sources are therefore collected first; for a lookup string that several sources
 * offer only the item with the highest [PrioritizedLookupElement] priority is kept (the earlier source on a tie),
 * and the rest are passed on in the order the sources added them. Duplicates within one source are that source's
 * own choice and stay.
 *
 * Sources must not call `runRemainingContributors` themselves; this dispatcher does it once for all of them.
 * The dispatcher is `DumbAware`; while indexing it asks only the classifiers and sources that are `DumbAware` too.
 */
class AnsibleCompletionContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = SiteDispatch.hostPosition(parameters.originalFile, parameters.offset)
        if (!SiteDispatch.isCompletionTarget(SiteDispatch.contextOf(position.file))) return
        val dumbAwareOnly = SiteDispatch.isDumb(position.file.project)
        val site = SiteDispatch.classify(position, dumbAwareOnly)?.site

        val collected = OwnItems()
        var index = 0
        CompletionSource.EP_NAME.forEachExtensionSafe { source ->
            ProgressManager.checkCanceled()
            val sourceIndex = index++
            if (SiteDispatch.mayRun(source, dumbAwareOnly)) source.complete(site, parameters, CollectingResultSet(result, collected, sourceIndex, this))
        }
        val ours = collected.flush()
        if (ours.isEmpty() || result.isStopped) return
        if (CoexistenceSettings.getInstance().hideOtherAnsibleCompletions()) {
            result.stopHere()
            return
        }
        result.runRemainingContributors(parameters) { foreign ->
            if (foreign.lookupElement.lookupString !in ours) result.passResult(foreign)
        }
    }
}

/**
 * The items our sources added in one completion, in order, until [flush] passes them on. Only items that pass the
 * prefix matcher of the result set they were added to are kept (the platform would drop the others anyway).
 */
internal class OwnItems {
    private class Item(val lookupString: String, val priority: Double, val source: Int, val pass: () -> Unit)

    private val items = ArrayList<Item>()

    fun add(element: LookupElement, source: Int, pass: () -> Unit) {
        val priority = element.`as`(PrioritizedLookupElement.CLASS_CONDITION_KEY)?.priority ?: 0.0
        items += Item(element.lookupString, priority, source, pass)
    }

    /**
     * Passes every kept item on, in the order added: for a lookup string several sources offered, the items of the
     * source whose best item has the highest priority (the earliest such source on a tie). Returns the lookup strings
     * passed on.
     */
    fun flush(): Set<String> {
        val winner = HashMap<String, Int>()
        val best = HashMap<String, Double>()
        for (item in items) {
            val current = best[item.lookupString]
            if (current == null || item.priority > current) {
                best[item.lookupString] = item.priority
                winner[item.lookupString] = item.source
            }
        }
        val passed = LinkedHashSet<String>()
        for (item in items) {
            ProgressManager.checkCanceled()
            if (winner[item.lookupString] != item.source) continue
            item.pass()
            passed += item.lookupString
        }
        items.clear()
        return passed
    }
}

/**
 * A [CompletionResultSet] for one source ([source] is its position among the sources): items that pass its prefix
 * matcher go to [collected] instead of [delegate], which receives them when [OwnItems.flush] keeps them. Derived
 * result sets (another prefix matcher, sorter or case sensitivity) collect into the same [collected].
 */
private class CollectingResultSet(
    private val delegate: CompletionResultSet,
    private val collected: OwnItems,
    private val source: Int,
    private val owner: CompletionContributor,
) : CompletionResultSet(
    delegate.prefixMatcher,
    { result: CompletionResult -> collected.add(result.lookupElement, source) { delegate.passResult(result) } },
    owner,
) {
    override fun addElement(element: LookupElement) {
        if (delegate.prefixMatcher.prefixMatches(element)) collected.add(element, source) { delegate.addElement(element) }
    }

    override fun withPrefixMatcher(matcher: PrefixMatcher): CompletionResultSet = wrap(delegate.withPrefixMatcher(matcher))

    override fun withPrefixMatcher(prefix: String): CompletionResultSet = wrap(delegate.withPrefixMatcher(prefix))

    override fun withRelevanceSorter(sorter: CompletionSorter): CompletionResultSet = wrap(delegate.withRelevanceSorter(sorter))

    override fun caseInsensitive(): CompletionResultSet = wrap(delegate.caseInsensitive())

    override fun addLookupAdvertisement(text: String) = delegate.addLookupAdvertisement(text)

    override fun restartCompletionOnPrefixChange(prefixCondition: ElementPattern<String>) =
        delegate.restartCompletionOnPrefixChange(prefixCondition)

    override fun restartCompletionWhenNothingMatches() = delegate.restartCompletionWhenNothingMatches()

    override fun stopHere() {
        super.stopHere()
        delegate.stopHere()
    }

    override fun isStopped(): Boolean = super.isStopped() || delegate.isStopped

    private fun wrap(derived: CompletionResultSet): CompletionResultSet = CollectingResultSet(derived, collected, source, owner)
}
