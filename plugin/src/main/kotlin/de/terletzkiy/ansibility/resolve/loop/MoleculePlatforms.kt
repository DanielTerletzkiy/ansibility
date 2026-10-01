package de.terletzkiy.ansibility.resolve.loop

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RenderContext
import de.terletzkiy.ansibility.api.RenderKind
import de.terletzkiy.ansibility.api.RenderLoop
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter

/**
 * 🟣 CLAUDE X77 (plan F2.3): molecule's Dockerfile templates are rendered by molecule itself, not by a task of the role.
 * The docker and podman drivers' `create` playbook templates `molecule_scenario_directory/(item.dockerfile |
 * default('Dockerfile.j2'))` once per platform of `molecule.yml` (`loop: molecule_yml.platforms`, skipping
 * `pre_build_image: true`), so inside such a template `item` is a molecule platform (`item.image`, `item.command`,
 * `item.env` …), never a loop item of the role's tasks.
 *
 * [rendersOf] finds the scenarios that render a template this way (any scenario of the template's `molecule/`
 * directory, so a shared `dockerfile: ../shared/Dockerfile.j2` counts too) and types `item` from the platforms that
 * build from it: the union of their literal shapes ([LiteralShapes]) over the documented platform keys of the
 * drivers ([PLATFORM_KEYS]). [loopOf] merges all scenarios into one loop.
 *
 * Secrets are never read: only keys and YAML types of the platform entries are kept. Call in a read action.
 */
object MoleculePlatforms {
    /** The variable molecule's `create` playbook iterates, as [RenderLoop.sourceVariable]. */
    const val SOURCE_VARIABLE: String = "molecule_yml"

    /** The accessor below [SOURCE_VARIABLE] the playbook iterates, as [RenderLoop.sourcePath]. */
    val SOURCE_PATH: List<String> = listOf("platforms")

    /** The template a platform builds from when it names no `dockerfile`. */
    const val DEFAULT_DOCKERFILE: String = "Dockerfile.j2"

    /** Drivers that build platform images from a templated Dockerfile. */
    val BUILDING_DRIVERS: Set<String> = setOf("docker", "podman")

    private const val LOOP_VAR = "item"

    /**
     * One scenario rendering a Dockerfile template: its `molecule.yml` ([config]), the `platforms:` key and the
     * platform entries building from the template, and the typed loop.
     */
    class PlatformRender(
        val template: VirtualFile,
        val config: VirtualFile,
        val platformsKey: SourceLocation,
        val platforms: List<SourceLocation>,
        val loop: RenderLoop,
    ) {
        /**
         * The render as a [RenderContext] of the template: molecule's playbook names the template literally
         * ([RenderKind.STATIC]); the site is the `platforms:` key, there is no role scope and no task vars.
         */
        fun renderContext(): RenderContext = RenderContext(
            template = template,
            kind = RenderKind.STATIC,
            site = platformsKey,
            taskSite = platformsKey,
            role = null,
            loop = loop,
            taskVars = emptyList(),
            via = emptyList(),
        )

        override fun toString(): String = "PlatformRender(${config.parent?.name}: ${platforms.size} platforms)"
    }

    /** The scenarios whose platforms build from [template], in scenario order; empty for any other file. */
    fun rendersOf(project: Project, template: VirtualFile): List<PlatformRender> {
        if (!template.isValid || template.isDirectory) return emptyList()
        val root = AnsibleWorkspace.getInstance(project).contextOf(template)?.root ?: return emptyList()
        val moleculeDir = moleculeDirOf(template, root.dir) ?: return emptyList()
        return moleculeDir.children.orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .mapNotNull { scenario ->
                ProgressManager.checkCanceled()
                val config = scenario.children.orEmpty().firstOrNull { !it.isDirectory && AnsibleLayout.isMoleculeConfigName(it.name) }
                config?.let { renderOf(project, template, scenario, it) }
            }
    }

    /**
     * The render contexts of [template] when molecule renders it (one per scenario, [PlatformRender.renderContext]),
     * else empty. A template molecule renders is never rendered by the role's tasks, so a non-empty answer replaces the
     * task-based contexts of `api.TemplateContextService.renderContexts` rather than adding to them.
     */
    fun renderContexts(project: Project, template: VirtualFile): List<RenderContext> =
        rendersOf(project, template).map { it.renderContext() }

    /** The loop of `item` in [template] over every scenario rendering it, or null when molecule renders it nowhere. */
    fun loopOf(project: Project, template: VirtualFile): RenderLoop? {
        val loops = rendersOf(project, template).map { it.loop }
        if (loops.isEmpty()) return null
        val item = loops.mapNotNull { it.item }.reduceOrNull { a, b -> LiteralShapes.union(a, b) }
        return loops.first().copy(item = item)
    }

