package de.terletzkiy.ansibility.navigation.tags

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.SiteNavigation
import de.terletzkiy.ansibility.api.TagSite
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import de.terletzkiy.ansibility.index.TagIndexer
import de.terletzkiy.ansibility.navigation.AnsibilityNavigationBundle.message
import de.terletzkiy.ansibility.navigation.structure.AnsibleOutline
import de.terletzkiy.ansibility.navigation.structure.AnsibleOutline.NodeKind
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem
import javax.swing.Icon

/** The caret on a tag in the `tags:` of a play, role entry, block or task of a playbook or task file (plan X70). */
class TagSiteClassifier : SiteClassifier {
    override fun classify(file: PsiFile, offset: Int): AnsibleSite? {
        val yaml = file as? YAMLFile ?: return null
        val shape = AnsibleOutline.shape(yaml) ?: return null
        val leaf = file.findElementAt(offset)?.takeUnless { it.textRange.startOffset == offset && offset > 0 && it.text.isBlank() }
            ?: file.findElementAt(offset - 1) ?: return null
        val keyValue = PsiTreeUtil.getParentOfType(leaf, YAMLKeyValue::class.java, false) ?: return null
        if (keyValue.keyText != TAGS) return null
        val colon = keyValue.key?.textRange?.endOffset ?: return null
        if (offset <= colon) return null
        val owner = (keyValue.parent as? YAMLMapping)?.parent as? YAMLSequenceItem ?: return null
        if (AnsibleOutline.nodeOf(owner, shape)?.kind !in OWNERS) return null
        val scalar = PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, false)
            ?: return TagSite("", TextRange(offset, offset))
        return token(scalar, offset)
    }

    /** The comma-separated name of [scalar] around [offset]; null on a templated one. */
    private fun token(scalar: YAMLScalar, offset: Int): TagSite? {
        val start = scalar.textRange.startOffset
        val text = scalar.text
        val quote = if (text.startsWith('"') || text.startsWith('\'')) 1 else 0
        val content = text.substring(quote, text.length - if (quote == 1 && text.length > 1 && text.last() == text.first()) 1 else 0)
        var from = 0
        for (part in content.split(',')) {
            val to = from + part.length
            if (offset - start - quote in from..to) {
                val lead = part.length - part.trimStart().length
                val name = part.trim()
                if ("{{" in name || "{%" in name) return null
                val nameStart = start + quote + from + lead
                return TagSite(name, TextRange(nameStart, nameStart + name.length))
            }
            from = to + 1
        }
        return null
    }

    private companion object {
        val OWNERS = setOf(NodeKind.PLAY, NodeKind.ROLE, NodeKind.BLOCK, NodeKind.TASK)
    }
}

/** Tags written anywhere in the root, plus the special `always` and `never`; tags already in the list are left out. */
class TagCompletion : CompletionSource {
    override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
        if (site !is TagSite) return
        val file = parameters.originalFile
        val root = TagOccurrences.rootOf(file.project, file.viewProvider.virtualFile) ?: return
        val prefix = parameters.editor.document.getText(TextRange(site.range.startOffset, parameters.offset.coerceAtLeast(site.range.startOffset)))
        val present = presentTags(file, parameters.offset) - site.name
        val set = result.withPrefixMatcher(prefix)
        for (name in AnsibleIndexQueries.tagNames(file.project, root) - present) {
            set.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Tag))
        }
        for (special in SPECIAL - present) {
            set.addElement(LookupElementBuilder.create(special).withIcon(AllIcons.Nodes.Tag).withTypeText(message("tag.special")))
        }
    }

    private fun presentTags(file: PsiFile, offset: Int): Set<String> {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return emptySet()
        val keyValue = PsiTreeUtil.getParentOfType(leaf, YAMLKeyValue::class.java, false) ?: return emptySet()
        val value = keyValue.value ?: return emptySet()
        val scalars = (value as? YAMLScalar)?.let(::listOf) ?: PsiTreeUtil.findChildrenOfType(value, YAMLScalar::class.java)
        return scalars.flatMapTo(HashSet()) { TagIndexer.names(it.textValue) }
    }

    private companion object {
        val SPECIAL = setOf("always", "never")
    }
}

/** Ctrl+B on a tag: every other place in the root that carries it, each row naming its play, role entry or task. */
class TagNavigation : SiteNavigation {
    override fun targets(site: AnsibleSite, file: PsiFile): List<PsiElement> {
        if (site !is TagSite || site.name.isEmpty()) return emptyList()
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val root = TagOccurrences.rootOf(file.project, virtualFile) ?: return emptyList()
        return TagOccurrences.of(file.project, root, site.name)
            .filterNot { it.file == virtualFile && it.offset in TagOccurrences.scalarRange(file, site.range.startOffset) }
            .mapNotNull { it.element() }
    }
}

/** Hover on a tag: how many plays, role entries, blocks and tasks carry it, and in which roles. */
class TagDocumentation : SiteDocumentation {
    override fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget? {
        if (site !is TagSite || site.name.isEmpty()) return null
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val root = TagOccurrences.rootOf(file.project, virtualFile) ?: return null
        return TagDocumentationTarget(file.project, root, site.name)
    }
}

