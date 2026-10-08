package de.terletzkiy.ansibility.vault.monitor

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.index.secrets.SecretIndex
import de.terletzkiy.ansibility.index.secrets.SecretIndexKind
import de.terletzkiy.ansibility.index.secrets.SecretVerdict
import de.terletzkiy.ansibility.index.vault.VaultIndexEntry
import de.terletzkiy.ansibility.index.vault.VaultIndexQueries
import de.terletzkiy.ansibility.inspections.vault.PlaintextKeyChecks
import de.terletzkiy.ansibility.inspections.vault.VaultEnvelopeChecks
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.secrets.PrivateKeySignatures
import de.terletzkiy.ansibility.semantics.vault.VaultShapeKind
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.FindingContext
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.settings.SeverityPolicy
import de.terletzkiy.ansibility.settings.isMoreSevereThan
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.envelope.WholeFileShapes
import de.terletzkiy.ansibility.vault.keys.PlaintextKeyExclusions
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultFile
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatuses

/**
 * Builds a [SecretSnapshot] (plan amendment R21, D165) from the indexes, without opening any file:
 * - `ansibility.secrets` ([SecretIndex]) over the project's content: ANS-V107 shapes, private keys, keystores and
 *   key-like names (confirmed below the content root, as the inspection does; the indexer leaves out files of password
 *   hashes only, `semantics.secrets.PasswordHashes`);
 * - `ansible.vault` per root (`VaultIndexQueries.malformed`): malformed envelopes, ANS-V101–V103; a file with an
 *   ANS-V107 shape gets that one code only (a `!vault` vars document is no malformed value);
 * - the vault password files of every root (`VaultFileOperations.isPasswordFile`: not scripts, not `.env.local`;
 *   only the first 14 bytes are read, to leave a vaulted password file out): ERROR when committed or staged.
 *
 * Applied as the inspections do: detached worktrees, decrypted tabs and Ansibility's ignored paths are left out, the
 * plaintext key allowlist (`vault.keys.PlaintextKeyExclusions`) silences ANS-V108, the VCS status from `statusOf`
 * decides ANS-V108's severity (git-ignored files are not listed at all), and every severity comes from
 * [SeverityPolicy]. A file outside every Ansible root is listed under the root of its VCS repository when that
 * repository holds one (outermost) root, else under the repository or, without one, its content root. Call in a smart
 * read action.
 */
internal object SecretSnapshotBuilder {
    /** The shapes the inspection reports only in files Ansible reads as they are (confirmed at query time). */
    private val RAW_ONLY = setOf(VaultShapeKind.QUOTED, VaultShapeKind.MIXED, VaultShapeKind.YAML_VALUE)

    private class Raw {
        val verdicts = ArrayList<SecretVerdict>()
        val malformed = ArrayList<VaultIndexEntry>()
        var passwordFileOf: AnsibleRoot? = null
    }

    /**
     * A snapshot and the VCS statuses it was built with (every file whose status was asked, also the ignored ones), so
     * a status event can be checked against them without a new build.
     */
    class Built(val snapshot: SecretSnapshot, val statuses: Map<VirtualFile, TrackedStatus>)

    /**
     * The snapshot of [project] with the VCS statuses of [statusOf]; [statusesKnown] false while the VCS has not reported
     * them yet: the ANS-V108 findings, which depend on them, are left out until then.
     */
    fun build(project: Project, statusOf: (VirtualFile) -> TrackedStatus, statusesKnown: Boolean = true): SecretSnapshot =
        buildWithStatuses(project, statusOf, statusesKnown).snapshot

