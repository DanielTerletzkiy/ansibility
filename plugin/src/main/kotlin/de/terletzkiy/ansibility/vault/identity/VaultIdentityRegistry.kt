package de.terletzkiy.ansibility.vault.identity

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.vfs.toNioPathOrNull
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.AnsibleStructureListener
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The vault ids of every root (plan amendment R7/R8, A.13 `VaultIdentityRegistry`): the explicit settings plus the
 * discovery chain of [VaultDiscoverer], without password scripts (M4.6) and **without reading any secret** (D25).
 *
 * - A nested playbook root (`danger_zone/database`) has no ids of its own: [vaultRoot] maps it to its parent, whose
 *   ids and unlock state it shares.
 * - Discoveries are cached per root and recomputed when the Ansible structure (`ansible.cfg`), the project or vault
 *   settings, the remembered PasswordSafe labels or a file the chain looks at (`.env.local`, `.env.local.skel`, the
 *   conventional names, configured password files) change; [modificationTracker] covers all of them.
 * - Safe on any thread and in a read action: discovery only checks that files exist and reads `.env.local.skel`.
 *   The environment is `EnvironmentUtil`'s (the login shell's, as a terminal would see it), which the IDE loads once
 *   at startup.
 */
@Service(Service.Level.PROJECT)
class VaultIdentityRegistry(private val project: Project) : Disposable {
    private class Cached(val stamp: Long, val discovery: VaultDiscovery)

    private val tracker = SimpleModificationTracker()
    private val cache = ConcurrentHashMap<String, Cached>()

    @Volatile
    private var watched: Set<String> = emptySet()

    @Volatile
    private var sourceAccess: VaultSourceAccess = LocalVaultSourceAccess

    /** The file and environment access every vault component uses (tests record it). */
    val access: VaultSourceAccess get() = sourceAccess

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES_BG,
            object : BulkFileListenerBackgroundable {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::isRelevant)) invalidate()
                }
            },
        )
        connection.subscribe(AnsibleStructureListener.TOPIC, AnsibleStructureListener { invalidate() })
    }

    /** Bumped whenever a discovery may have changed. */
    val modificationTracker: ModificationTracker = ModificationTracker { stamp() }

    /** The root whose ids [root] uses: its parent for a nested playbook root, else itself. */
    fun vaultRoot(root: AnsibleRoot): AnsibleRoot {
        if (root.kind != RootKind.NESTED_PLAYBOOK) return root
        val parentDir = root.parentDir ?: return root
        return AnsibleWorkspace.getInstance(project).roots().firstOrNull { it.dir == parentDir } ?: root
    }

    /** The `settings.RootKeys` key of [root]. */
    fun rootKey(root: AnsibleRoot): String = RootKeys.keyOf(project, root.dir)

    /** The ids of [root] (of its parent for a nested playbook root), in the order Ansible tries them. */
    fun discovery(root: AnsibleRoot): VaultDiscovery {
        val vaultRoot = vaultRoot(root)
        val stamp = stamp()
        val key = vaultRoot.dir.url
        cache[key]?.takeIf { it.stamp == stamp }?.let { return it.discovery }
        val discovery = discover(vaultRoot)
        cache[key] = Cached(stamp, discovery)
        watched = watched + discovery.watchedPaths()
        return discovery
    }

    /** The discoveries of every root that is not detached, one per vault root (nested roots share their parent's). */
    fun discoveries(): List<VaultDiscovery> =
        AnsibleWorkspace.getInstance(project).roots().filter { !it.detached }.map(::vaultRoot).distinctBy { it.dir }.map(::discovery)

    /** Drops every cached discovery. */
    fun invalidate() {
        cache.clear()
        watched = emptySet()
        tracker.incModificationCount()
    }

    /** Replaces the file and environment access (tests record every access, or fake the environment). */
    @TestOnly
    fun setAccessForTests(access: VaultSourceAccess) {
        sourceAccess = access
        invalidate()
    }

    override fun dispose() {
        cache.clear()
    }

    private fun discover(root: AnsibleRoot): VaultDiscovery {
        val access = sourceAccess
        val rootPath = root.dir.takeIf { it.isInLocalFileSystem }?.toNioPathOrNull()?.toAbsolutePath()?.normalize()
        val canonical = rootPath?.let(access::canonical)?.toString() ?: rootPath?.toString() ?: root.dir.path
        val cfg = AnsibleWorkspaceImpl.getInstance(project)?.configOf(root)
        val rootKey = rootKey(root)
        val input = VaultDiscoverer.Input(
            root = root,
            rootKey = rootKey,
            rootPath = rootPath,
            canonicalRootPath = canonical,
            cfgDefaults = cfg?.sections?.get(DEFAULTS_SECTION).orEmpty(),
            cfgDir = if (cfg != null) rootPath?.toString() ?: root.dir.path else null,
            environment = access.environment(),
            explicit = VaultProjectSettings.getInstance(project).rootSettings(rootKey).identities,
            remembered = VaultUserState.getInstance().rememberedLabels(canonical),
            repoDir = rootPath?.let { repoDir(it, access) },
        )
        return VaultDiscoverer(access).discover(input)
    }

    /** The nearest directory at or above [rootPath] (up to the project directory) that holds `.git`. */
    private fun repoDir(rootPath: Path, access: VaultSourceAccess): Path? {
        val limit = RootKeys.projectDir(project)?.takeIf { it.isInLocalFileSystem }?.toNioPathOrNull()?.toAbsolutePath()?.normalize()
        var dir: Path? = rootPath
        var depth = 0
        while (dir != null && depth <= MAX_REPO_DEPTH) {
            if (access.exists(dir.resolve(GIT))) return dir
            if (limit != null && dir == limit) return null
            dir = dir.parent
            depth++
        }
        return null
    }

    private fun VaultDiscovery.watchedPaths(): Set<String> = identities.flatMap { identity ->
        when (val plan = identity.plan) {
            is SecretPlan.PasswordFile -> plan.target.paths()
            is SecretPlan.Script -> listOf(plan.path.toString())
            is SecretPlan.EnvLocal -> plan.envLocal.paths() + plan.expected?.paths().orEmpty()
            else -> emptyList()
        }
    }.toSet()

    private fun ConsentTarget.paths(): List<String> = listOfNotNull(path, declared).map { it.toString().replace('\\', '/') }

    private fun isRelevant(event: VFileEvent): Boolean {
        val paths = buildList {
            add(event.path)
            if (event is VFileMoveEvent) add(event.oldPath)
            if (event is VFilePropertyChangeEvent && event.isRename) add(event.oldPath)
        }
        return paths.any { path -> path.substringAfterLast('/') in RELEVANT_NAMES || path in watched }
    }

    private fun stamp(): Long =
        tracker.modificationCount +
            AnsibleWorkspace.getInstance(project).structureTracker.modificationCount +
            AnsibilityProjectSettings.getInstance(project).modificationTracker.modificationCount +
            VaultProjectSettings.getInstance(project).modificationTracker.modificationCount +
            VaultUserState.getInstance().modificationTracker.modificationCount

    companion object {
        private const val DEFAULTS_SECTION = "defaults"
        private const val GIT = ".git"
        private const val MAX_REPO_DEPTH = 8

        /** File names whose creation, deletion or change can change a discovery. */
        private val RELEVANT_NAMES: Set<String> =
            setOf(VaultDiscoverer.ENV_LOCAL, VaultDiscoverer.ENV_LOCAL_SKEL, "ansible.cfg", GIT) + VaultDiscoverer.CONVENTIONAL_NAMES

        fun getInstance(project: Project): VaultIdentityRegistry = project.service()
    }
}
