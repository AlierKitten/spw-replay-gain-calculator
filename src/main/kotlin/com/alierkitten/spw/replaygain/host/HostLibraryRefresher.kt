package com.alierkitten.spw.replaygain.host

import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Outcome of attempting to trigger the host's library scan / refresh.
 *
 * The refresher never throws. Callers (the index pipeline) only ever see these three
 * states and can map them to user-facing toast / log messages without further defensive
 * code.
 */
sealed interface RefreshResult {
    /** Host entry was located and invoked. */
    data object Triggered : RefreshResult

    /** None of the [HostScanCandidates] could be located on the host classpath. */
    data object Unavailable : RefreshResult

    /** A candidate was located but invocation failed (e.g. access denied, exception in host). */
    data class Failed(val reason: String) : RefreshResult
}

/**
 * Reflectively triggers Salt Player's own library scan.
 *
 * Single responsibility: locate one of [HostScanCandidates], dispatch the call onto a
 * daemon thread, and never throw. The host scan is fire-and-forget — we do not block
 * the indexer thread waiting for completion, both because the single-round flow does
 * not need the result and because the host's scan can take minutes for large libraries.
 */
class HostLibraryRefresher {

    private val resolved = ConcurrentHashMap<HostScanEntry, ResolvedEntry>()

    /** Tunable: how long to wait for the reflective invoke to return before giving up. */
    private val invokeTimeoutMs: Long = 10_000L

    fun trigger(): RefreshResult {
        val candidates = listOf(HostScanCandidates.Primary)
        var lastFailureReason: String? = null

        for (entry in candidates) {
            val result = invokeOnce(entry)
            if (result is RefreshResult.Triggered) {
                return result
            }
            if (result is RefreshResult.Failed) {
                lastFailureReason = result.reason
            }
            // Unavailable entries: silently try the next candidate.
        }

        return when (val reason = lastFailureReason) {
            null -> RefreshResult.Unavailable
            else -> RefreshResult.Failed(reason)
        }
    }

    private fun invokeOnce(entry: HostScanEntry): RefreshResult {
        return try {
            val resolved = resolve(entry) ?: return RefreshResult.Unavailable
            val (cls, method, receiver) = resolved
            doInvoke(entry, cls, method, receiver)
        } catch (error: Throwable) {
            // Catch absolutely everything: reflective access in unknown class loaders can
            // surface as InaccessibleObjectException, SecurityException, NPE on missing
            // singletons, or even Errors. The caller treats any failure identically.
            logFailure(entry, error)
            RefreshResult.Failed("${entry.className}.${entry.methodName}: ${error.javaClass.simpleName}")
        }
    }

    private fun resolve(entry: HostScanEntry): ResolvedEntry? {
        resolved[entry]?.let { return it }

        val cls = loadClass(entry.className) ?: return null
        val receiver: Any? = when (val r = entry.receiver) {
            Receiver.Static -> null
            is Receiver.SingletonField -> readSingleton(cls, r.fieldName)
            is Receiver.CompanionField -> readSingleton(cls, r.fieldName)
        }
        if (entry.receiver !is Receiver.Static && receiver == null) {
            return null
        }

        val paramClasses = entry.parameterTypes.map(::loadClassOrPrimitive).toTypedArray()
        val method: java.lang.reflect.Method = try {
            cls.getDeclaredMethod(entry.methodName, *paramClasses)
        } catch (error: NoSuchMethodException) {
            return null
        }
        method.isAccessible = true

        val resolved = ResolvedEntry(cls, method, receiver)
        this.resolved[entry] = resolved
        return resolved
    }

    private fun doInvoke(
        entry: HostScanEntry,
        cls: Class<*>,
        method: java.lang.reflect.Method,
        receiver: Any?,
    ): RefreshResult {
        // Run on a daemon thread so a misbehaving host entry cannot stall the indexer.
        // We join with a hard timeout: if the host scan blocks (it shouldn't — the
        // observed implementation is fire-and-forget), we still treat it as Triggered
        // because Method.invoke has at least entered the host. We do not wait for the
        // scan itself to complete.
        val ready = java.util.concurrent.CountDownLatch(1)
        val error = arrayOfNulls<Throwable>(1)
        val runner = thread(name = "spw-host-refresh", isDaemon = true) {
            try {
                method.invoke(receiver)
            } catch (t: Throwable) {
                error[0] = unwrap(t)
            } finally {
                ready.countDown()
            }
        }
        val finished = ready.await(invokeTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            // The reflective call is still in flight. We cannot force-kill Method.invoke,
            // but a polite Thread.interrupt() at least signals the daemon to bail out at
            // the next cooperative cancellation point (kotlinx.coroutines usually honours
            // it). The thread is daemon so it will not prevent JVM shutdown.
            runner.interrupt()
            logFailure(
                entry,
                java.util.concurrent.TimeoutException(
                    "反射调用超过 ${invokeTimeoutMs}ms 未返回，已尽力中断",
                ),
            )
            return RefreshResult.Triggered
        }
        return when (val t = error[0]) {
            null -> RefreshResult.Triggered
            else -> {
                logFailure(entry, t)
                RefreshResult.Failed("${entry.className}.${entry.methodName}: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun readSingleton(cls: Class<*>, fieldName: String): Any? = try {
        val field = cls.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(null)
    } catch (error: Throwable) {
        null
    }

    private fun loadClass(name: String): Class<*>? {
        // Order: WorkshopApi's loader (host-defined, guaranteed to see host classes) →
        // thread context loader → system loader. Use Class.forName with `false` to avoid
        // triggering static initialisation of a host class we only need to reflect on.
        val loaders = listOfNotNull(
            safeClassLoader { com.xuncorp.spw.workshop.api.WorkshopApi::class.java.classLoader },
            safeClassLoader { Thread.currentThread().contextClassLoader },
            safeClassLoader { ClassLoader.getSystemClassLoader() },
        )
        for (loader in loaders) {
            try {
                return Class.forName(name, false, loader)
            } catch (_: ClassNotFoundException) {
                // try next loader
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun loadClassOrPrimitive(name: String): Class<*> = when (name) {
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> loadClass(name) ?: Class.forName(name)
    }

    private fun safeClassLoader(resolver: () -> ClassLoader?): ClassLoader? = try {
        resolver()
    } catch (_: Throwable) {
        null
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is java.lang.reflect.InvocationTargetException) error.targetException ?: error
        else error

    private fun logFailure(entry: HostScanEntry, error: Throwable) {
        println(
            "[ReplayGain] 反射调用宿主扫描入口失败: ${entry.className}.${entry.methodName} " +
                "(receiver=${entry.receiver}, reason=${entry.rationale}) — ${error.javaClass.simpleName}: ${error.message}",
        )
    }

    private data class ResolvedEntry(
        val cls: Class<*>,
        val method: java.lang.reflect.Method,
        val receiver: Any?,
    )
}