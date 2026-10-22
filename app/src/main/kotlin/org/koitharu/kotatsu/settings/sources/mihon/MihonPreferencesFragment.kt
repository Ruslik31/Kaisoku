package org.koitharu.kotatsu.settings.sources.mihon

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.preferenceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.settings.SettingsActivity
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject

/** A separate screen is necessary: configuration-only wrappers replace every child during setup. */
@AndroidEntryPoint
class MihonPreferencesFragment : BasePreferenceFragment(R.string.mihon_source_preferences),
    SharedPreferences.OnSharedPreferenceChangeListener {

    @Inject lateinit var extensions: MihonExtensionManager
    @Inject lateinit var sources: MangaSourcesRepository

    private var source: MihonMangaSource? = null
    private var sourcePreferences: SharedPreferences? = null
    private var receiverRegistered = false
    private var errorDetails: String? = null
    private val guarded = Collections.newSetFromMap(WeakHashMap<Preference, Boolean>())
    private val unsupportedHolders = Collections.newSetFromMap(WeakHashMap<PreferenceViewHolder, Boolean>())
    private val replacementReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == replacementAction()) {
                refreshSources()
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        protect {
            val requested = MangaSource(arguments?.getString(AppRouter.KEY_SOURCE)) as? MihonMangaSource
                ?: error("Extension source is unavailable")
            source = requested
            val loaded = extensions.resolve(requested) ?: error("Extension source is unavailable")
            val configurable = loaded.sourceInstance as? ConfigurableSource
                ?: error("Extension does not expose preferences")
            sourcePreferences = configurable.getSourcePreferences()
            preferenceManager.sharedPreferencesName = configurable.preferenceKey()
            preferenceManager.preferenceDataStore = SourcePreferenceDataStore(requireNotNull(sourcePreferences))
            val screen = preferenceManager.createPreferenceScreen(requireContext())
            preferenceScreen = screen
            configurable.setupPreferenceScreen(screen)
            guardListeners(screen)
            val nestedKey = arguments?.getString(KEY_SCREEN)
            if (nestedKey != null) {
                preferenceScreen = screen.findPreference<PreferenceScreen>(nestedKey)
                    ?: error("Extension preference screen is unavailable: $nestedKey")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        sourcePreferences?.registerOnSharedPreferenceChangeListener(this)
        val filter = IntentFilter(replacementAction()).apply { addDataScheme("package") }
        ContextCompat.registerReceiver(requireContext(), replacementReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        setTitle(source?.resolved()?.displayName ?: getString(R.string.mihon_source_preferences))
        // Nested screens can be populated by wrapper-owned, user-triggered asynchronous callbacks.
        guardListeners(preferenceScreen)
    }

    override fun onStop() {
        sourcePreferences?.unregisterOnSharedPreferenceChangeListener(this)
        if (receiverRegistered) {
            requireContext().unregisterReceiver(replacementReceiver)
            receiverRegistered = false
        }
        // A wrapper may alter its own installed-runtime files without changing preferences.
        source?.let { extensions.invalidatePackage(it.packageName) }
        refreshSources()
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        source?.let { extensions.invalidatePackage(it.packageName) }
    }

    @SuppressLint("RestrictedApi")
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        // Obtain a normal AndroidX holder through its adapter: its constructor is package-private.
        val fallbackScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(Preference(context).apply { isPersistent = false })
        }
        val fallback = PreferenceGroupAdapter(fallbackScreen)
        return object : PreferenceGroupAdapter(preferenceScreen) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreferenceViewHolder =
                protect<PreferenceViewHolder?>(null) { super.onCreateViewHolder(parent, viewType) }
                    ?: fallback.onCreateViewHolder(parent, fallback.getItemViewType(0)).also(unsupportedHolders::add)

            override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
                val item = getItem(position) ?: return
                guardPreference(item)
                if (holder in unsupportedHolders) {
                    holder.itemView.findViewById<TextView>(android.R.id.title).text = item.title
                    holder.itemView.findViewById<TextView>(android.R.id.summary)
                        .setText(R.string.mihon_preferences_error_summary)
                    holder.itemView.setOnClickListener { showError() }
                } else {
                    if (!protect(false) { super.onBindViewHolder(holder, position); true }) {
                        holder.itemView.findViewById<TextView>(android.R.id.title)?.text = item.title
                        holder.itemView.findViewById<TextView>(android.R.id.summary)
                            ?.setText(R.string.mihon_preferences_error_summary)
                        holder.itemView.setOnClickListener { showError() }
                    }
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == KEY_ERROR) {
            showError()
            return true
        }
        if (preference is PreferenceScreen) {
            val key = preference.key
            if (key.isNullOrEmpty()) {
                showFailure(IllegalArgumentException("Extension nested screen has no key"))
            } else {
                (activity as? SettingsActivity)?.openFragment(javaClass, Bundle(requireArguments()).apply {
                    putString(KEY_SCREEN, key)
                }, false)
            }
            return true
        }
        return protect(false) { super.onPreferenceTreeClick(preference) }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        // Standard AndroidX dialogs keep their native validation and change callbacks. Unsupported
        // custom dialogs fail inside this host instead of taking down source settings.
        protect { super.onDisplayPreferenceDialog(preference) }
    }

    private fun refreshSources() {
        val current = source ?: return
        extensions.invalidatePackage(current.packageName)
        processLifecycleScope.launch(Dispatchers.IO) {
            try {
                sources.refreshInstalledMihonSources()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Loading failures remain available in Sources → Extension loading errors.
            }
        }
    }

    private fun guardListeners(group: PreferenceGroup) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            guardPreference(preference)
            if (preference is PreferenceGroup) guardListeners(preference)
        }
    }

    private fun guardPreference(preference: Preference) {
        if (!guarded.add(preference)) return
        preference.onPreferenceClickListener?.let { original ->
            preference.setOnPreferenceClickListener { protect(true) { original.onPreferenceClick(it) } }
        }
        preference.onPreferenceChangeListener?.let { original ->
            preference.setOnPreferenceChangeListener { item, value ->
                protect(false) { original.onPreferenceChange(item, value) }
            }
        }
    }

    private fun showFailure(error: Throwable) {
        errorDetails = error.getCopyableErrorDetails()
        val errorRow = findPreference<Preference>(KEY_ERROR) ?: Preference(requireContext()).apply {
            key = KEY_ERROR
            isPersistent = false
            order = Int.MIN_VALUE
            setTitle(R.string.mihon_preferences_error)
            icon = getWarningIcon()
            preferenceScreen.addPreference(this)
        }
        errorRow.setSummary(R.string.mihon_preferences_error_summary)
    }

    private fun showError() {
        val details = errorDetails ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mihon_preferences_error)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                requireContext().copyToClipboard(getString(R.string.mihon_preferences_error), details)
            }.show()
    }

    private fun replacementAction() = requireContext().packageName + ".ACTION_EXTENSION_REPLACED"

    private inline fun protect(block: () -> Unit) { protect(Unit, block) }

    private inline fun <T> protect(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (e is CancellationException || e is InterruptedException || e is VirtualMachineError || e is ThreadDeath) throw e
        showFailure(e)
        fallback
    }

    companion object {
        private const val KEY_SCREEN = "extension_screen"
        private const val KEY_ERROR = "kaisoku_extension_error"
    }
}