    private fun renderOf(project: Project, template: VirtualFile, scenario: VirtualFile, config: VirtualFile): PlatformRender? {
        val yaml = YamlFiles.yamlFile(project, config) ?: return null
        val document = PsiYValueAdapter.documentValue(yaml) as? YMap ?: return null
        val driver = ((document["driver"] as? YMap)?.get("name") as? YScalar)?.text
        if (driver !in BUILDING_DRIVERS) return null
        val platforms = document["platforms"] as? YSeq ?: return null
        val rendering = platforms.items.filterIsInstance<YMap>().filter { dockerfileOf(scenario, it) == template }
        if (rendering.isEmpty()) return null
        // molecule skips pre-built platforms; when every platform is pre-built the template still describes them
        val building = rendering.filterNot { isTrue(it["pre_build_image"]) }.ifEmpty { rendering }
        val item = building.map { LiteralShapes.of(LOOP_VAR, it, 1) }.reduce { a, b -> LiteralShapes.union(a, b) }
        val loop = RenderLoop(
            loopVar = LOOP_VAR,
            indexVar = null,
            extended = false,
            item = documented(item),
            sourceVariable = SOURCE_VARIABLE,
            sourcePath = SOURCE_PATH,
        )
        val key = document.entries.lastOrNull { it.key.text == "platforms" }?.key?.range?.start ?: 0
        val entries = building.mapNotNull { platform -> platform.range?.let { SourceLocation(config, it.start) } }
        return PlatformRender(template, config, SourceLocation(config, key), entries, loop)
    }

    /** The template [platform] builds from, resolved against the [scenario] directory; null when templated or missing. */
    private fun dockerfileOf(scenario: VirtualFile, platform: YMap): VirtualFile? {
        val written = (platform["dockerfile"] as? YScalar)?.takeIf { it.resolved is Resolved.Str }?.text?.trim()
        val name = written?.takeIf { it.isNotEmpty() } ?: DEFAULT_DOCKERFILE
        if (JinjaBearing.hasTemplateMarkers(name)) return null
        if (name.startsWith("/")) return scenario.fileSystem.findFileByPath(name)
        return scenario.findFileByRelativePath(name)
    }

    /** The nearest `molecule` directory above [file] and below [rootDir]; null when the file is not under one. */
    private fun moleculeDirOf(file: VirtualFile, rootDir: VirtualFile): VirtualFile? {
        var current = file.parent
        while (current != null && current != rootDir) {
            if (current.name == AnsibleLayout.MOLECULE) return current
            current = current.parent
        }
        return null
    }

    /** The documented platform keys (with their types) under the literal shape [item]; written keys keep their shape. */
    private fun documented(item: OptionSpec): OptionSpec {
        val written = item.options.orEmpty()
        val options = LinkedHashMap<String, OptionSpec>()
        for ((name, spec) in PLATFORM_KEYS) {
            val literal = written[name]
            options[name] = if (literal == null) spec else spec.copy(options = spec.options ?: literal.options, elements = spec.elements ?: literal.elements)
        }
        for ((name, literal) in written) options.putIfAbsent(name, literal)
        return item.copy(type = OptionType.Dict, options = options)
    }

    private fun isTrue(value: YValue?): Boolean = ((value as? YScalar)?.resolved as? Resolved.Bool)?.value == true

    private fun key(name: String, type: OptionType, elements: OptionType? = null, required: Boolean = false, options: Map<String, OptionSpec>? = null) =
        name to OptionSpec(name, type, elements = elements, required = required, options = options)

    /** The platform keys the molecule docker and podman drivers document (molecule-plugins), with their types. */
    val PLATFORM_KEYS: Map<String, OptionSpec> = linkedMapOf(
        key("name", OptionType.Str, required = true),
        key("hostname", OptionType.Str),
        key("image", OptionType.Str),
        key("dockerfile", OptionType.Str),
        key("pull", OptionType.Bool),
        key("pre_build_image", OptionType.Bool),
        key(
            "registry", OptionType.Dict,
            options = linkedMapOf(
                key("url", OptionType.Str),
                key("credentials", OptionType.Dict, options = linkedMapOf(key("username", OptionType.Str), key("password", OptionType.Str))),
            ),
        ),
        key("override_command", OptionType.Bool),
        key("command", OptionType.Str),
        key("tty", OptionType.Bool),
        key("pid_mode", OptionType.Str),
        key("privileged", OptionType.Bool),
        key("security_opts", OptionType.List, OptionType.Str),
        key("devices", OptionType.List, OptionType.Str),
        key("volumes", OptionType.List, OptionType.Str),
        key("keep_volumes", OptionType.Bool),
        key("tmpfs", OptionType.List, OptionType.Str),
        key("capabilities", OptionType.List, OptionType.Str),
        key("sysctls", OptionType.Dict),
        key("exposed_ports", OptionType.List, OptionType.Str),
        key("published_ports", OptionType.List, OptionType.Str),
        key("user", OptionType.Str),
        key("ulimits", OptionType.List, OptionType.Str),
        key("dns_servers", OptionType.List, OptionType.Str),
        key("etc_hosts", OptionType.Raw),
        key("docker_networks", OptionType.List, OptionType.Dict),
        key("networks", OptionType.List, OptionType.Dict),
        key("network_mode", OptionType.Str),
        key("purge_networks", OptionType.Bool),
        key("docker_host", OptionType.Str),
        key("cacert_path", OptionType.Str),
        key("cert_path", OptionType.Str),
        key("key_path", OptionType.Str),
        key("tls_verify", OptionType.Bool),
        key("env", OptionType.Dict),
        key("restart_policy", OptionType.Str),
        key("restart_retries", OptionType.Int),
        key("buildargs", OptionType.Dict),
        key("cgroupns_mode", OptionType.Str),
        key("groups", OptionType.List, OptionType.Str),
        key("children", OptionType.List, OptionType.Str),
    )
}
