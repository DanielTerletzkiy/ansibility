package de.terletzkiy.ansibility.context.host.card

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.context.host.AnsibilityHostBundle
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vars.AnsibilityVarsBundle
import de.terletzkiy.ansibility.vars.VarLinks
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.vault.ui.VaultCardLinks
import org.jetbrains.annotations.Nls

/**
 * The wording and HTML pieces the host-aware card parts share (plan amendment R7/R8, F8.2): host lists that never
 * merge environments, `path:line` labels of definitions, layer labels in the card's own words, and vault-safe values.
 *
 * Values are the engine's previews ([VarSourceRef.preview], the one vault-safe rule of `index.ValueSummary`): a vault
 * value, a `vault_*` name and every value of a vault file have no preview and are shown masked. A `!vault` value gets
 * the vault area's Reveal link ([VaultCardLinks]), the one explicit way from a card to a value; nothing here decrypts.
 * Definition links are the variable card's own ([VarLinks.definition]), so they open the definition's card.
 */
internal object HostCardTexts {
    const val SEPARATOR: String = " · "

    /** Hosts listed per environment before "+n more". */
    private const val MAX_HOSTS_PER_ENVIRONMENT = 8

    @Nls
    fun message(key: String, vararg params: Any): String = AnsibilityHostBundle.message(key, *params)

    // ------------------------------------------------------------------------------------------------ hosts

    /**
     * [hosts] as users read them: bare names when they all belong to one environment, else per environment
     * (`ops: ops-ops1 · prod: prod-prod1, prod-prod2`), so hosts of different environments are never merged. Molecule
     * scenarios read `molecule postfix/default: …`. At most [MAX_HOSTS_PER_ENVIRONMENT] names per environment.
     */
    @NlsSafe
    fun hostList(hosts: Collection<HostKey>): String {
        val byEnvironment = hosts.distinct().groupBy({ it.environment }, { it.host })
        if (byEnvironment.size == 1) return names(byEnvironment.values.single())
        return byEnvironment.entries.joinToString(SEPARATOR) { (environment, names) -> "${environmentLabel(environment)}: ${names(names)}" }
    }

    /** `prod`, or `molecule postfix/default` for a molecule scenario's pseudo-environment. */
    @NlsSafe
    fun environmentLabel(environment: String): String =
        if (environment.startsWith(HostKey.MOLECULE_PREFIX)) {
            message("card.environment.molecule", environment.removePrefix(HostKey.MOLECULE_PREFIX))
        } else {
            environment
        }

    /** `prod › prod-prod1`, the one host of a host-mode line. */
    @NlsSafe
    fun hostLabel(host: HostKey): String = "${environmentLabel(host.environment)} › ${host.host}"

    private fun names(names: List<String>): String {
        if (names.size <= MAX_HOSTS_PER_ENVIRONMENT) return names.joinToString(", ")
        return names.take(MAX_HOSTS_PER_ENVIRONMENT).joinToString(", ") + " " + message("card.hosts.more", names.size - MAX_HOSTS_PER_ENVIRONMENT)
    }

    /** `play System`, or `3 plays`; null without plays (inventory-only evaluation). */
    @Nls
    fun plays(plays: Collection<PlayRef>): String? = when (plays.size) {
        0 -> null
        1 -> message("card.effective.play", playName(plays.single()))
        else -> message("card.effective.plays", plays.size)
    }

    /** `System`, or `playbook-setup-system.yml #2` for an unnamed play. */
    @NlsSafe
    fun playName(play: PlayRef): String = play.name ?: "${play.file.name} #${play.playIndex}"

    // ------------------------------------------------------------------------------------------------ definitions

    /**
     * `group_vars/all/vars.yml:156`: [file] relative to [root], else to the root whose inventory [root] uses (a nested
     * playbook root's parent), else to the project, with the 1-based line of [offset].
     */
    @NlsSafe
    fun label(project: Project, root: AnsibleRoot, file: VirtualFile, offset: Int): String {
        val owner = AnsibleContextServiceImpl.getInstance(project)?.inventoryRoot(root) ?: root
        val path = VfsUtilCore.getRelativePath(file, root.dir)
            ?: VfsUtilCore.getRelativePath(file, owner.dir)
            ?: RootKeys.relativePath(project, file)
            ?: file.presentableUrl
        return "$path:${VarLocations.line(file, offset)}"
    }

    @NlsSafe
    fun label(project: Project, root: AnsibleRoot, ref: VarSourceRef): String = label(project, root, ref.file, ref.offset)

    /** A link to the card of the definition [ref] names, labelled `path:line`. */
    fun definitionLink(project: Project, root: AnsibleRoot, ref: VarSourceRef): HtmlChunk = definitionLink(ref, label(project, root, ref))

    /** A link to the card of the definition [ref] names, labelled [text]. */
    fun definitionLink(ref: VarSourceRef, @Nls text: String): HtmlChunk = HtmlChunk.link(VarLinks.definition(SourceLocation(ref.file, ref.offset)), text)

