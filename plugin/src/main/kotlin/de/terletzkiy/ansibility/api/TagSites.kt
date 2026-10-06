package de.terletzkiy.ansibility.api

import com.intellij.openapi.util.TextRange

/** One tag in the `tags:` of a play, role entry, block or task (plan X70); [name] is empty where a tag is being typed. */
data class TagSite(val name: String, override val range: TextRange) : AnsibleSite
