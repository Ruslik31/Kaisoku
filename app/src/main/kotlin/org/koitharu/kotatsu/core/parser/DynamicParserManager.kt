package org.koitharu.kotatsu.core.parser

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSourceRegistry
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.parser.tsuki.TsukiLoaderContext
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import tsuki.MangaParser as TsukiMangaParser
import tsuki.model.MangaSource as TsukiMangaSource

class PluginClassLoader(
	dexPath: String,
	optimizedDirectory: String?,
	librarySearchPath: String?,
	parent: ClassLoader,
) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {

	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		// tsuki.* plugins link against the host's Tsuki core; their own sites/factory are found
		// in the plugin dex after the parent lookup misses.
		if (name.startsWith("tsuki.")) {
			return super.loadClass(name, resolve)
		}
		if (name == "org.koitharu.kotatsu.parsers.util.LinkResolver" ||
			name.startsWith("org.koitharu.kotatsu.parsers.util.LinkResolver$") ||
			name == "org.koitharu.kotatsu.parsers.MangaLoaderContext" ||
			name == "org.koitharu.kotatsu.parsers.MangaParserAuthProvider" ||
			(name.startsWith("org.koitharu.kotatsu.parsers.model.") &&
				name != "org.koitharu.kotatsu.parsers.model.MangaParserSource") ||
			name.startsWith("org.koitharu.kotatsu.parsers.config.") ||
			name.startsWith("org.koitharu.kotatsu.parsers.exception.")
		) {
			return super.loadClass(name, resolve)
		}
		if (name == "org.koitharu.kotatsu.parsers.MangaParser" ||
			name == "org.koitharu.kotatsu.parsers.model.MangaParserSource" ||
			name.startsWith("org.koitharu.kotatsu.parsers.site.") ||
			name.startsWith("org.koitharu.kotatsu.parsers.core.") ||
			name.startsWith("org.koitharu.kotatsu.core.parser.") ||
			name.startsWith("org.koitharu.kotatsu.parsers.util.") ||
			name.startsWith("org.koitharu.kotatsu.parsers.MangaParserFactory") ||
			name.startsWith("eu.kanade.tachiyomi.") ||
			name.startsWith("uy.kohesive.injekt.") ||
			name.startsWith("rx.") ||
			name.startsWith("keiyoushi.")
		) {
			return runCatching { findClass(name) }.getOrElse { super.loadClass(name, resolve) }
		}
		return super.loadClass(name, resolve)
	}
}

object DynamicParserManager {

	data class LoadedParser(
		val source: PluginMangaSource,
		val delegate: Any,
	)

	data class LoadedTsukiParser(
		val source: PluginMangaSource,
		val parser: TsukiMangaParser,
	)

	private enum class PluginFormat(
		val factoryClass: String,
		val sourceClass: String,
		val contextClass: String,
	) {
		TSUKI("tsuki.MangaParserFactoryKt", "tsuki.model.MangaParserSource", "tsuki.MangaLoaderContext"),
		LEGACY(
			"org.koitharu.kotatsu.parsers.MangaParserFactoryKt",
			"org.koitharu.kotatsu.parsers.model.MangaParserSource",
			"org.koitharu.kotatsu.parsers.MangaLoaderContext",
		),
	}

	@Volatile
	private var delegateIndex: Map<Any, PluginMangaSource> = emptyMap()

	@Volatile
	private var loadErrors: Map<String, Throwable> = emptyMap()

	private const val LOAD_COPY_PREFIX = "plugin_load_"

	private val classLoaders = mutableMapOf<String, ClassLoader>()
	private val newParserMethods = mutableMapOf<String, Method>()
	private val methodCache = ConcurrentHashMap<MethodKey, Method>()

