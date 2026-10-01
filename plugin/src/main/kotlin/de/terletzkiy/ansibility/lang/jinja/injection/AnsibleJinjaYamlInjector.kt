package de.terletzkiy.ansibility.lang.jinja.injection

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Injects Ansible Jinja into YAML scalars (plan A.5 "Jinja inside YAML", F2.2, WU C4), registered as a
 * `multiHostInjector`: full Jinja PSI in values containing `{{`/`{%`, and the bare expressions of `when`,
 * `changed_when`, `failed_when`, `until`, `assert.that` and `debug.var` through
 * `registrar.startInjecting(AnsibleJinja).addPlace("{{ ", " }}", host, range)`, so one language and one parser serve
 * both and offsets map back through the prefix. Which scalars qualify, and in which mode, is decided by
 * [JinjaYamlInjections] (gated to Ansible files inside roots).
 *
 * The injected text is the scalar's value decoded by the host's own literal text escaper (double-quoted escapes such
 * as `'\n'` and `\"`, doubled single quotes, the indentation of block scalars), laid out as [JinjaYamlInjections.Place]s.
 * Each fragment carries its [JinjaInjectionMode] as user data ([JinjaInjectionMode.KEY]).
 *
 * Needs no index (the gate reads the cached path classification), so Jinja highlighting works while indexing.
 */
class AnsibleJinjaYamlInjector : MultiHostInjector, DumbAware {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(YAMLScalar::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val scalar = context as? YAMLScalar ?: return
        if (!scalar.isValidHost) return
        val injection = JinjaYamlInjections.injectionFor(scalar) ?: return
        registrar.startInjecting(AnsibleJinjaLanguage)
        for (place in injection.places) registrar.addPlace(place.prefix, place.suffix, scalar, place.rangeInHost)
        registrar.putInjectedFileUserData(JinjaInjectionMode.KEY, injection.mode)
        registrar.doneInjecting()
    }
}
