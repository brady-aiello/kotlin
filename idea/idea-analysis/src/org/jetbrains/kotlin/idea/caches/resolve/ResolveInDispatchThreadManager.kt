/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.references.AbstractKtReference

/**
 * Temporary allow resolve in dispatch thread.
 *
 * All resolve should be banned from the UI thread. This method is needed for the transition period to document
 * places that are not fixed yet.
 */
fun <T> allowResolveInDispatchThread(runnable: () -> T): T {
    return ResolveInDispatchThreadManager.runWithResolveAllowedInDispatchThread(runnable)
}

/**
 * Temporary allow resolve in write action.
 *
 * All resolve should be banned from dispatch thread. This method should be carefully used for the transition
 * period at the places where developer expects everything is already cached.
 *
 * (A better check is needed instead that would assert no resolve result are obtained outside of caches.)
 */
fun <T> allowCachedResolveInDispatchThread(runnable: () -> T): T {
    return ResolveInDispatchThreadManager.runWithResolveAllowedInDispatchThread(runnable)
}

/**
 * Force resolve check in tests as it disabled by default.
 */
@TestOnly
fun <T> forceCheckForResolveInDispatchThreadInTests(project: Project, errorHandler: (() -> Unit)? = null, runnable: () -> T): T {
    ResolveCache.getInstance(project).clearCache(true)
    return ResolveInDispatchThreadManager.runWithForceCheckForResolveInDispatchThreadInTests(errorHandler, runnable)
}

private const val RESOLVE_IN_DISPATCH_THREAD_ERROR_MESSAGE = "Resolve is not allowed in dispatch thread!"

class ResolveInDispatchThreadException(message: String? = null) :
    IllegalThreadStateException(message ?: RESOLVE_IN_DISPATCH_THREAD_ERROR_MESSAGE)

internal object ResolveInDispatchThreadManager {
    private val LOG = Logger.getInstance(ResolveInDispatchThreadManager::class.java)

    // Guarded by isDispatchThread check
    private var isResolveAllowed: Boolean = false

    // Guarded by isDispatchThread check
    private var isForceCheckInTests: Boolean = true // VD: TEMP

    // Guarded by isDispatchThread check
    private var errorHandler: (() -> Unit)? = null

    init {
        AbstractKtReference.enableResolverCache = isForceCheckInTests
    }

    internal fun assertNoResolveInDispatchThread() {
        val application = ApplicationManager.getApplication() ?: return
        if (!application.isDispatchThread) {
            return
        }

        if (isResolveAllowed) return

        if (application.isUnitTestMode) {
            if (!isForceCheckInTests) return

            val handler = errorHandler
            if (handler != null) {
                handler()
                return
            }

            throw ResolveInDispatchThreadException()
        } else {
            @Suppress("InvalidBundleOrProperty")
            if (!Registry.`is`("kotlin.dispatch.thread.resolve.check", false)) return

            LOG.error(RESOLVE_IN_DISPATCH_THREAD_ERROR_MESSAGE)
        }
    }

    internal inline fun <T> runWithResolveAllowedInDispatchThread(runnable: () -> T): T {
        val wasSet =
            if (ApplicationManager.getApplication()?.isDispatchThread == true && !isResolveAllowed) {
                isResolveAllowed = true
                true
            } else {
                false
            }
        try {
            return runnable()
        } finally {
            if (wasSet) {
                isResolveAllowed = false
            }
        }
    }

    internal fun <T> runWithForceCheckForResolveInDispatchThreadInTests(errorHandler: (() -> Unit)?, runnable: () -> T): T {
        ApplicationManager.getApplication()?.assertIsDispatchThread() ?: error("Application is not available")

        val wasSet = if (!isForceCheckInTests) {
            isForceCheckInTests = true
            this.errorHandler = errorHandler
            true
        } else {
            false
        }

        try {
            return runnable()
        } finally {
            if (wasSet) {
                isForceCheckInTests = false
                this.errorHandler = null
            }
        }
    }
}