	// Serialized: startup, imports and auto-updates reload concurrently and share the code-cache copies.
	@Synchronized
	@Throws(Exception::class)
	fun loadParsersFromDirectory(context: Context, pluginDir: File) {
		val cacheDir = context.codeCacheDir.absolutePath
		val parent = context.classLoader
		val sources = mutableListOf<MangaSource>()
		val methods = mutableMapOf<String, Method>()
		val loaders = mutableMapOf<String, ClassLoader>()
		val errors = mutableMapOf<String, Throwable>()
		if (!pluginDir.exists()) {
			pluginDir.mkdirs()
		}
		val codeCache = context.codeCacheDir
		// Load from a per-version copy so replacing a jar never touches a file an old class loader maps.
		codeCache.listFiles { file -> file.name.startsWith(LOAD_COPY_PREFIX) && file.extension == "jar" }
			?.forEach { stale ->
				stale.setWritable(true, true)
				stale.delete()
			}
		val jars = (pluginDir.listFiles { file -> file.extension == "jar" } ?: emptyArray()).sortedBy { it.name }
		for (jar in jars) {
			jar.setReadOnly()
			val loadFile = runCatching {
				File(codeCache, "$LOAD_COPY_PREFIX${jar.nameWithoutExtension}_${jar.lastModified()}.jar").also { copy ->
					jar.copyTo(copy, overwrite = true)
					copy.setReadOnly()
				}
			}.getOrElse { jar }
			val classLoader = PluginClassLoader(loadFile.absolutePath, cacheDir, null, parent)
			try {
				val format = PluginFormat.entries.firstNotNullOfOrNull { format ->
					runCatching { format to classLoader.loadClass(format.factoryClass) }.getOrNull()
				} ?: throw ClassNotFoundException("No parser factory in ${jar.name}")
				val factory = format.second
				val enumClass = classLoader.loadClass(format.first.sourceClass)
				val contextClass = classLoader.loadClass(format.first.contextClass)
				val newParser = factory.getMethod("newParser", enumClass, contextClass)
				enumClass.enumConstants?.forEach { constant ->
					if (constant is MangaSource || constant is TsukiMangaSource) {
						val source = PluginMangaSource(constant, jar.name)
						sources.add(source)
						methods[source.name] = newParser
					}
				}
				loaders[jar.name] = classLoader
			} catch (e: Throwable) {
				Log.w(TAG, "Failed to load parser plugin ${jar.name}", e)
				errors[jar.name] = e
			}
		}
		synchronized(this) {
			classLoaders.clear()
			newParserMethods.clear()
			methodCache.clear()
			classLoaders.putAll(loaders)
			newParserMethods.putAll(methods)
			loadErrors = errors
			delegateIndex = sources.filterIsInstance<PluginMangaSource>()
				.associateByTo(IdentityHashMap()) { it.delegate }
			MangaSourceRegistry.replaceAll(sources)
		}
	}

	/** Why a jar in the plugins directory could not be loaded, keyed by file name. */
	fun getLoadErrors(): Map<String, Throwable> = loadErrors

	/** The loaded plugin source that wraps a plugin's own enum constant, if any. */
	fun findPluginSource(delegate: Any): PluginMangaSource? = delegateIndex[delegate]
		?: (delegate as? TsukiMangaSource)?.let { stale ->
			// A repository created before a reload still holds the previous jar's constants.
			delegateIndex.values.firstOrNull { it.isTsuki && it.sourceName == stale.name }
		}

	fun getTsukiSources(): List<TsukiMangaSource> = delegateIndex.keys.filterIsInstance<TsukiMangaSource>()

	fun deletePlugin(context: Context, jarName: String) {
		val dir = PluginFileLoader.pluginsDir(context)
		File(dir, jarName).takeIf { it.exists() }?.run {
			setWritable(true, true)
			delete()
		}
		loadParsersFromDirectory(context, dir)
	}

	fun getInstalledPlugins(context: Context): List<String> =
		PluginFileLoader.pluginsDir(context)
			.listFiles { file -> file.extension == "jar" }
			?.map { it.name }
			.orEmpty()

	fun createParser(
		source: MangaSource,
		loaderContext: MangaLoaderContext,
		tsukiContext: TsukiLoaderContext?,
		appContext: Context,
	): LoadedParser {
		val context = appContext.applicationContext
		val pluginSource = resolvePluginSource(source)
			?: throw IllegalArgumentException(context.getString(R.string.plugin_not_found, source.name))
		val classLoader = classLoaders[pluginSource.jarName]
		val factoryMethod = newParserMethods[pluginSource.name]
		if (classLoader == null || factoryMethod == null) {
			throw IllegalStateException(
				if (classLoader == null) {
					context.getString(R.string.jar_not_loaded, pluginSource.jarName)
				} else {
					context.getString(R.string.unknown_source, source.name)
				},
			)
		}
		// Re-resolve from the current class loader: a source captured before a reload wraps a stale constant.
		val format = if (pluginSource.isTsuki) PluginFormat.TSUKI else PluginFormat.LEGACY
		val constant = classLoader.loadClass(format.sourceClass).enumConstants?.firstOrNull {
			(it as? Enum<*>)?.name == pluginSource.sourceName
		} ?: throw IllegalArgumentException(context.getString(R.string.missing_in_plugin, pluginSource.sourceName))
		val parserContext: Any = if (pluginSource.isTsuki) {
			tsukiContext ?: throw UnsupportedOperationException("No tsuki.* host for ${pluginSource.name}")
		} else {
			loaderContext
		}
		val delegate = try {
			factoryMethod.invoke(null, constant, parserContext)
		} catch (e: InvocationTargetException) {
			throw e.targetException
		} ?: throw IllegalStateException(context.getString(R.string.loaded_null))
		return LoadedParser(pluginSource, delegate)
	}

