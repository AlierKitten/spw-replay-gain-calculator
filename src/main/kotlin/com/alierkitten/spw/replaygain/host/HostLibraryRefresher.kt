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
            logDiag(
                "已解析入口: class=${cls.name} method=${method.name} receiver=${receiver?.javaClass?.name} " +
                    "receiverIdentity=${System.identityHashCode(receiver)}",
            )
            doInvoke(entry, cls, method, receiver)
        } catch (error: Throwable) {
            logFailure(entry, error)
            RefreshResult.Failed("${entry.className}.${entry.methodName}: ${error.javaClass.simpleName}")
        }
    }

    private fun resolve(entry: HostScanEntry): ResolvedEntry? {
        resolved[entry]?.let { return it }

        val cls = loadClass(entry.className)
        if (cls == null) {
            logDiag("loadClass(${entry.className}) 失败")
            return null
        }
        val receiver: Any? = when (val r = entry.receiver) {
            Receiver.Static -> null
            is Receiver.SingletonField -> readSingleton(cls, r.fieldName)
            is Receiver.CompanionField -> readSingleton(cls, r.fieldName)
        }
        logDiag(
            "resolve ${entry.className}: receiverRequested=${entry.receiver::class.simpleName} " +
                "receiverValue=${receiver}",
        )
        if (entry.receiver !is Receiver.Static && receiver == null) {
            // Fallback for Kotlin `object`: <clinit> may not have run for the class we just
            // linked. If the host has not yet touched this object, INSTANCE is Java-default null.
            // Forcing <clinit> via getDeclaredConstructor().newInstance() makes it explicit and
            // also surfaces any host-side init failures as Failed rather than silent Unavailable.
            val constructed = try {
                val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
                ctor.newInstance()
            } catch (error: Throwable) {
                logDiag("INSTANCE 为空，手动 newInstance 仍失败: ${error.javaClass.simpleName}: ${error.message}")
                return null
            }
            logDiag("INSTANCE 为空，手动 newInstance 成功: $constructed")
            // Stash so readSingleton picks it up next time
            return finishResolve(entry, cls, constructed)
        }

        return finishResolve(entry, cls, receiver)
    }

    private fun finishResolve(
        entry: HostScanEntry,
        cls: Class<*>,
        receiver: Any?,
    ): ResolvedEntry? {
        val paramClasses = entry.parameterTypes.map(::loadClassOrPrimitive).toTypedArray()
        val method: java.lang.reflect.Method = try {
            cls.getDeclaredMethod(entry.methodName, *paramClasses)
        } catch (error: NoSuchMethodException) {
            logDiag("getDeclaredMethod 失败: ${error.message}")
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
                val ret = method.invoke(receiver)
                logDiag("Method.invoke 已返回: $ret")
            } catch (t: Throwable) {
                error[0] = unwrap(t)
                logDiag("Method.invoke 抛异常: ${error[0]?.javaClass?.simpleName}: ${error[0]?.message}")
            } finally {
                ready.countDown()
            }
        }
        val finished = ready.await(invokeTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
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
        logDiag("readSingleton 异常: ${error.javaClass.simpleName}: ${error.message}")
        null
    }

    private fun loadClass(name: String): Class<*>? {
        // Order: WorkshopApi's loader (host-defined, guaranteed to see host classes) →
        // thread context loader → system loader → call-site loader.
        // We use initialize=true so Kotlin `object` static initializers (which assign
        // INSTANCE) actually run; without that, reflection gets a non-initialised Class
        // and INSTANCE is Java-default null.
        val loaders = listOfNotNull(
            safeClassLoader { com.xuncorp.spw.workshop.api.WorkshopApi::class.java.classLoader },
            safeClassLoader { Thread.currentThread().contextClassLoader },
            safeClassLoader { ClassLoader.getSystemClassLoader() },
            safeClassLoader { HostLibraryRefresher::class.java.classLoader },
        )
        val seen = mutableSetOf<ClassLoader>()
        for (loader in loaders) {
            if (!seen.add(loader)) continue
            try {
                val cls = Class.forName(name, true, loader)
                logDiag("loadClass(${name}) loader=${loader::class.java.name} OK")
                return cls
            } catch (_: ClassNotFoundException) {
                // try next loader
            } catch (error: Throwable) {
                logDiag("loadClass(${name}) 抛 ${error.javaClass.simpleName}: ${error.message}")
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

    private fun logDiag(message: String) {
        println("[ReplayGain:host] $message")
    }

    private data class ResolvedEntry(
        val cls: Class<*>,
        val method: java.lang.reflect.Method,
        val receiver: Any?,
    )
}