    /** [build], with the statuses it asked for. */
    fun buildWithStatuses(project: Project, statusOf: (VirtualFile) -> TrackedStatus, statusesKnown: Boolean = true): Built {
        val workspace = AnsibleWorkspace.getInstance(project)
        val roots = workspace.roots().filter { !it.detached }
        if (roots.isEmpty()) return Built(SecretSnapshot(emptyList(), 0, computed = true, statusesKnown), emptyMap())
        val raw = LinkedHashMap<VirtualFile, Raw>()
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.projectScope(project)
        for (kind in SecretIndexKind.entries) {
            index.processValues(SecretIndex.NAME, kind.name, null, { file, verdicts ->
                ProgressManager.checkCanceled()
                raw.getOrPut(file, ::Raw).verdicts += verdicts
                true
            }, scope)
        }
        val seenMalformed = HashSet<Pair<VirtualFile, Int>>()
        for (root in roots) {
            for (hit in VaultIndexQueries.malformed(project, root)) {
                ProgressManager.checkCanceled()
                if (seenMalformed.add(hit.file to hit.value.offset)) raw.getOrPut(hit.file, ::Raw).malformed += hit.value
            }
            for (source in passwordFiles(project, root)) raw.getOrPut(source, ::Raw).passwordFileOf = root
        }
        val groups = Groups(project, roots)
        val findings = ArrayList<SecretFinding>()
        val statuses = HashMap<VirtualFile, TrackedStatus>()
        val settings = AnsibilityProjectSettings.getInstance(project)
        for ((file, found) in raw) {
            ProgressManager.checkCanceled()
            if (!file.isValid || file.isDirectory || DecryptedVaultFile.isDecryptedTab(file)) continue
            val root = workspace.rootFor(file)
            if (root?.detached == true || settings.isIgnored(file)) continue
            val group = groups.of(file, root) ?: continue
            val status = statusOf(file)
            statuses[file] = status
            if (status == TrackedStatus.IGNORED) continue
            val path = VfsUtilCore.getRelativePath(file, group.dir, '/') ?: VfsUtilCore.findRelativePath(group.dir, file, '/') ?: file.name
            val context = FileFacts(project, file, root, group, path, status)
            findings += vaultFindings(context, found)
            if (statusesKnown && !PlaintextKeyExclusions.isAllowlisted(project, file)) findings += keyFindings(context, found)
        }
        findings.sortWith(compareBy<SecretFinding>({ groups.order(it.group) }, { it.group.title }, { it.group.id }, { it.category.ordinal }, { it.path }))
        return Built(SecretSnapshot(findings, roots.size, computed = true, statusesKnown), statuses)
    }

    /** What every finding of one file shares. */
    private class FileFacts(
        val project: Project,
        val file: VirtualFile,
        val root: AnsibleRoot?,
        val group: SecretGroup,
        val path: String,
        val status: TrackedStatus,
    ) {
        val policy: SeverityPolicy get() = SeverityPolicy.getInstance(project)

        fun finding(category: SecretCategory, code: DiagnosticCode, level: Level, details: List<String>, line: Int, offset: Int, fix: SecretFix?) =
            SecretFinding(file, group, path, category, code, level, status, details, line, offset, fix)
    }

    // ------------------------------------------------------------------------------------------------ vault files

    /** ANS-V107, or else ANS-V101–V103 of the file's whole-file vault and of its values. */
    private fun vaultFindings(facts: FileFacts, found: Raw): List<SecretFinding> {
        val shapes = found.verdicts.filter { verdict ->
            verdict.kind == SecretIndexKind.NOT_WHOLE_FILE && verdict.shape != null &&
                (verdict.shape !in RAW_ONLY || WholeFileShapes.readsRaw(facts.project, facts.file))
        }
        if (shapes.isNotEmpty()) {
            val level = facts.policy.level(DiagnosticCode.V107_NOT_WHOLE_FILE_VAULT, facts.root)
            if (level == Level.OFF) return emptyList()
            val convertible = shapes.any { val shape = it.shape!!; shape.isWrapped && shape != VaultShapeKind.BYTE_ORDER_MARK }
            val details = shapes.map { SecretTexts.shape(it.shape!!, it.line) }
            return listOf(
                facts.finding(
                    SecretCategory.BROKEN_VAULT_FILES, DiagnosticCode.V107_NOT_WHOLE_FILE_VAULT, level, details, shapes.first().line, -1,
                    if (convertible) SecretFix.CONVERT else null,
                ),
            )
        }
        if (found.malformed.isEmpty()) return emptyList()
        val findings = ArrayList<SecretFinding>()
        val (wholeFile, inline) = found.malformed.partition { it.kind == VaultEnvelopeKind.FILE }
        malformedFinding(facts, wholeFile, SecretCategory.BROKEN_VAULT_FILES)?.let(findings::add)
        malformedFinding(facts, inline, SecretCategory.BROKEN_VAULT_VALUES)?.let(findings::add)
        return findings
    }

