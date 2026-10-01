package de.terletzkiy.ansibility.completion.keys

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.NamedColorUtil
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.vars.VarCard
import org.jetbrains.annotations.Nls
import javax.swing.Icon

/**
 * What a key or value item documents: variable [name] of the root at [rootDir] with the nested [path] below it (keys
 * and sequence indices as written), seen from [file] at [offset]. The lookup object of every item of this package;
 * Ctrl+Q in the popup turns it into the variable card ([VarLookupDocumentationTargetProvider]).
 */
internal data class VarLookupDoc(
    val rootDir: VirtualFile,
    val name: String,
    val path: List<String>,
    val file: VirtualFile,
    val offset: Int,
    /** The lookup string, so that items with equal documentation stay distinct objects. */
    val lookupString: String,
)

/**
 * Builds the lookup items of vars-file key and value completion (plan F4.2, X19) and their ranking.
 *
 * Ranks (higher first): keys of the roles the mapping is meant for ([AppliedRoles]), required-without-default ones
 * first; then the other roles' keys, required first; then inventory-only names (grey). Nested keys: required first.
 * Values keep the spec's order.
 */
internal object KeyLookups {
    private const val APPLIED = 300.0
    private const val OTHER = 100.0
    private const val REQUIRED_BONUS = 50.0
    private const val INVENTORY_ONLY = 0.0
    private const val NESTED_REQUIRED = 200.0
    private const val NESTED = 100.0
    private const val MAX_TAIL_ROLES = 3

    /** Keeps spec order inside one priority group (far below the gaps between groups). */
    private const val RANK_STEP = 0.001

    /** A top-level variable declared by [declarations] (applied roles first). */
    fun topLevel(name: String, declarations: List<KeyDeclaration>, applied: Boolean, doc: VarLookupDoc): LookupElement {
        val spec = declarations.firstOrNull { it.option != null }
        val option = spec?.option
        val required = declarations.any { it.requiredWithoutDefault }
        val roles = declarations.map { it.role }.distinct()
        val tail = if (option != null) {
            AnsibilityKeyCompletionBundle.message("key.tail.roles", roleList(roles))
        } else {
            AnsibilityKeyCompletionBundle.message("key.tail.defaults", roleList(roles))
        }
        var builder = LookupElementBuilder.create(doc, name)
            .withIcon(AllIcons.Nodes.Variable)
            .withBoldness(required)
            .withTailText(tail, true)
            .withTypeText(option?.let(VarCard::typeText) ?: declarations.firstNotNullOfOrNull { it.defaultType })
            .withInsertHandler(KeyInsertHandler)
        if (option?.deprecated != null) builder = builder.strikeout()
        val priority = (if (applied) APPLIED else OTHER) + if (required) REQUIRED_BONUS else 0.0
        return PrioritizedLookupElement.withPriority(builder, priority)
    }

    /** A variable only the root's inventory sets (no role declares it): last, greyed out. */
    fun inventoryOnly(name: String, doc: VarLookupDoc): LookupElement {
        val builder = LookupElementBuilder.create(doc, name)
            .withIcon(AllIcons.Nodes.Variable)
            .withItemTextForeground(NamedColorUtil.getInactiveTextColor())
            .withTailText(AnsibilityKeyCompletionBundle.message("key.tail.inventory"), true)
            .withInsertHandler(KeyInsertHandler)
        return PrioritizedLookupElement.withPriority(builder, INVENTORY_ONLY)
    }

    /**
     * A sub-option key [option] declared by [roles]; [rank] is its place in the popup (0 first), since the popup
     * sorts items of equal priority by name and the spec order should win.
     */
    fun nested(option: OptionSpec, roles: List<String>, rank: Int, doc: VarLookupDoc): LookupElement {
        var builder = LookupElementBuilder.create(doc, option.name)
            .withIcon(AllIcons.Nodes.Property)
            .withBoldness(option.required)
            .withTailText(AnsibilityKeyCompletionBundle.message("key.tail.roles", roleList(roles)), true)
            .withTypeText(VarCard.typeText(option))
            .withInsertHandler(KeyInsertHandler)
        if (option.deprecated != null) builder = builder.strikeout()
        return PrioritizedLookupElement.withPriority(builder, (if (option.required) NESTED_REQUIRED else NESTED) - rank * RANK_STEP)
    }

    /**
     * A value item: [lookupString] is what the prefix is matched against (the value's text), [insertText] what is
     * written (quoted when the plain spelling would load as another type), [presentable] what the popup shows.
     */
    fun value(
        lookupString: String,
        insertText: String,
        presentable: String,
        typeText: String?,
        isDefault: Boolean,
        rank: Int,
        doc: VarLookupDoc,
        icon: Icon = AllIcons.Nodes.Enum,
    ): LookupElement {
        var builder = LookupElementBuilder.create(doc, lookupString)
            .withPresentableText(presentable)
            .withIcon(icon)
            .withTypeText(typeText)
        if (isDefault) builder = builder.withTailText(AnsibilityKeyCompletionBundle.message("value.tail.default"), true)
        if (insertText != lookupString) builder = builder.withInsertHandler(ReplaceInsertHandler(insertText))
        return PrioritizedLookupElement.withPriority(builder, -rank * RANK_STEP)
    }

    /** `haproxy`, `haproxy, loki`, `a, b, c, +2`. */
    @Nls
    private fun roleList(roles: List<String>): String {
        val shown = roles.take(MAX_TAIL_ROLES).joinToString(", ")
        return if (roles.size <= MAX_TAIL_ROLES) shown else AnsibilityKeyCompletionBundle.message("key.tail.more", shown, roles.size - MAX_TAIL_ROLES)
    }
}

/**
 * Completes a key as `name: `: adds the colon and a space unless the rest of the line already has the key's colon
 * (completing inside an existing key), and leaves the caret after them.
 */
internal object KeyInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val tail = context.tailOffset
        val text = document.charsSequence
        var end = tail
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        val rest = text.subSequence(tail, end)
        if (hasKeyColon(rest)) return
        document.insertString(tail, ": ")
        context.editor.caretModel.moveToOffset(tail + 2)
    }

    /** True when [rest] (after the inserted name, up to the line end) continues with the key's `:` separator. */
    private fun hasKeyColon(rest: CharSequence): Boolean {
        val index = rest.indexOf(':')
        if (index < 0) return false
        val before = rest.subSequence(0, index)
        if (before.any { it == '#' || it == '"' || it == '\'' }) return false
        return index + 1 == rest.length || rest[index + 1] == ' ' || rest[index + 1] == '\t'
    }
}

/** Writes [text] in place of the inserted lookup string (a value that must be quoted). */
internal class ReplaceInsertHandler(private val text: String) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        context.document.replaceString(context.startOffset, context.tailOffset, text)
        context.editor.caretModel.moveToOffset(context.startOffset + text.length)
    }
}