private class TagDocumentationTarget(private val project: Project, private val root: AnsibleRoot, private val tag: String) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> {
        val self = this
        return Pointer { self.takeIf { !project.isDisposed } }
    }

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(tag).icon(AllIcons.Nodes.Tag).presentation()

    override fun computeDocumentationHint(): String = esc(summary(TagOccurrences.of(project, root, tag)))

    override fun computeDocumentation(): DocumentationResult {
        val occurrences = TagOccurrences.of(project, root, tag)
        val html = buildString {
            append(DocumentationMarkup.DEFINITION_START).append("<b>").append(esc(message("tag.title", tag))).append("</b>").append(DocumentationMarkup.DEFINITION_END)
            append(DocumentationMarkup.CONTENT_START)
            append("<p>").append(esc(summary(occurrences))).append("</p>")
            if (tag == "always" || tag == "never") append("<p>").append(esc(message("tag.special.$tag"))).append("</p>")
            val roles = occurrences.mapNotNull { it.roleName }.groupingBy { it }.eachCount()
            if (roles.isNotEmpty()) {
                append("<p>").append(esc(message("tag.in.roles", roles.entries.joinToString(", ") { (role, n) -> if (n > 1) "$role ($n)" else role }))).append("</p>")
            }
            for ((kind, group) in occurrences.filter { it.kind == NodeKind.PLAY || it.kind == NodeKind.ROLE }.groupBy { it.kind }) {
                append("<p><b>").append(esc(message("tag.group.${kind.name.lowercase()}"))).append("</b><br>")
                append(group.take(MAX_ROWS).joinToString("<br>") { esc("${it.text} \u00B7 ${it.where}") })
                if (group.size > MAX_ROWS) append("<br>").append(esc(message("tag.more", group.size - MAX_ROWS)))
                append("</p>")
            }
            append(DocumentationMarkup.CONTENT_END)
        }
        return DocumentationResult.documentation(html)
    }

    private fun summary(occurrences: List<TagOccurrence>): String {
        val counts = occurrences.groupingBy { it.kind }.eachCount()
        return message(
            "tag.summary",
            counts[NodeKind.PLAY] ?: 0, counts[NodeKind.ROLE] ?: 0, counts[NodeKind.BLOCK] ?: 0, counts[NodeKind.TASK] ?: 0,
        )
    }

    private fun esc(text: String): String = StringUtil.escapeXmlEntities(text)

    override val navigatable: Navigatable? get() = null

    private companion object {
        const val MAX_ROWS = 12
    }
}

/** One place that carries a tag: the owner's outline [kind] and [text], and `path:line` ([where]). */
internal class TagOccurrence(
    val project: Project,
    val file: VirtualFile,
    val offset: Int,
    val kind: NodeKind,
    val text: String,
    val where: String,
    val roleName: String?,
) {
    fun element(): PsiElement? = PsiManager.getInstance(project).findFile(file)?.findElementAt(offset)?.let { TagTargetElement(it, this) }
}

internal object TagOccurrences {
    fun rootOf(project: Project, file: VirtualFile): AnsibleRoot? = AnsibleWorkspace.getInstance(project).contextOf(file)?.root

    fun of(project: Project, root: AnsibleRoot, tag: String): List<TagOccurrence> {
        val manager = PsiManager.getInstance(project)
        return AnsibleIndexQueries.tagUses(project, root, tag).mapNotNull { hit ->
            val yaml = manager.findFile(hit.file) as? YAMLFile ?: return@mapNotNull null
            val shape = AnsibleOutline.shape(yaml) ?: return@mapNotNull null
            val keyValue = PsiTreeUtil.getParentOfType(yaml.findElementAt(hit.value), YAMLKeyValue::class.java) ?: return@mapNotNull null
            val owner = (keyValue.parent as? YAMLMapping)?.parent as? YAMLSequenceItem ?: return@mapNotNull null
            val node = AnsibleOutline.nodeOf(owner, shape) ?: return@mapNotNull null
            val document = yaml.viewProvider.document ?: return@mapNotNull null
            val path = VfsUtilCore.getRelativePath(hit.file, root.dir) ?: hit.file.name
            TagOccurrence(project, hit.file, hit.value, node.kind, node.text, "$path:${document.getLineNumber(hit.value) + 1}", hit.context.roleName)
        }.distinctBy { it.file to it.offset }
    }

    /** The range of the scalar at [offset] in [file], so the tag under the caret is not offered as its own target. */
    fun scalarRange(file: PsiFile, offset: Int): IntRange {
        val scalar = PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLScalar::class.java, false) ?: return offset..offset
        return scalar.textRange.startOffset..scalar.textRange.endOffset
    }
}

/** A chooser row for a tag occurrence: `Render config  task · roles/web/tasks/main.yml:12`. */
private class TagTargetElement(private val anchor: PsiElement, private val occurrence: TagOccurrence) : FakePsiElement() {
    override fun getParent(): PsiElement = anchor

    override fun getName(): String = occurrence.text

    override fun getPresentableText(): String = occurrence.text

    override fun getLocationString(): String = message("tag.row", message("tag.kind.${occurrence.kind.name.lowercase()}"), occurrence.where)

    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Tag

    override fun getTextRange(): TextRange? = anchor.textRange

    override fun getTextOffset(): Int = occurrence.offset

    override fun getContainingFile(): PsiFile? = anchor.containingFile

    override fun isValid(): Boolean = anchor.isValid && occurrence.file.isValid

    override fun canNavigate(): Boolean = occurrence.file.isValid

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(anchor.project, occurrence.file, occurrence.offset).navigate(requestFocus)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TagTargetElement && other.occurrence.file == occurrence.file && other.occurrence.offset == occurrence.offset

    override fun hashCode(): Int = occurrence.file.hashCode() * 31 + occurrence.offset
}

private const val TAGS = "tags"
