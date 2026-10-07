package de.terletzkiy.ansibility.run.events

import java.security.SecureRandom

/**
 * The Ansibility callback a run adds (`ansible/callback/ansibility_events.py`): its source, the token that marks its
 * events, and the `ANSIBLE_CALLBACK_PLUGINS` value that adds its directory. That variable replaces the configured
 * value, so the configured one (environment, else `ansible.cfg`, else Ansible's default) is kept in front: setting
 * only ours would silently drop a team's own callbacks, such as a report callback. Pure apart from [source].
 */
object RunCallback {
    const val FILE = "ansibility_events.py"
    const val TOKEN_VARIABLE = "ANSIBILITY_EVENTS_TOKEN"
    const val PLUGINS_VARIABLE = "ANSIBLE_CALLBACK_PLUGINS"

    /** Ansible's default `callback_plugins` (DEFAULT_CALLBACK_PLUGIN_PATH). */
    const val DEFAULT_PLUGINS = "~/.ansible/plugins/callback:/usr/share/ansible/plugins/callback"

    private val random = SecureRandom()

    /** The callback's Python source, from the plugin's resources. */
    fun source(): String = RunCallback::class.java.getResourceAsStream("/ansible/callback/$FILE")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** A fresh token for one run (16 random bytes, hex). */
    fun newToken(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * The `ANSIBLE_CALLBACK_PLUGINS` of a run: the configured paths ([environment] over [config], else the default) and
     * then [ours]. A path list in Ansible is `:`-separated (`os.pathsep`); duplicates of [ours] are not repeated.
     */
    fun pluginPath(environment: String?, config: String?, ours: String): String {
        val configured = environment?.takeIf { it.isNotBlank() } ?: config?.takeIf { it.isNotBlank() } ?: DEFAULT_PLUGINS
        val paths = configured.split(':').map { it.trim() }.filter { it.isNotEmpty() && it != ours }
        return (paths + ours).joinToString(":")
    }
}
