package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.index.vault.VaultIndexQueries
import de.terletzkiy.ansibility.semantics.vault.EncryptIdentity
import de.terletzkiy.ansibility.semantics.vault.GenerationRule
import de.terletzkiy.ansibility.semantics.vault.SecretGenerator
import de.terletzkiy.ansibility.semantics.vault.SecretShape
import de.terletzkiy.ansibility.vault.AnsibilityVaultBundle
import de.terletzkiy.ansibility.vault.VaultCorpusGuardException
import de.terletzkiy.ansibility.vault.VaultLog
import de.terletzkiy.ansibility.vault.actions.PlainValueRef
import de.terletzkiy.ansibility.vault.actions.VaultActionPrompts
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.actions.VaultIdentityChoice
import de.terletzkiy.ansibility.vault.actions.VaultIdentityRequest
import de.terletzkiy.ansibility.vault.actions.VaultValueActions
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.reencrypt.Decryption
import de.terletzkiy.ansibility.vault.reencrypt.Encryption
import de.terletzkiy.ansibility.vault.reencrypt.Inspection
import de.terletzkiy.ansibility.vault.reencrypt.ItemOutcome
import de.terletzkiy.ansibility.vault.reencrypt.NewPlaintext
import de.terletzkiy.ansibility.vault.reencrypt.PreparedItem
import de.terletzkiy.ansibility.vault.reencrypt.PreparedPlan
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptItem
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptTarget
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionEngine
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionRun
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionWriter
import de.terletzkiy.ansibility.vault.reencrypt.SharedValue
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import de.terletzkiy.ansibility.vault.ui.VaultUiFeedback
import de.terletzkiy.ansibility.yaml.YamlPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Regenerate Secrets… (plan amendment R29, Part 2, D215–D222): new random values for chosen vault values, encrypted
 * directly, so no plaintext reaches the disk at any moment. Also the single-value intentions "Regenerate secret" (X97)
 * and "Generate and encrypt" (a plaintext `vault_*` value).
 *
 * The flow, on the re-encryption engine ([ReencryptionEngine], [ReencryptionWriter]):
 * 1. save the selection's documents; scan it in a smart read action: inline `!vault` values of files the `ansible.vault`
 *    index lists, plaintext `vault_*` values of the files Ansible loads vars from ([PlainValueRef.placeholder]), and
 *    whole-file vaults (listed, never regenerated, D216);
 * 2. decrypt every `!vault` value in memory (one unlock per root): its shape ([SecretShape], D218 rule 2) and a digest
 *    keyed with a random key of this run, so equal values can be grouped (D219) without keeping any plaintext; the
 *    plaintext is zeroed right after;
 * 3. the selection dialog ([RegeneratePrompts.chooseValues], D217): rules of the root first, then "same as now", then
 *    32 alphanumeric (D218);
 * 4. generate (an injected [SecureRandom]; one value per equal group, handed to the engine, which zeroes it after
 *    encrypting), encrypt with the id and header each value had (placeholders with the id Ansible's rule picks, asked
 *    once per root when ambiguous; D220), verify;
 * 5. write everything in one undoable command, all or nothing, with Local History labels (write mode 2, D221);
 * 6. the summary (D222) with, per variable, the roles and templates that use it (X130, a background smart read).
 *
 * Logs name files, keys and failure classes only; the summary and the dialog never contain a value.
 */
@Service(Service.Level.PROJECT)
class RegenerateService(private val project: Project, private val scope: CoroutineScope) {
    private val engine = ReencryptionEngine(project)
    private val writer = ReencryptionWriter(project) { VaultFileOperations.FileModes.POSIX }

    @Volatile
    private var random: SecureRandom = SecureRandom()

    @Volatile
    private var lastJob: Job? = null

    /** The running or last regeneration, for tests that wait for it. */
    @get:TestOnly
    val lastRun: Job? get() = lastJob

    /** Generates with [random] (tests pass a seeded one, so they know the new values) until [parent] is disposed. */
    @TestOnly
    fun setRandomForTests(random: SecureRandom, parent: Disposable) {
        this.random = random
        Disposer.register(parent) { this.random = SecureRandom() }
    }