	fun createTsukiParser(
		source: MangaSource,
		tsukiContext: TsukiLoaderContext,
		appContext: Context,
	): LoadedTsukiParser {
		val loaded = createParser(source, tsukiContext.hostContext, tsukiContext, appContext)
		return LoadedTsukiParser(loaded.source, loaded.delegate as TsukiMangaParser)
	}

	fun createParserProxy(
		source: MangaSource,
		loaderContext: MangaLoaderContext,
		appContext: Context,
	): MangaParser {
		val pluginSource = resolvePluginSource(source)
		if (pluginSource?.isTsuki == true) {
			throw UnsupportedOperationException("tsuki.* plugin ${pluginSource.name} has no org.koitharu parser")
		}
		val loaded = createParser(source, loaderContext, tsukiContext = null, appContext = appContext)
		return Proxy.newProxyInstance(
			MangaParser::class.java.classLoader,
			arrayOf(MangaParser::class.java),
		) { proxy, method, args ->
			when (method.name) {
				"toString" -> "PluginParser[${loaded.source.name}]"
				"hashCode" -> loaded.delegate.hashCode()
				"equals" -> proxy === args?.firstOrNull()
				"getSource" -> MangaParserSource.entries.firstOrNull { it.name == loaded.source.sourceName }
					?: throw IllegalStateException(appContext.getString(R.string.unknown_source, loaded.source.name))
				else -> invoke(loaded.delegate, method.name, *(args ?: emptyArray()))
			}
		} as MangaParser
	}

	fun invoke(delegate: Any, name: String, vararg args: Any?): Any? {
		val method = methodCache.getOrPut(MethodKey(delegate.javaClass, name, args.size, isSuspend = false)) {
			findCompatibleMethod(delegate.javaClass, name, args, isSuspend = false)
		}
		return try {
			method.invoke(delegate, *args)
		} catch (e: InvocationTargetException) {
			throw e.targetException
		}
	}

	suspend fun invokeSuspend(delegate: Any, name: String, vararg args: Any?): Any? = suspendCoroutine { cont ->
		val method = methodCache.getOrPut(MethodKey(delegate.javaClass, name, args.size, isSuspend = true)) {
			findCompatibleMethod(delegate.javaClass, name, args, isSuspend = true)
		}
		try {
			@Suppress("UNCHECKED_CAST")
			val continuation = cont as Continuation<Any?>
			val result = method.invoke(delegate, *args, continuation)
			if (result !== COROUTINE_SUSPENDED) {
				cont.resume(result)
			}
		} catch (e: InvocationTargetException) {
			cont.resumeWithException(e.targetException)
		} catch (t: Throwable) {
			cont.resumeWithException(t)
		}
	}

	private fun resolvePluginSource(source: MangaSource): PluginMangaSource? {
		(source as? PluginMangaSource)?.let { return it }
		return MangaSourceRegistry.resolve(source.name) as? PluginMangaSource
	}

	private fun findCompatibleMethod(
		target: Class<*>,
		name: String,
		args: Array<out Any?>,
		isSuspend: Boolean,
	): Method {
		val expectedCount = args.size + if (isSuspend) 1 else 0
		val candidates = target.methods.filter { method ->
			method.name == name &&
				method.parameterCount == expectedCount &&
				(!isSuspend || Continuation::class.java.isAssignableFrom(method.parameterTypes.last()))
		}
		return candidates.firstOrNull { method ->
			matchesParams(method.parameterTypes, args, isSuspend)
		} ?: candidates.firstOrNull() ?: throw NoSuchMethodException(name)
	}

	private fun matchesParams(
		parameterTypes: Array<Class<*>>,
		args: Array<out Any?>,
		isSuspend: Boolean,
	): Boolean {
		for (i in args.indices) {
			val arg = args[i] ?: continue
			if (!parameterTypes[i].boxed().isAssignableFrom(arg.javaClass.boxed())) {
				return false
			}
		}
		return !isSuspend || Continuation::class.java.isAssignableFrom(parameterTypes.last())
	}

	private fun Class<*>.boxed(): Class<*> = when (this) {
		java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
		java.lang.Byte.TYPE -> java.lang.Byte::class.java
		java.lang.Character.TYPE -> java.lang.Character::class.java
		java.lang.Double.TYPE -> java.lang.Double::class.java
		java.lang.Float.TYPE -> java.lang.Float::class.java
		java.lang.Integer.TYPE -> java.lang.Integer::class.java
		java.lang.Long.TYPE -> java.lang.Long::class.java
		java.lang.Short.TYPE -> java.lang.Short::class.java
		java.lang.Void.TYPE -> java.lang.Void::class.java
		else -> this
	}

	private data class MethodKey(
		val target: Class<*>,
		val name: String,
		val arity: Int,
		val isSuspend: Boolean,
	)

	private const val TAG = "DynamicParserManager"
}
