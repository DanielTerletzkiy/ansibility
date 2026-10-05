package de.terletzkiy.ansibility.resolve.register

import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.DocAnchor
import de.terletzkiy.ansibility.api.DocKind
import de.terletzkiy.ansibility.resolve.register.AnsibilityRegisteredBundle.message
import de.terletzkiy.ansibility.runtime.DocUrls
import de.terletzkiy.ansibility.semantics.registered.MemberDoc
import de.terletzkiy.ansibility.semantics.registered.MemberKind
import de.terletzkiy.ansibility.semantics.registered.ResultMember
import org.jetbrains.annotations.Nls

/** A web page that documents a registered result's member, for Ctrl+B and the card's Documentation row. */
data class MemberPage(
    /** What the page is, e.g. "ansible.builtin.command docs" or "Common return values". */
    @Nls val label: String,
    val url: String,
    /** The module whose page it is; null for the playbook guide and reference pages. */
    val module: String?,
)

/**
 * Texts and pages of registered result members (plan amendment FU, F1.12): the documentation of members no module
 * documents ([MemberDoc.textKey], from [AnsibilityRegisteredBundle]), what a member is, its completion tail, and the
 * pages that document it:
 * - a module's return value → the module page at `#return-<path>` ([DocAnchor.ReturnValue]); an undocumented return
 *   (`json` of `uri`) → the module page;
 * - the common keys and `stdout_lines`/`stderr_lines` → the common return values reference, at the key;
 * - `results` and the item keys of a loop → "Registering variables with a loop"; `attempts`/`retries` → "Retrying a
 *   task until a condition is met"; the job keys of `async` → the `async_status` page, else the async guide.
 *
 * Pages live under the root's versioned docs tree (the base of [AnsibleDocService.docsUrl]).
 */
object RegisteredDocs {
    private const val COMMON_PAGE = "reference_appendices/common_return_values.html"
    private const val LOOPS_PAGE = "playbook_guide/playbooks_loops.html"
    private const val ASYNC_PAGE = "playbook_guide/playbooks_async.html"
    private const val LOOP_ANCHOR = "#registering-variables-with-a-loop"
    private const val RETRY_ANCHOR = "#retrying-a-task-until-a-condition-is-met"
    private const val KEY_PREFIX = "member."

    /** The documentation paragraphs of [doc] (Ansible doc markup): the module's text, else the built-in one. */
    fun description(doc: MemberDoc): List<String> {
        if (doc.description.isNotEmpty()) return doc.description
        val key = doc.textKey ?: return emptyList()
        return listOfNotNull(AnsibilityRegisteredBundle.messageOrNull("$KEY_PREFIX$key.description"))
    }

    /** When the member is present (`always`, `success` …), as documented or built in; null when unknown. */
    @Nls
    fun returned(doc: MemberDoc): String? =
        doc.returned?.takeIf { it.isNotBlank() } ?: doc.textKey?.let { AnsibilityRegisteredBundle.messageOrNull("$KEY_PREFIX$it.returned") }

    /** What the member is: "return value of ansible.builtin.command", "common return value", "loop result" … */
    @Nls
    fun kind(doc: MemberDoc): String = when (doc.kind) {
        MemberKind.MODULE -> message("kind.module", doc.module.orEmpty())
        MemberKind.SUPPLEMENT -> message("kind.supplement", doc.module.orEmpty())
        MemberKind.DERIVED -> message("kind.derived")
        MemberKind.COMMON -> message("kind.common")
        MemberKind.RETRY -> message("kind.retry")
        MemberKind.ASYNC -> doc.module?.let { message("kind.module", it) } ?: message("kind.async")
        MemberKind.LOOP -> message("kind.loop")
        MemberKind.LOOP_ITEM -> message("kind.loop.item")
    }

    /**
     * The completion tail of [member] without separator: the short names of the modules that return it
     * (`command, shell`), else where it comes from (`common`, `until`, `async`, `loop` …).
     */
    @Nls
    fun tail(member: ResultMember): String {
        val modules = member.docs.filter { it.kind == MemberKind.MODULE || it.kind == MemberKind.SUPPLEMENT }
            .mapNotNull { it.module?.substringAfterLast('.') }.distinct()
        if (modules.isNotEmpty()) return message("tail.module", modules.joinToString(", "))
        return when (member.primary?.kind) {
            MemberKind.RETRY -> message("tail.retry")
            MemberKind.ASYNC -> message("tail.async")
            MemberKind.LOOP -> message("tail.loop")
            MemberKind.LOOP_ITEM -> message("tail.loop.item")
            MemberKind.DERIVED -> message("tail.derived")
            else -> message("tail.common")
        }
    }

    /** The pages documenting [member] (named [name], the last key of its path) in [root], one per distinct page. */
    fun pages(docs: AnsibleDocService, root: AnsibleRoot, name: String, member: ResultMember): List<MemberPage> =
        member.docs.mapNotNull { page(docs, root, name, it) }.distinctBy { it.url }

    /** The page of one documentation of the member [name]; null when nothing documents it on the web. */
    fun page(docs: AnsibleDocService, root: AnsibleRoot, name: String, doc: MemberDoc): MemberPage? {
        val module = doc.module
        return when (doc.kind) {
            MemberKind.MODULE, MemberKind.SUPPLEMENT -> module?.let {
                val anchor = DocAnchor.ReturnValue(doc.returnPath).takeIf { doc.kind == MemberKind.MODULE && doc.returnPath.isNotEmpty() }
                MemberPage(message("card.docs.module", it), docs.docsUrl(root, DocKind.MODULE, it, anchor), it)
            }
            MemberKind.ASYNC -> if (module != null) {
                MemberPage(message("card.docs.module", module), docs.docsUrl(root, DocKind.MODULE, module, DocAnchor.ReturnValue(doc.returnPath.ifEmpty { listOf(name) })), module)
            } else {
                MemberPage(message("card.docs.async"), base(docs, root) + ASYNC_PAGE, null)
            }
            MemberKind.DERIVED, MemberKind.COMMON -> MemberPage(message("card.docs.common"), base(docs, root) + COMMON_PAGE + "#" + anchorOf(name), null)
            MemberKind.RETRY -> MemberPage(message("card.docs.retry"), base(docs, root) + LOOPS_PAGE + RETRY_ANCHOR, null)
            MemberKind.LOOP, MemberKind.LOOP_ITEM -> MemberPage(message("card.docs.loops"), base(docs, root) + LOOPS_PAGE + LOOP_ANCHOR, null)
        }
    }

    /** The section anchor of a common return value (`stdout_lines` → `stdout-lines`, as Sphinx derives it). */
    private fun anchorOf(name: String): String = name.lowercase().replace('_', '-')

    /** The root's docs tree (`https://docs.ansible.com/ansible/11/`): the part of a keywords page URL before the page. */
    private fun base(docs: AnsibleDocService, root: AnsibleRoot): String =
        docs.docsUrl(root, DocKind.KEYWORD, REGISTER_KEYWORD).substringBefore(DocUrls.KEYWORDS_PAGE)

    private const val REGISTER_KEYWORD = "register"
}