    private fun malformedFinding(facts: FileFacts, entries: List<VaultIndexEntry>, category: SecretCategory): SecretFinding? {
        val coded = entries.mapNotNull { entry ->
            val code = VaultEnvelopeChecks.codeOf(entry.problem, entry.hint, entry.style) ?: return@mapNotNull null
            val level = facts.policy.level(code, facts.root)
            if (level == Level.OFF) null else Triple(entry, code, level)
        }
        if (coded.isEmpty()) return null
        val worst = coded.reduce { a, b -> if (b.third.isMoreSevereThan(a.third)) b else a }
        val codes = coded.map { it.second.id }.distinct()
        val details = if (category == SecretCategory.BROKEN_VAULT_FILES) {
            listOf(message("monitor.detail.malformed.file", codes.joinToString(", ")))
        } else {
            listOf(message("monitor.detail.malformed.values", coded.size, codes.joinToString(", ")))
        }
        return facts.finding(category, worst.second, worst.third, details, -1, coded.first().first.offset, null)
    }

    // ------------------------------------------------------------------------------------------------ keys

    /** ANS-V108: a committed vault password file, or keys and keystores, or else a key-like name. */
    private fun keyFindings(facts: FileFacts, found: Raw): List<SecretFinding> {
        val uncommitted = facts.status == TrackedStatus.UNTRACKED
        found.passwordFileOf?.let { source ->
            // Without version control a local password file is the normal setup (as the inspection decides).
            if (facts.status == TrackedStatus.NO_VCS) return emptyList()
            val level = facts.policy.level(DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY, facts.root, FindingContext(uncommitted = uncommitted))
            if (level == Level.OFF) return emptyList()
            val details = listOf(message("monitor.detail.password", source.displayName))
            return listOf(facts.finding(SecretCategory.VAULT_PASSWORD_FILES, DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY, level, details, -1, -1, null))
        }
        val encrypts = PlaintextKeyChecks.encryptsFile(facts.project, facts.file, facts.root)
        val keys = found.verdicts.filter { it.kind == SecretIndexKind.TEXT_KEY || it.kind == SecretIndexKind.BINARY_KEY }
        if (keys.isNotEmpty()) {
            val levels = keys.mapNotNull { verdict ->
                val context = FindingContext(weakSecretSignal = isWeak(verdict), uncommitted = uncommitted)
                facts.policy.level(DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY, facts.root, context).takeIf { it != Level.OFF }?.let { verdict to it }
            }
            if (levels.isEmpty()) return emptyList()
            val worst = levels.reduce { a, b -> if (b.second.isMoreSevereThan(a.second)) b else a }.second
            val details = levels.map { (verdict, _) -> SecretTexts.key(verdict) }.distinct()
            val line = keys.firstOrNull { it.line >= 0 }?.line ?: -1
            // A key in plaintext makes it a plaintext key file; protected keys and keystores alone are weaker (D161).
            val category = if (levels.any { !isWeak(it.first) }) SecretCategory.PLAINTEXT_KEYS else SecretCategory.PROTECTED_KEYS
            return listOf(
                facts.finding(
                    category, DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY, worst, details, line, -1,
                    if (encrypts) SecretFix.ENCRYPT else null,
                ),
            )
        }
        if (found.verdicts.none { it.kind == SecretIndexKind.KEY_LIKE_NAME }) return emptyList()
        val name = PlaintextKeyChecks.keyLikeName(facts.project, facts.file) ?: return emptyList()
        val level = facts.policy.level(
            DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY, facts.root, FindingContext(weakSecretSignal = true, uncommitted = uncommitted),
        )
        if (level == Level.OFF) return emptyList()
        val details = listOf(message("monitor.detail.key.like", PlaintextKeyChecks.nameText(name)))
        return listOf(
            facts.finding(
                SecretCategory.KEY_LIKE_FILES, DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY, level, details, -1, -1,
                if (encrypts) SecretFix.ENCRYPT else null,
            ),
        )
    }

