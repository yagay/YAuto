package com.yagay.yauto.platform.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortXObserverMethodSelectionTest {
    private val startSpec = ShortXCompatHookCatalog.subscribedObservers(
        setOf("android.event.app_ops_operation")
    ).first { it.id == "app-ops-start" }

    @Test fun selectsPublicMethodWhenBothWrappersAndImplExist() {
        assertEquals(
            setOf("startOperation"),
            ShortXObserverMethodSelection.select(
                startSpec, setOf("startOperation", "startOperationImpl")
            ),
        )
    }

    @Test fun retainsImplOnlyOnOemVersionsWithoutPublicWrapper() {
        assertEquals(
            setOf("startOperationImpl"),
            ShortXObserverMethodSelection.select(startSpec, setOf("startOperationImpl")),
        )
    }

    @Test fun noCandidateNeverInstallsAnUnrelatedMethod() {
        assertEquals(emptySet<String>(), ShortXObserverMethodSelection.select(
            startSpec, setOf("finishOperation")
        ))
    }

    @Test fun ordinaryObserversKeepOriginalMethodSet() {
        val normal = ShortXCompatHookCatalog.subscribedObservers(
            setOf("android.event.notification_posted_system")
        ).first()
        assertEquals(normal.methodNames, ShortXObserverMethodSelection.select(normal, normal.methodNames))
    }
}