    /**
     * The layer of [ref] in the card's words (`playbook group_vars/all`, `inventory host_vars/prod-prod1`, `role
     * defaults`), the same labels the card's "Set in" and "This definition" rows use.
     */
    @Nls
    fun layerName(ref: VarSourceRef): String = layerName(ref.layer, ref.group ?: ref.host ?: ref.role.orEmpty())

    @Nls
    fun layerName(layer: VarsLayer, owner: String): String = AnsibilityVarsBundle.message("layer.${layer.name}", owner)

    /** `L5 playbook group_vars/all`; molecule inventories have no level of their own. */
    @Nls
    fun levelAndLayer(ref: VarSourceRef): String =
        if (ref.layer == VarsLayer.MOLECULE_INVENTORY) layerName(ref) else message("card.effective.layer", ref.layer.level, layerName(ref))

    /** Whether [layer] is environment data (levels 3–10 and molecule inventories) rather than a play's or a role's own source. */
    fun isInventory(layer: VarsLayer): Boolean = layer.level in 3..10

    // ------------------------------------------------------------------------------------------------ values

    /**
     * The value of [ref] for the card: its preview as inline code, or the masked form of a secret. A `!vault` value
     * reads `🔒 vault-encrypted (AES256, 1.1)` (header facts from [VaultStatusService], which never decrypts) followed
     * by the Reveal link when the envelope can be revealed; a value of a vault file or a `vault_*` name reads
     * `🔒 value hidden`.
     */
    fun value(project: Project, ref: VarSourceRef): HtmlChunk {
        val preview = ref.preview
        if (preview != null && !ref.isVault) return HtmlChunk.tag("code").addText(preview)
        if (!ref.isVault) return HtmlChunk.text(message("card.value.hidden"))
        val location = SourceLocation(ref.file, ref.offset)
        val status = VaultStatusService.getInstance(project).status(location)
            ?: return HtmlChunk.text(message("card.value.vault.plain"))
        val header = status.header
        val summary = listOfNotNull(header.cipher.takeIf { it.isNotEmpty() }, header.version.takeIf { it.isNotEmpty() }, header.label).joinToString(", ")
        val text = HtmlChunk.text(if (summary.isEmpty()) message("card.value.vault.plain") else message("card.value.vault", summary))
        val revealable = status.plaintextLength != null && status.lockState != VaultLockState.NO_IDENTITY
        if (!revealable) return text
        val linkText = message(if (status.lockState == VaultLockState.LOCKED) "card.value.unlock" else "card.value.reveal")
        return HtmlChunk.fragment(text, HtmlChunk.text(SEPARATOR), HtmlChunk.link(VaultCardLinks.reveal(location), linkText))
    }

    /**
     * Where a bare `{{ other }}` value leads ([ResolvedChain]): `→ str 192.0.2.33 via system_ip_floating · <link>`,
     * `→ 🔒 vault-encrypted via vault_x · <link>` (never the value), `→ {{ … }} via x (Jinja, evaluated at runtime)`, or
     * `→ x is not set`.
     */
    fun chain(project: Project, root: AnsibleRoot, chain: ResolvedChain): HtmlChunk {
        val via = chain.via.joinToString(" → ")
        val end = chain.end
        val text = when (chain.kind) {
            ChainEnd.VALUE -> HtmlChunk.fragment(
                HtmlChunk.text(message("card.effective.chain.type", chain.typeName.orEmpty()) + " "),
                HtmlChunk.tag("code").addText(end?.preview.orEmpty()),
                HtmlChunk.text(" " + message("card.effective.chain.via", via)),
            )
            ChainEnd.SECRET -> HtmlChunk.text(message("card.effective.chain.secret", via))
            ChainEnd.TEMPLATE -> HtmlChunk.text(message("card.effective.chain.template", end?.preview.orEmpty(), via))
            ChainEnd.UNDEFINED -> HtmlChunk.text(message("card.effective.chain.undefined", via))
        }
        return if (end == null) text else joined(listOf(text, definitionLink(project, root, end)))
    }

    // ------------------------------------------------------------------------------------------------ HTML

    fun grayed(@Nls text: String): HtmlChunk = HtmlChunk.text(text).wrapWith(DocumentationMarkup.GRAYED_ELEMENT)

    /** [chunks] with [separator] between them. */
    fun joined(chunks: List<HtmlChunk>, separator: HtmlChunk = HtmlChunk.text(SEPARATOR)): HtmlChunk =
        HtmlChunk.fragment(*chunks.flatMapIndexed { index, chunk -> if (index == 0) listOf(chunk) else listOf(separator, chunk) }.toTypedArray())

    /** [lines] as one block with a line break between them. */
    fun lines(lines: List<HtmlChunk>): HtmlChunk = joined(lines, HtmlChunk.br())
}
