package com.facelapse.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class VersioningTest {

    @Test
    fun `test semantic versioning logic`() {
        // This is a placeholder test for the logic I put in the bash script.
        // In a real app I might have a Kotlin script for this.

        val currentVersion = "1.0.0"
        val commitMessage = "feat: add new feature"

        val nextVersion = bumpVersion(currentVersion, commitMessage)
        assertEquals("1.1.0", nextVersion)
    }

    private fun bumpVersion(current: String, message: String): String {
        val parts = current.split(".").map { it.toInt() }
        var (major, minor, patch) = Triple(parts[0], parts[1], parts[2])

        if (message.contains("breaking")) {
            major++
            minor = 0
            patch = 0
        } else if (message.contains("feat")) {
            minor++
            patch = 0
        } else {
            patch++
        }

        return "$major.$minor.$patch"
    }
}
