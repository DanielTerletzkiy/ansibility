package de.terletzkiy.ansibility.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.LocalAnsibleRuntime
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings

/** Where a root's target ansible-core version came from, strongest first (plan A.11, D10). */
enum class TargetVersionSource {
    /** The per-root override in the project settings. */
    SETTINGS,

    /** An `ansible-core==X` (or `ansible==N`) pin in one of the root's Dockerfiles. */
    DOCKERFILE,

    /** No pin of its own: the version most roots of the project pin. */
    MAJORITY,

    /**
     * Nothing pinned anywhere: the version the local install reports (`ansible --version`, project SDK first,
     * then PATH). UI shows "target guessed from local install".
     */
    LOCAL_GUESSED,

    /** Nothing found (the local probe has not answered yet, or found no install). */
    NONE,
}

/**
 * The ansible-core version a root targets.
 *
 * [guessed] is true when the version does not come from the root itself ([TargetVersionSource.MAJORITY],
 * [TargetVersionSource.LOCAL_GUESSED]) or is unknown ([TargetVersionSource.NONE]); UI marks such versions.
 */
data class TargetVersion(
    val version: CoreVersion?,
    val source: TargetVersionSource,
    /** Human-readable evidence, e.g. `docker/ansible-playbook/Dockerfile`, `majority of 9 roots` or the local `ansible` path. */
    val detail: String,
) {
    val guessed: Boolean
        get() = source == TargetVersionSource.MAJORITY || source == TargetVersionSource.LOCAL_GUESSED || source == TargetVersionSource.NONE

    companion object {
        val UNKNOWN = TargetVersion(null, TargetVersionSource.NONE, "")
    }
}

/**
 * Detects the target ansible-core version per root; the first hit wins:
 * 1. the settings override ([overrideFor], installed by `settings.AnsibilitySettingsWiring` from
 *    `AnsibilityProjectSettings.targetCoreOverride`);
 * 2. a pin in `<root>/docker/<image>/Dockerfile`, `<root>/docker/Dockerfile` or `<root>/../docker/…`
 *    (the repos keep their images in `repos/<r>/ansible/docker`, the role library in `golden/docker`);
 *    a NESTED_PLAYBOOK root without pins of its own inherits its parent's;
 * 3. the majority pin across all non-detached roots;
 * 4. the local install's `ansible --version` ([LocalAnsibleRuntime], probed in the background and cached; the
 *    first lookup starts the probe and reports unknown until it answers), flagged as guessed;
 * 5. otherwise unknown, flagged as guessed.
 *
 * Results are cached until the workspace structure changes (Dockerfile edits bump the structure tracker), the
 * project settings change, the override hook is replaced or the local probe answers. Caches built from a target version depend on
 * [modificationTracker], which covers all of these.
 */
@Service(Service.Level.PROJECT)
class TargetVersionDetector(private val project: Project) {

    private val overridesTracker = SimpleModificationTracker()

    /**
     * The per-root settings override, consulted first. `settings.AnsibilitySettingsWiring` installs it when the
     * project opens (from `AnsibilityProjectSettings.targetCoreOverride`) and re-installs it whenever an explicit
     * target changes; without it no root has an override. Assigning a new function invalidates the cached versions.
     */
    @Volatile
    var overrideFor: (AnsibleRoot) -> CoreVersion? = { null }
        set(value) {
            field = value
            overridesTracker.incModificationCount()
        }

    private val cache: CachedValue<Map<VirtualFile, TargetVersion>> =
        CachedValuesManager.getManager(project).createCachedValue(
            {
                val workspace = AnsibleWorkspace.getInstance(project)
                val local = LocalAnsibleRuntime.getInstanceOrNull()
                CachedValueProvider.Result.create(
                    resolve(workspace.roots(), overrideFor, ::findPins) { localGuess(local) },
                    listOfNotNull(
                        workspace.structureTracker, overridesTracker, ProjectRootManager.getInstance(project), local?.probeTracker,
                        AnsibilityProjectSettings.getInstance(project).modificationTracker,
                    ),
                )
            },
            false,
        )

    /**
     * Changes whenever the target version of some root may have changed: the workspace structure, the project roots,
     * the project settings (which decide the roots and their overrides), the override hook ([overrideFor]) or the
     * local probe. Per-file caches that read [targetVersion] (the task model's keyword set) add it to their
     * dependencies. Thread-safe; reading it never computes versions.
     */
    val modificationTracker: ModificationTracker = ModificationTracker {
        val workspace = AnsibleWorkspace.getInstance(project).structureTracker.modificationCount
        val roots = ProjectRootManager.getInstance(project).modificationCount
        val settings = AnsibilityProjectSettings.getInstance(project).modificationTracker.modificationCount
        val probe = LocalAnsibleRuntime.getInstanceOrNull()?.probeTracker?.modificationCount ?: 0L
        workspace + roots + settings + overridesTracker.modificationCount + probe
    }

    /** The D10 fallback: the local install's version; starts the background probe on first use. */
    private fun localGuess(local: LocalAnsibleRuntime?): TargetVersion? {
        val install = local?.localInstall(project) ?: return null
        return TargetVersion(install.coreVersion, TargetVersionSource.LOCAL_GUESSED, install.executable)
    }