    // ------------------------------------------------------------------------------------------------ Regenerate Secrets…

    /** Regenerate Secrets… on [selection]: files, folders, roots and repositories. EDT. */
    fun regenerate(selection: List<VirtualFile>) {
        ThreadingAssertions.assertEventDispatchThread()
        VaultFileOperations.getInstance(project).saveDocuments(selection)
        launch(message("regenerate.progress"), null) { state ->
            val candidates = smartReadAction(project) { scan(selection) }
            if (candidates.none { it.kind != RowKind.VAULT_FILE }) return@launch onEdt { VaultUiFeedback.info(project, null, message("regenerate.none")) }
            val run = ReencryptionRun(VaultPurpose.REKEY)
            try {
                val inspected = inspect(candidates, run)
                val request = readAction { rows(candidates, inspected) }
                if (!onEdt { RegeneratePrompts.getInstance().chooseValues(project, request) }) return@launch
                // The dialog may have stayed open past an auto-lock or a Lock All: unlock again, once per root.
                run.resumeAfterPause()
                val selected = request.selected
                if (selected.isEmpty()) return@launch onEdt { VaultUiFeedback.info(project, null, message("regenerate.nothing.selected")) }
                val identities = onEdt { placeholderIdentities(selected, candidates) } ?: return@launch
                val items = items(request, selected, candidates, identities)
                val plan = dropBrokenGroups(engine.prepare(items, run), request)
                finish(plan, null, state)
            } finally {
                forgetOldPlaintexts(candidates)
            }
        }
    }

    /** A value of the selection: where it is and what the dialog shows of it. Not secret. */
    private class Candidate(
        val file: VirtualFile,
        val root: AnsibleRoot,
        val target: ReencryptTarget,
        val keyPath: String,
        val keyName: String?,
        val kind: RowKind,
        @Nls val repository: String,
    ) {
        override fun toString(): String = "Candidate(${file.name}, $keyPath, $kind)"
    }

