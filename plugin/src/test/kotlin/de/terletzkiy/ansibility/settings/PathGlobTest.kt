package de.terletzkiy.ansibility.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathGlobTest {
    private fun glob(pattern: String) = PathGlob.compile(pattern)!!

    @Test
    fun doubleStarMatchesAnyDepthIncludingNone() {
        val dotAnsible = glob("**/.ansible/**")
        assertTrue(dotAnsible.matches(".ansible/roles/x/tasks/main.yml"))
        assertTrue(dotAnsible.matches("golden/.ansible/collections/ansible_collections/x.yml"))
        assertTrue(dotAnsible.matches("repos/falcon/ansible/.ansible"))
        assertFalse(dotAnsible.matches("repos/falcon/ansible/roles/postfix/tasks/main.yml"))
        assertFalse("a name that only starts with .ansible", dotAnsible.matches("golden/.ansible-lint"))
    }

    @Test
    fun trailingDoubleStarMatchesTheDirectoryAndBelow() {
        val patches = glob("patches/**")
        assertTrue(patches.matches("patches"))
        assertTrue(patches.matches("patches/roles/nginx/tasks/main.yml.patch"))
        assertFalse("anchored at the project directory", patches.matches("golden/patches/x"))
        assertFalse(patches.matches("patches-old/x"))
    }

    @Test
    fun singleStarAndQuestionMarkStayInsideOneSegment() {
        assertTrue(glob("*.yml").matches("main.yml"))
        assertFalse(glob("*.yml").matches("tasks/main.yml"))
        assertTrue(glob("roles/*/tasks").matches("roles/nginx/tasks"))
        assertFalse(glob("roles/*/tasks").matches("roles/a/b/tasks"))
        assertTrue(glob("host?.yml").matches("host1.yml"))
        assertFalse(glob("host?.yml").matches("host/.yml"))
    }

    @Test
    fun specialCharactersAreLiteral() {
        assertTrue(glob("gateway.*.conf").matches("gateway.api.conf"))
        assertFalse("the dot is not a regex wildcard", glob("gateway.*.conf").matches("gatewayXapi.conf"))
        assertTrue(glob("a+b(c)[d]").matches("a+b(c)[d]"))
    }

    @Test
    fun slashesAroundPatternsAndPathsAreIgnored() {
        assertTrue(glob("/patches/**/").matches("/patches/x/"))
        assertEquals("patches/**", glob(" /patches/**/ ").pattern)
        assertNull(PathGlob.compile("   "))
        assertNull(PathGlob.compile("/"))
    }

    @Test
    fun pathSettingsMatchTheDefaultIgnoredPaths() {
        val paths = PathSettings()
        assertTrue(paths.isIgnored("repos/falcon/ansible/.ansible/roles/cached/tasks/main.yml"))
        assertTrue(paths.isIgnored("patches/roles/nginx/defaults/main.yml"))
        assertFalse(paths.isIgnored("repos/falcon/ansible/roles/postfix/tasks/main.yml"))
        assertFalse(PathSettings(extraIgnoredPaths = emptyList()).isIgnored("patches/x"))
    }
}