    /** The target version of [root]. */
    fun targetVersion(root: AnsibleRoot): TargetVersion {
        val versions = if (ApplicationManager.getApplication().isReadAccessAllowed) cache.value else runReadActionBlocking { cache.value }
        return versions[root.dir] ?: TargetVersion.UNKNOWN
    }

    /** One `Dockerfile` pin: the file, its path relative to the root, and the version. */
    data class Pin(val file: VirtualFile, val relativePath: String, val version: CoreVersion)

    companion object {
        private val CORE_PIN = Regex("""(?<![\w.-])ansible-core\s*==\s*(\d+\.\d+(?:\.\d+)?)""")
        private val PACKAGE_PIN = Regex("""(?<![\w.-])ansible\s*==\s*(\d+)(?:\.\d+)*""")

        fun getInstance(project: Project): TargetVersionDetector = project.service()

        /**
         * The ansible-core versions pinned in a Dockerfile's text. `ansible==N.x` (the community package) maps
         * to core `2.(N+7)`, the mapping since ansible 3 (ansible 11 ships core 2.18).
         */
        fun parsePins(text: CharSequence): List<CoreVersion> {
            val core = CORE_PIN.findAll(text).mapNotNull { CoreVersion.parse(it.groupValues[1]) }.toList()
            if (core.isNotEmpty()) return core
            return PACKAGE_PIN.findAll(text).mapNotNull { match ->
                match.groupValues[1].toIntOrNull()?.takeIf { it >= 3 }?.let { CoreVersion(2, it + 7) }
            }.toList()
        }

        /** Pins found in the Dockerfiles that belong to [root]. */
        fun findPins(root: AnsibleRoot): List<Pin> {
            val dockerDirs = listOfNotNull(root.dir.childDirectory(AnsibleLayout.DOCKER), root.dir.parent?.childDirectory(AnsibleLayout.DOCKER))
            val dockerfiles = dockerDirs.distinct().flatMap { docker ->
                docker.children.orEmpty().flatMap { child ->
                    if (child.isDirectory) child.children.orEmpty().filter { !it.isDirectory && AnsibleLayout.isDockerfileName(it.name) }
                    else listOf(child).filter { AnsibleLayout.isDockerfileName(it.name) }
                }
            }
            return dockerfiles.sortedBy { it.path }.flatMap { file ->
                val text = RootDetector.readText(file) ?: return@flatMap emptyList()
                val relative = VfsUtilCore.getRelativePath(file, root.dir) ?: VfsUtilCore.getRelativePath(file, root.dir.parent ?: root.dir)?.let { "../$it" } ?: file.name
                parsePins(text).map { Pin(file, relative, it) }
            }
        }

        /**
         * Applies the detection order to all [roots] at once (the majority rule needs every root). [localGuess] is
         * asked only when a root has no override and no pin exists anywhere.
         */
        fun resolve(
            roots: List<AnsibleRoot>,
            overrideFor: (AnsibleRoot) -> CoreVersion?,
            pinsOf: (AnsibleRoot) -> List<Pin>,
            localGuess: () -> TargetVersion? = { null },
        ): Map<VirtualFile, TargetVersion> {
            val pins = roots.associate { it.dir to pinsOf(it) }
            val own = HashMap<VirtualFile, TargetVersion>()
            for (root in roots) {
                val rootPins = pins[root.dir].orEmpty()
                val chosen = mostCommon(rootPins.map { it.version }) ?: continue
                val evidence = rootPins.filter { it.version == chosen }.joinToString(", ") { it.relativePath }
                own[root.dir] = TargetVersion(chosen, TargetVersionSource.DOCKERFILE, evidence)
            }
            // Each root votes with its own pin only; inherited pins would count the parent twice.
            val voters = roots.filter { !it.detached }.mapNotNull { own[it.dir]?.version }
                .ifEmpty { roots.mapNotNull { own[it.dir]?.version } }
            val majority = mostCommon(voters)
            val inherited = roots.filter { it.kind == RootKind.NESTED_PLAYBOOK && it.dir !in own }
                .mapNotNull { nested -> nested.parentDir?.let(own::get)?.let { nested.dir to it } }
            own.putAll(inherited)
            val local by lazy(LazyThreadSafetyMode.NONE) { localGuess() }
            return roots.associate { root ->
                val result = overrideFor(root)?.let { TargetVersion(it, TargetVersionSource.SETTINGS, "") }
                    ?: own[root.dir]
                    ?: majority?.let { TargetVersion(it, TargetVersionSource.MAJORITY, "majority of ${voters.size} roots") }
                    ?: local
                    ?: TargetVersion.UNKNOWN
                root.dir to result
            }
        }

        /** The most frequent version; ties go to the highest. */
        private fun mostCommon(versions: List<CoreVersion>): CoreVersion? =
            versions.groupingBy { it }.eachCount().entries
                .maxWithOrNull(compareBy<Map.Entry<CoreVersion, Int>> { it.value }.thenBy { it.key })
                ?.key
    }
}
