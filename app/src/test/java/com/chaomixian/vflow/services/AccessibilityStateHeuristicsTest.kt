package com.chaomixian.vflow.services

import com.chaomixian.vflow.permissions.PermissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityStateHeuristicsTest {

    @Test
    fun accessibilityPermissionId_staysStableForTransientFiltering() {
        assertEquals("vflow.permission.ACCESSIBILITY_SERVICE", PermissionManager.ACCESSIBILITY.id)
    }

    @Test
    fun replaceServiceId_keepsSingleTargetEntry() {
        val original = "com.chaomixian.vflow/com.chaomixian.vflow.services.AccessibilityService"
        val disguised = "com.chaomixian.vflow/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        val setting = "$original:$disguised"

        val replaced = AccessibilityServiceStatus.replaceServiceId(setting, original, disguised)

        assertEquals(disguised, replaced)
    }

    @Test
    fun containsServiceId_remains_caseInsensitive() {
        val expected = "com.chaomixian.vflow/com.chaomixian.vflow.services.AccessibilityService"

        assertTrue(AccessibilityServiceStatus.containsServiceId(expected.uppercase(), expected))
        assertFalse(AccessibilityServiceStatus.containsServiceId("other/.Service", expected))
    }

    @Test
    fun containsAnyServiceId_acceptsOriginalOrDisguisedComponent() {
        val original = "com.chaomixian.vflow/com.chaomixian.vflow.services.AccessibilityService"
        val disguised = "com.chaomixian.vflow/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

        assertTrue(AccessibilityServiceStatus.containsAnyServiceId(original, original, disguised))
        assertTrue(AccessibilityServiceStatus.containsAnyServiceId(disguised, original, disguised))
        assertFalse(
            AccessibilityServiceStatus.containsAnyServiceId(
                "other.package/other.package.AccessibilityService",
                original,
                disguised
            )
        )
    }
}