    /** A weaker signal (D161, `PrivateKeySignatures.isWeakSignal`): at most WARNING. */
    private fun isWeak(verdict: SecretVerdict): Boolean {
        val format = verdict.format ?: return true
        val protection = verdict.protection ?: return true
        return PrivateKeySignatures.isWeakSignal(format, protection)
    }

    // ------------------------------------------------------------------------------------------------ sources

    /**
     * The vault password files of [root] that are files of the project, not empty and not vaulted themselves (Ansible
     * decrypts a vaulted password file: no leak). Only their first 14 bytes are read, for the vault magic.
     */
    private fun passwordFiles(project: Project, root: AnsibleRoot): List<VirtualFile> {
        val operations = VaultFileOperations.getInstance(project)
        val paths = operations.passwordFiles(root)
        if (paths.isEmpty()) return emptyList()
        val fileIndex = ProjectFileIndex.getInstance(project)
        return paths.mapNotNull { path ->
            ProgressManager.checkCanceled()
            LocalFileSystem.getInstance().findFileByNioFile(path)
                ?.takeIf { !it.isDirectory && it.length > 0 && fileIndex.isInContent(it) && operations.isPasswordFile(root, it) }
                ?.takeIf { !VaultEnvelopes.isWholeFileVault(it) }
        }.distinct()
    }

    // ------------------------------------------------------------------------------------------------ groups

    /**
     * The repository of each file (D165: "grouped by repo"): its Ansible root; outside every root, the one (outermost)
     * Ansible root of its VCS repository (`TrackedStatuses.repositoryOf`: `repos/thrush/certs/web.key` is listed under
     * `repos/thrush/ansible`), or the repository itself when it holds none or several; without a VCS repository, its
     * content root ("Other files in …").
     */
    private class Groups(private val project: Project, private val roots: List<AnsibleRoot>) {
        private val byDir = HashMap<VirtualFile, SecretGroup>()
        private val fileIndex = ProjectFileIndex.getInstance(project)
        private val statuses = TrackedStatuses.getInstance(project)

        private fun rootGroup(root: AnsibleRoot): SecretGroup =
            byDir.getOrPut(root.dir) { SecretGroup(RootKeys.keyOf(project, root.dir), root.displayName, root.dir, isRoot = true) }

        fun of(file: VirtualFile, root: AnsibleRoot?): SecretGroup? {
            if (root != null) return rootGroup(root)
            statuses.repositoryOf(file)?.let { repository ->
                val inside = roots.filter { VfsUtilCore.isAncestor(repository, it.dir, false) }
                val outermost = inside.filter { candidate -> inside.none { it !== candidate && VfsUtilCore.isAncestor(it.dir, candidate.dir, true) } }
                outermost.singleOrNull()?.let { return rootGroup(it) }
                return byDir.getOrPut(repository) {
                    SecretGroup(REPOSITORY_PREFIX + RootKeys.keyOf(project, repository), repository.name, repository, isRoot = false)
                }
            }
            val contentRoot = fileIndex.getContentRootForFile(file) ?: return null
            return byDir.getOrPut(contentRoot) {
                SecretGroup(OTHER_PREFIX + RootKeys.keyOf(project, contentRoot), message("monitor.group.other", contentRoot.name), contentRoot, isRoot = false)
            }
        }

        /** Roots in the workspace's order, then the other groups by title. */
        fun order(group: SecretGroup): Int {
            if (!group.isRoot) return roots.size
            return roots.indexOfFirst { it.dir == group.dir }.takeIf { it >= 0 } ?: roots.size
        }

        private companion object {
            const val OTHER_PREFIX = "other:"
            const val REPOSITORY_PREFIX = "repository:"
        }
    }
}