    /**
     * The values of [selection] in file and document order: `!vault` values of the YAML files the `ansible.vault` index
     * lists, plaintext `vault_*` values of vars files, and whole-file vaults. A detached worktree's root (DEV rule 6) is
     * scanned only when something selected lies inside that worktree, never for a folder above it. Smart read action.
     */
    private fun scan(selection: List<VirtualFile>): List<Candidate> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val psi = PsiManager.getInstance(project)
        val types = FileTypeRegistry.getInstance()
        val result = ArrayList<Candidate>()
        val detachedSelected = HashMap<AnsibleRoot, Boolean>()
        for (file in VaultFileOperations.getInstance(project).filesOf(selection)) {
            ProgressManager.checkCanceled()
            val root = workspace.rootFor(file) ?: continue
            if (root.detached && !detachedSelected.getOrPut(root) { selection.any { VfsUtilCore.isAncestor(worktreeDirOf(root), it, false) } }) continue
            val repository = repositoryOf(file)
            if (VaultEnvelopes.isWholeFileVault(file)) {
                result += Candidate(file, root, ReencryptTarget.WholeFile(file), file.name, null, RowKind.VAULT_FILE, repository)
                continue
            }
            if (!types.isFileOfType(file, YAMLFileType.YML)) continue
            val envelopes = VaultIndexQueries.envelopes(project, file).isNotEmpty()
            val placeholders = mentions(file, PLACEHOLDER_MARK)
            if (!envelopes && !placeholders) continue
            val psiFile = psi.findFile(file) ?: continue
            val found = ArrayList<Pair<Int, Candidate>>()
            if (envelopes) {
                for (scalar in VaultValuePsi.vaultScalars(psiFile)) {
                    val ref = VaultValueRef.of(scalar) ?: continue
                    found += scalar.textRange.startOffset to Candidate(file, root, ReencryptTarget.Value(ref), keyPath(scalar), ref.keyName, RowKind.VAULT_VALUE, repository)
                }
            }
            if (placeholders) {
                for (keyValue in PsiTreeUtil.findChildrenOfType(psiFile, YAMLKeyValue::class.java)) {
                    ProgressManager.checkCanceled()
                    val scalar = keyValue.value as? YAMLScalar ?: continue
                    val ref = PlainValueRef.placeholder(scalar) ?: continue
                    found += scalar.textRange.startOffset to Candidate(file, root, ReencryptTarget.PlainValue(ref), keyPath(scalar), ref.keyName, RowKind.PLAINTEXT, repository)
                }
            }
            result += found.sortedBy { it.first }.map { it.second }
        }
        return result
    }

    private fun keyPath(scalar: YAMLScalar): String = YamlPaths.keyPath(scalar).joinToString(".")

    /** The detached worktree [root] lies in (its directory), else the root's own directory. Read action. */
    private fun worktreeDirOf(root: AnsibleRoot): VirtualFile = AnsibleWorkspaceImpl.getInstance(project)?.worktreeOf(root)?.dir ?: root.dir

    /** Which side of DEV rule 6 [root] is on: "" for every normal root, the worktree's path for a detached one. Read action. */
    private fun sideOf(root: AnsibleRoot): String = if (root.detached) worktreeDirOf(root).path else ""

    /** The repository of [file] as the tree shows it: the repository directory's name, else the project's. Read action. */
    @Nls
    private fun repositoryOf(file: VirtualFile): String =
        de.terletzkiy.ansibility.vault.vcs.TrackedStatuses.getInstance(project).repositoryOf(file)?.name ?: project.name

    /** True when [file] (up to 16 MiB) contains [mark]: only those are parsed for placeholders. */
    private fun mentions(file: VirtualFile, mark: ByteArray): Boolean {
        if (file.length > MAX_SCANNED_BYTES) return false
        val bytes = try {
            file.contentsToByteArray()
        } catch (_: IOException) {
            return false
        }
        return (0..bytes.size - mark.size).any { start -> mark.indices.all { bytes[start + it] == mark[it] } }
    }

    /** What step 2 learnt about one `!vault` value: its shape and a keyed digest of its plaintext. Not secret. */
    private class Facts(val shape: SecretShape, val digest: String) {
        override fun toString(): String = "Facts($shape)"
    }

    /**
     * Decrypts the `!vault` values of [candidates] (one unlock per root) and keeps only their shapes and digests keyed
     * with a random key of this run ([KeyedDigest]), which is zeroed afterwards with every copy of it: equal plaintexts
     * give equal digests, and no digest says anything about a value once the key is gone (D219).
     */
    private suspend fun inspect(candidates: List<Candidate>, run: ReencryptionRun): Map<Candidate, Inspection<Facts>> {
        val values = candidates.filter { it.kind == RowKind.VAULT_VALUE }
        if (values.isEmpty()) return emptyMap()
        val items = values.map { ReencryptItem(it.root, it.target, Decryption.RootSecrets, NewPlaintext.Keep, Encryption.SameAsBefore, tag = it) }
        KeyedDigest().use { digest ->
            val inspections = engine.inspect(items, run) { _, bytes -> bytes?.let { Facts(SecretShape.of(it), digest.of(it)) } }
            @Suppress("UNCHECKED_CAST")
            return inspections.associateBy { it.item.tag as Candidate } as Map<Candidate, Inspection<Facts>>
        }
    }

    /**
     * HMAC-SHA256 (RFC 2104) under a random key of one run (D219), computed with [MessageDigest] over the key pads this
     * class owns, so [close] zeroes every copy of the key: a `Mac` with a `SecretKeySpec` would keep copies of its own
     * until garbage collection (plan amendment R29 review, finding 9). The intermediate digest is zeroed too.
     */
    private class KeyedDigest : AutoCloseable {
        private val innerPad = ByteArray(SHA256_BLOCK)
        private val outerPad = ByteArray(SHA256_BLOCK)

        init {
            val key = ByteArray(DIGEST_KEY_BYTES).also(SecureRandom()::nextBytes)
            try {
                for (i in 0 until SHA256_BLOCK) {
                    val k = if (i < key.size) key[i].toInt() else 0
                    innerPad[i] = (k xor 0x36).toByte()
                    outerPad[i] = (k xor 0x5c).toByte()
                }
            } finally {
                key.fill(0)
            }
        }

        /** The keyed digest of [value], in hex. */
        fun of(value: ByteArray): String {
            val sha = MessageDigest.getInstance(SHA256)
            sha.update(innerPad)
            sha.update(value)
            val inner = sha.digest()
            try {
                sha.update(outerPad)
                sha.update(inner)
                return sha.digest().joinToString("") { "%02x".format(it) }
            } finally {
                inner.fill(0)
            }
        }

        override fun close() {
            innerPad.fill(0)
            outerPad.fill(0)
        }

        override fun toString(): String = "KeyedDigest(***)"
    }

    /** The dialog's rows: shapes, rules, marks and the equal-value groups (D217–D219). */
    private fun rows(candidates: List<Candidate>, inspected: Map<Candidate, Inspection<Facts>>): RegenerateRequest {
        val rulesByRoot = HashMap<AnsibleRoot, List<GenerationRule>>()
        val identities = HashMap<VirtualFile, EncryptIdentity.Choice>()
        val rows = ArrayList<RegenerateRow>()
        val digests = HashMap<RegenerateRow, String>()
        for ((index, candidate) in candidates.withIndex()) {
            val rules = rulesByRoot.getOrPut(candidate.root) { rulesOf(candidate.root) }
            val rule = GenerationRule.firstMatch(rules, candidate.keyName)
            rows += when (candidate.kind) {
                RowKind.VAULT_FILE -> RegenerateRow(
                    index, candidate.file, candidate.root, candidate.repository, candidate.keyPath, null, RowKind.VAULT_FILE, null, null,
                    message("regenerate.reason.vault.file"), null, SecretGenerator.DEFAULT, GeneratorSource.DEFAULT,
                )
                RowKind.VAULT_VALUE -> {
                    val inspection = inspected[candidate]
                    val facts = inspection?.result
                    val failure = inspection?.outcome?.let(::outcomeText) ?: message("regenerate.reason.not.decrypted")
                    val shape = facts?.shape
                    val (generator, source) = generatorFor(rule, (shape as? SecretShape.Password)?.generator)
                    RegenerateRow(
                        index, candidate.file, candidate.root, candidate.repository, candidate.keyPath, candidate.keyName, RowKind.VAULT_VALUE,
                        inspection?.identity, (shape as? SecretShape.NotPassword)?.reason, if (facts == null) message("regenerate.reason.failed", failure) else null,
                        rule, generator, source,
                    ).also { row -> facts?.let { digests[row] = sideOf(candidate.root) + DIGEST_SEPARATOR + it.digest } }
                }
                RowKind.PLAINTEXT -> {
                    val ref = (candidate.target as ReencryptTarget.PlainValue).ref
                    val choice = identities.getOrPut(candidate.file) { encryptChoice(ref) }
                    val bytes = ref.value.toByteArray(Charsets.UTF_8)
                    val reason = try {
                        (SecretShape.of(bytes) as? SecretShape.NotPassword)?.reason?.takeIf { bytes.isNotEmpty() }
                    } finally {
                        bytes.fill(0)
                    }
                    val (generator, source) = generatorFor(rule, null)
                    RegenerateRow(
                        index, candidate.file, candidate.root, candidate.repository, candidate.keyPath, candidate.keyName, RowKind.PLAINTEXT,
                        (choice as? EncryptIdentity.Choice.Chosen)?.label, reason,
                        message("regenerate.reason.no.identity").takeIf { choice is EncryptIdentity.Choice.NotFound || choice == EncryptIdentity.Choice.NoIdentity },
                        rule, generator, source,
                    )
                }
            }
        }
        // D219: values equal now get one new value together; the group takes its first row's generator and check. The
        // digests carry the side of DEV rule 6, so a group never spans a detached worktree and a normal root.
        var groupId = 0
        for ((_, members) in digests.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }) {
            val id = ++groupId
            val first = members.minBy { it.id }
            for (row in members) {
                row.group = id
                row.generator = first.generator
                row.source = first.source
                row.checked = first.checked
            }
        }
        return RegenerateRequest(rows)
    }

    /** D218: the rule of the root, then "same as now", then 32 alphanumeric. */
    private fun generatorFor(rule: GenerationRule?, shape: SecretGenerator?): Pair<SecretGenerator, GeneratorSource> = when {
        rule != null -> rule.generator to GeneratorSource.RULE
        shape != null -> shape to GeneratorSource.SAME_AS_NOW
        else -> SecretGenerator.DEFAULT to GeneratorSource.DEFAULT
    }

    /** The generation rules of [root] (shared settings, R27 sub-repository files included). */
    private fun rulesOf(root: AnsibleRoot): List<GenerationRule> {
        val rootKey = VaultIdentityRegistry.getInstance(project).discovery(root).rootKey
        return VaultProjectSettings.getInstance(project).rootSettings(rootKey).generationRules
    }

    /** Ansible's encrypt-id rule for a placeholder, as Encrypt value has it (the environment mapping included, F7.9). */
    private fun encryptChoice(ref: PlainValueRef): EncryptIdentity.Choice {
        val config = VaultStatusService.getInstance(project).config(ref.root)
        val labels = config.identities.map { it.label }.distinct()
        return VaultValueActions.getInstance(project).encryptChoice(ref.root, ref.environments, ref.file, config, labels)
    }

    /**
     * The ids of the selected placeholders whose root leaves a choice, asked once per root (D220); null when you
     * cancelled. EDT.
     */
    private fun placeholderIdentities(selected: List<RegenerateRow>, candidates: List<Candidate>): Map<RegenerateRow, String>? {
        val asked = HashMap<AnsibleRoot, String>()
        val result = HashMap<RegenerateRow, String>()
        for (row in selected) {
            if (row.kind != RowKind.PLAINTEXT) continue
            row.identity?.let { result[row] = it; continue }
            val ref = (candidates[row.id].target as ReencryptTarget.PlainValue).ref
            val choice = runReadActionBlocking { encryptChoice(ref) } as? EncryptIdentity.Choice.Ambiguous ?: continue
            result[row] = asked[row.root] ?: chooseIdentity(row.root, choice)?.also { asked[row.root] = it } ?: return null
        }
        return result
    }

    private fun chooseIdentity(root: AnsibleRoot, choice: EncryptIdentity.Choice.Ambiguous): String? {
        val config = runReadActionBlocking { VaultStatusService.getInstance(project).config(root) }
        val choices = config.identities.distinctBy { it.label }.filter { it.label in choice.candidates }.map { VaultIdentityChoice(it.label, it.lockState) }
        val request = VaultIdentityRequest(message("regenerate.identity.title"), message("regenerate.identity.choose", root.displayName), choices, choice.preselected)
        return VaultActionPrompts.getInstance().chooseIdentity(project, request)
    }

    /**
     * The engine's items for the [selected] rows: one new value per equal group (D219) or row, taken by the engine when
     * the item's crypto runs; `!vault` values keep their id and header, placeholders get [identities] (D220).
     */
    private fun items(request: RegenerateRequest, selected: List<RegenerateRow>, candidates: List<Candidate>, identities: Map<RegenerateRow, String>): List<ReencryptItem> {
        val random = random
        val chosen = selected.toSet()
        val values = HashMap<Int, SharedValue>()
        val items = ArrayList<ReencryptItem>()
        for (row in selected) {
            val candidate = candidates[row.id]
            val generator = row.generator
            val users = row.group?.let { group -> request.members(group).count { it in chosen } } ?: 1
            val value = values.getOrPut(row.group?.let { -it } ?: row.id) { SharedValue(users) { generator.generate(random) } }
            items += when (row.kind) {
                RowKind.VAULT_VALUE -> ReencryptItem(row.root, candidate.target, Decryption.RootSecrets, NewPlaintext.Replace(value), Encryption.SameAsBefore, row)
                RowKind.PLAINTEXT -> {
                    val label = identities[row] ?: continue
                    ReencryptItem(row.root, candidate.target, Decryption.None, NewPlaintext.Replace(value), Encryption.ConfiguredId(label), row)
                }
                RowKind.VAULT_FILE -> continue
            }
        }
        return items
    }

    /** D219: when a value of an equal group could not be regenerated, the others are left out too, so they stay equal. */
    private fun dropBrokenGroups(plan: PreparedPlan, request: RegenerateRequest): PreparedPlan {
        val broken = plan.items.filter { it.ready == null }.mapNotNull { (it.item.tag as? RegenerateRow)?.group }.toSet()
        if (broken.isEmpty()) return plan
        val drop = plan.items.filter { (it.item.tag as? RegenerateRow)?.group in broken }.map { it.item }.toSet()
        return plan.without(drop, ItemOutcome.LeftOut)
    }

    // ------------------------------------------------------------------------------------------------ single values (X97, ANS-X001's case)

    /**
     * The intention "Regenerate secret" on one `!vault` value: a small chooser instead of the tree. The file's document
     * is saved first, as the menu action saves its selection: in the editor it usually has unsaved edits, which the
     * all-or-nothing write would take for a change made meanwhile (plan amendment R29 review, finding 2). EDT.
     */
    internal fun regenerate(ref: VaultValueRef, editor: Editor?) {
        ThreadingAssertions.assertEventDispatchThread()
        VaultFileOperations.getInstance(project).saveDocuments(listOf(ref.file))
        launch(message("regenerate.progress"), editor) { state ->
            val run = ReencryptionRun(VaultPurpose.REKEY)
            val candidate = Candidate(ref.file, ref.root, ReencryptTarget.Value(ref), ref.keyName ?: ref.file.name, ref.keyName, RowKind.VAULT_VALUE, "")
            try {
                val inspection = inspect(listOf(candidate), run).getValue(candidate)
                inspection.outcome?.let { outcome -> return@launch onEdt { report(editor, outcome) } }
                val shape = inspection.result?.shape
                val rule = readAction { GenerationRule.firstMatch(rulesOf(ref.root), ref.keyName) }
                val (preset, source) = generatorFor(rule, (shape as? SecretShape.Password)?.generator)
                val request = GeneratorRequest(
                    ref.keyName ?: ref.file.name, preset, RegenerateTexts.source(source, preset, rule), preset.characters < SecretShape.SHORT_BELOW,
                    (shape as? SecretShape.NotPassword)?.reason?.let(RegenerateTexts::reason),
                )
                val generator = onEdt { RegeneratePrompts.getInstance().chooseGenerator(project, request) } ?: return@launch
                run.resumeAfterPause()
                val random = random
                val item = ReencryptItem(ref.root, candidate.target, Decryption.RootSecrets, NewPlaintext.Replace(SharedValue(1) { generator.generate(random) }), Encryption.SameAsBefore)
                finish(engine.prepare(listOf(item), run), editor, state)
            } finally {
                forgetOldPlaintexts(listOf(candidate))
            }
        }
    }

    /**
     * "Generate and encrypt" on a plaintext `vault_*` value: the id as Encrypt value picks it (asked when ambiguous),
     * then a small chooser, then a new random value in a `!vault |` block. The file's document is saved first, as in
     * [regenerate]: the placeholder was usually just typed. EDT.
     */
    internal fun generateAndEncrypt(ref: PlainValueRef, editor: Editor?) {
        ThreadingAssertions.assertEventDispatchThread()
        VaultFileOperations.getInstance(project).saveDocuments(listOf(ref.file))
        val label = when (val choice = runReadActionBlocking { encryptChoice(ref) }) {
            is EncryptIdentity.Choice.Chosen -> choice.label
            is EncryptIdentity.Choice.Ambiguous -> chooseIdentity(ref.root, choice) ?: return
            is EncryptIdentity.Choice.NotFound, EncryptIdentity.Choice.NoIdentity -> return VaultUiFeedback.failure(project, editor, VaultFailure.NO_IDENTITY)
        }
        val rule = runReadActionBlocking { GenerationRule.firstMatch(rulesOf(ref.root), ref.keyName) }
        val (preset, source) = generatorFor(rule, null)
        val request = GeneratorRequest(ref.keyName, preset, RegenerateTexts.source(source, preset, rule), preset.characters < SecretShape.SHORT_BELOW, null)
        val generator = RegeneratePrompts.getInstance().chooseGenerator(project, request) ?: return
        launch(message("regenerate.progress"), editor) { state ->
            val random = random
            val item = ReencryptItem(
                ref.root, ReencryptTarget.PlainValue(ref), Decryption.None, NewPlaintext.Replace(SharedValue(1) { generator.generate(random) }),
                Encryption.ConfiguredId(label),
            )
            finish(engine.prepare(listOf(item), ReencryptionRun(VaultPurpose.REKEY)), editor, state)
        }
    }

    // ------------------------------------------------------------------------------------------------ writing and summary

    /**
     * Writes [plan] (mode 2, D221) and reports the outcome (D222), with the X130 lines after a successful write. A
     * Cancel before the write still writes nothing; once the write started, it and the summary run non-cancellable, so a
     * Cancel that arrives while the command runs cannot swallow the summary of a written regeneration (plan amendment
     * R29 review, finding 7). The switch to the EDT sits inside `withContext(NonCancellable)`: a `withContext` that
     * changes the dispatcher discards its result when its caller was cancelled meanwhile (kotlinx's prompt cancellation),
     * `NonCancellable` in the same call included.
     */
    private suspend fun finish(plan: PreparedPlan, editor: Editor?, state: RunState) {
        val ready = plan.items.filter { it.ready != null }
        val count = ready.size
        val job = currentCoroutineContext().job
        val outcome = if (ready.isEmpty()) ReencryptionWriter.AllOutcome.Nothing else withContext(NonCancellable) {
            onEdt {
                if (job.isCancelled) null
                else writer.writeAll(plan, message("regenerate.command", count), message("regenerate.label.before", count), message("regenerate.label.after", count))
                    .also { state.finished = true }
            }
        } ?: throw CancellationException("cancelled before the write")
        state.finished = true
        // Written is written: the summary follows even when the run is cancelled now; X130 gets a time budget.
        withContext(NonCancellable) {
            val usedBy = if (outcome is ReencryptionWriter.AllOutcome.Written) withTimeoutOrNull(USED_BY_MILLIS) { usedBy(ready) }.orEmpty() else emptyList()
            onEdt { summary(outcome, plan, usedBy, editor) }
        }
    }

    /** X130: per regenerated variable, the roles and templates that use it (a background smart read, names only). */
    private suspend fun usedBy(ready: List<PreparedItem>): List<String> {
        val names = ready.mapNotNull { prepared -> keyOf(prepared.item)?.let { prepared.item.root to it } }.distinct()
        if (names.isEmpty()) return emptyList()
        return try {
            smartReadAction(project) { names.mapNotNull { (root, name) -> UsedBy.line(project, root, name) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VaultLog.failure(VaultLog.Operation.ENCRYPT, e)
            emptyList()
        }
    }

    private fun keyOf(item: ReencryptItem): String? = when (val target = item.target) {
        is ReencryptTarget.Value -> target.ref.keyName
        is ReencryptTarget.PlainValue -> target.ref.keyName
        is ReencryptTarget.WholeFile -> null
    }

    /** The summary of a run (D222): counts and files, what was left alone and why, X130's lines; never a value. EDT. */
    private fun summary(outcome: ReencryptionWriter.AllOutcome, plan: PreparedPlan, usedBy: List<String>, editor: Editor?) {
        val leftAlone = plan.items.filter { it.ready == null && it.outcome != ItemOutcome.Unchanged }.map { prepared ->
            message("regenerate.left.item", escape(keyOf(prepared.item) ?: prepared.item.file.name), escape(prepared.item.file.name), outcomeText(prepared.outcome))
        }
        val parts = ArrayList<String>()
        var error = false
        when (outcome) {
            is ReencryptionWriter.AllOutcome.Written -> {
                parts += message("regenerate.done", outcome.values, outcome.files.size)
                parts += message("regenerate.done.copy")
            }
            is ReencryptionWriter.AllOutcome.NotWritten -> {
                error = true
                val name = escape(outcome.stale.file.name)
                parts += when (outcome.stale) {
                    is ReencryptionWriter.Stale.ReadOnly -> message("regenerate.stale.read.only", name)
                    is ReencryptionWriter.Stale.TabModified -> message("regenerate.stale.tab", name)
                    is ReencryptionWriter.Stale.Changed -> message("regenerate.stale.changed", name)
                }
            }
            is ReencryptionWriter.AllOutcome.Failed -> {
                error = true
                parts += message("regenerate.write.failed", escape(outcome.file.name))
            }
            ReencryptionWriter.AllOutcome.Nothing -> {
                error = leftAlone.isNotEmpty()
                parts += message("regenerate.nothing.written")
            }
        }
        if (leftAlone.isNotEmpty()) parts += message("regenerate.left.alone", leftAlone.joinToString("; "))
        if (usedBy.isNotEmpty()) parts += message("regenerate.used.by.title") + "<br>" + usedBy.joinToString("<br>")
        val text = parts.joinToString(" ")
        if (error) VaultUiFeedback.error(project, editor, text) else VaultUiFeedback.info(project, editor, text)
    }

    /** Why an item was left alone, in words (a failure class, never data). */
    @Nls
    private fun outcomeText(outcome: ItemOutcome): String = when (outcome) {
        is ItemOutcome.Failed -> AnsibilityVaultBundle.failure(outcome.failure)
        ItemOutcome.Changed -> message("regenerate.left.changed")
        ItemOutcome.Declined -> message("regenerate.left.declined")
        ItemOutcome.NotVerified -> message("regenerate.left.verify")
        ItemOutcome.LeftOut -> message("regenerate.left.group")
        ItemOutcome.Unchanged, is ItemOutcome.Ready -> ""
    }

    private fun report(editor: Editor?, outcome: ItemOutcome) {
        if (outcome is ItemOutcome.Failed && outcome.failure == VaultFailure.CANCELLED) return
        VaultUiFeedback.error(project, editor, message("regenerate.not.decrypted", outcomeText(outcome)))
    }

    /** Zeroes the plaintexts the preflight's decrypts left in the vault's plaintext cache: the old values are gone or going. */
    private suspend fun forgetOldPlaintexts(candidates: List<Candidate>) {
        val crypto = VaultCrypto.getInstance(project)
        val registry = VaultIdentityRegistry.getInstance(project)
        withContext(NonCancellable) {
            for (candidate in candidates) {
                val envelope = (candidate.target as? ReencryptTarget.Value)?.ref?.envelope ?: continue
                crypto.forgetPlaintexts(registry.discovery(candidate.root).rootKey, crypto.fingerprint(envelope))
            }
        }
    }

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    /** Whether a run got as far as writing (then Cancel no longer means "nothing was written"). */
    private class RunState {
        @Volatile
        var finished: Boolean = false
    }

    /**
     * Runs [block] in the background with progress and Cancel; a Cancel before the write says that nothing was written;
     * unexpected errors are logged by class and reported.
     */
    private fun launch(@Nls title: String, editor: Editor?, block: suspend CoroutineScope.(RunState) -> Unit) {
        val state = RunState()
        lastJob = scope.launch {
            try {
                withBackgroundProgress(project, title) { block(state) }
            } catch (e: CancellationException) {
                if (!state.finished) withContext(NonCancellable) { onEdt { VaultUiFeedback.info(project, null, message("regenerate.cancelled")) } }
                throw e
            } catch (e: VaultCorpusGuardException) {
                throw e
            } catch (e: Exception) {
                VaultLog.failure(VaultLog.Operation.ENCRYPT, e)
                onEdt { VaultUiFeedback.failure(project, editor, VaultFailure.SOURCE_UNAVAILABLE) }
            }
        }
    }

    private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT) { block() }

    companion object {
        /** Larger YAML files are not searched for placeholders. */
        private const val MAX_SCANNED_BYTES = 16L * 1024 * 1024

        private val PLACEHOLDER_MARK = PlainValueRef.VAULT_PREFIX.toByteArray(Charsets.US_ASCII)
        private const val SHA256 = "SHA-256"
        private const val SHA256_BLOCK = 64
        private const val DIGEST_KEY_BYTES = 32

        /** Between the side of DEV rule 6 and the digest in an equal-value key (never in a path). */
        private const val DIGEST_SEPARATOR = "\u0000"

        /** How long the summary waits for X130's usages (smart mode, the usage index) before it goes without them. */
        private const val USED_BY_MILLIS = 5_000L

        fun getInstance(project: Project): RegenerateService = project.service()
    }
}
