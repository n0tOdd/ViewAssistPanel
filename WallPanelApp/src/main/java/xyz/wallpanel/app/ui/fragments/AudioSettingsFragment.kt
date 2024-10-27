package xyz.wallpanel.app.ui.fragments

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.navigation.Navigation
import androidx.preference.SwitchPreference
import xyz.wallpanel.app.R
import xyz.wallpanel.app.ui.activities.SettingsActivity
import dagger.android.support.AndroidSupportInjection

class AudioSettingFragment : BaseSettingsFragment() {

    private var audioStreamPreference: SwitchPreference? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        AndroidSupportInjection.inject(this)
        setHasOptionsMenu(true)
    }
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (activity is SettingsActivity) {
            val actionBar = (activity as SettingsActivity).supportActionBar
            with(actionBar) {
                this?.setDisplayHomeAsUpEnabled(true)
                this?.setDisplayShowHomeEnabled(true)
                this?.title = (getString(R.string.title_audio_settings))
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_help, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                view?.let { Navigation.findNavController(it).navigate(R.id.settings_action) }
                return true
            }
            R.id.action_help -> {
                showSupport()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_audio)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        super.onViewCreated(view, savedInstanceState)

        audioStreamPreference = findPreference<SwitchPreference>(getString(R.string.key_setting_audio_enabled)) as SwitchPreference
        bindPreferenceSummaryToValue(audioStreamPreference!!)

        val description = findPreference<Preference>(getString(R.string.key_setting_directions)) as Preference
        description.summary = getString(R.string.pref_audio_description)


    }

    }