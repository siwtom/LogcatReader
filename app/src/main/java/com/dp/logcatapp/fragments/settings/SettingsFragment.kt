package com.dp.logcatapp.fragments.settings

import android.Manifest
import android.annotation.TargetApi
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.dp.logcat.Logcat
import com.dp.logcatapp.BuildConfig
import com.dp.logcatapp.R
import com.dp.logcatapp.fragments.base.BaseDialogFragment
import com.dp.logcatapp.fragments.logcatlive.LogcatLiveFragment
import com.dp.logcatapp.fragments.settings.dialogs.FolderChooserDialogFragment
import com.dp.logcatapp.util.PreferenceKeys
import com.dp.logcatapp.util.isDarkThemeOn
import com.dp.logcatapp.util.restartApp
import com.dp.logcatapp.util.showToast
import java.io.File
import java.text.NumberFormat

class SettingsFragment : PreferenceFragmentCompat() {

  companion object {
    val TAG = SettingsFragment::class.qualifiedName
    private const val WRITE_STORAGE_PERMISSION_REQ = 12
    private const val SAVE_LOCATION_REQ = 123
  }

  override fun onCreatePreferences(
    savedInstanceState: Bundle?,
    rootKey: String?
  ) {
    addPreferencesFromResource(R.xml.settings)
    setupGeneralCategory()
    setupLogcatCategory()
  }

  private fun setupAppearanceCategory() {
    val sharedPrefs = preferenceScreen.sharedPreferences
    val themePref = findPreference<ListPreference>(PreferenceKeys.Appearance.KEY_THEME)!!
    val useBlackThemePref =
      findPreference<Preference>(PreferenceKeys.Appearance.KEY_USE_BLACK_THEME)!!

    val currTheme = sharedPrefs.getString(
      PreferenceKeys.Appearance.KEY_THEME,
      PreferenceKeys.Appearance.Default.THEME
    )!!

    when (currTheme) {
      PreferenceKeys.Appearance.Theme.AUTO,
      PreferenceKeys.Appearance.Theme.DARK -> useBlackThemePref.isEnabled = true
      else -> useBlackThemePref.isEnabled = false
    }

    val themePrefEntries = resources.getStringArray(R.array.pref_appearance_theme_entries)
    themePref.summary = themePrefEntries[currTheme.toInt()]

    themePref.onPreferenceChangeListener =
      Preference.OnPreferenceChangeListener { preference, newValue ->
        preference.summary = themePrefEntries[(newValue as String).toInt()]
        requireActivity().restartApp()
        true
      }

    useBlackThemePref.onPreferenceChangeListener = Preference
      .OnPreferenceChangeListener { _, _ ->
        val activity = requireActivity()
        if (activity.isDarkThemeOn()) {
          activity.restartApp()
        }
        true
      }
  }

  private fun setupGeneralCategory() {
    val sharedPrefs = preferenceScreen.sharedPreferences
    val sendIdentificatorPref = findPreference<Preference>(PreferenceKeys.General.KEY_KEEP_SEND_IDENTIFICATORS)!!
    val serverPortPref = findPreference<Preference>(PreferenceKeys.General.KEY_KEEP_SERVER_PORT)!!
    val generalServerIpPref =
            findPreference<Preference>(PreferenceKeys.General.KEY_KEEP_SERVER_IP)!!
      generalServerIpPref.summary = preferenceScreen.sharedPreferences
              .getString(
                      PreferenceKeys.General.KEY_KEEP_SERVER_IP,
                      PreferenceKeys.General.Default.KEY_KEEP_SERVER_IP
              )!!
    generalServerIpPref.onPreferenceChangeListener = Preference
            .OnPreferenceChangeListener { preference, newValue ->
              val activity = requireActivity()
              val serverIpValue = newValue.toString().trim()
              if(serverIpValue.isNotEmpty()){
                preference.summary = serverIpValue
                activity.restartApp()
                true
              } else {
                activity.showToast(getString(R.string.value_server_ip_must_be_not_empty))
                false
              }
            }
      //Server Port
      serverPortPref.summary = preferenceScreen.sharedPreferences
              .getString(
                      PreferenceKeys.General.KEY_KEEP_SERVER_PORT,
                      PreferenceKeys.General.Default.KEY_KEEP_SERVER_PORT
              )!!
      serverPortPref.onPreferenceChangeListener = Preference
              .OnPreferenceChangeListener { preference, newValue ->
                  val activity = requireActivity()
                  val serverPortValue = newValue.toString().trim()
                  if(serverPortValue.isNotEmpty()){
                      preference.summary = serverPortValue
                      activity.restartApp()
                      true
                  } else {
                      activity.showToast(getString(R.string.value_server_port_must_be_not_empty))
                      false
                  }
              }
      //Identification
      sendIdentificatorPref.summary = preferenceScreen.sharedPreferences
              .getString(
                      PreferenceKeys.General.KEY_KEEP_SEND_IDENTIFICATORS,
                      PreferenceKeys.General.Default.KEY_KEEP_SEND_IDENTIFICATORS
              )!!
      sendIdentificatorPref.onPreferenceChangeListener = Preference
              .OnPreferenceChangeListener { preference, newValue ->
                  val activity = requireActivity()
                  val identificationValue = newValue.toString().trim()
                  if(identificationValue.isNotEmpty()){
                      preference.summary = identificationValue
                      activity.restartApp()
                      true
                  } else {
                      activity.showToast(getString(R.string.value_identificatios_must_be_not_empty))
                      false
                  }
              }
  }

  private fun setupLogcatCategory() {
    val prefPollInterval = findPreference<Preference>(PreferenceKeys.Logcat.KEY_POLL_INTERVAL)!!
    val prefBuffers = findPreference<MultiSelectListPreference>(PreferenceKeys.Logcat.KEY_BUFFERS)!!
    val prefMaxLogs = findPreference<Preference>(PreferenceKeys.Logcat.KEY_MAX_LOGS)!!

    prefPollInterval.summary = preferenceScreen.sharedPreferences
      .getString(
        PreferenceKeys.Logcat.KEY_POLL_INTERVAL,
        PreferenceKeys.Logcat.Default.POLL_INTERVAL
      )!! + " ms"

    prefPollInterval.onPreferenceChangeListener = Preference
      .OnPreferenceChangeListener { preference, newValue ->
        val activity = requireActivity()
        try {
          val v = newValue.toString().trim()
          val num = v.toLong()
          if (num <= 0) {
            activity.showToast(getString(R.string.value_must_be_greater_than_0))
            false
          } else {
            preference.summary = "$v ms"
            true
          }
        } catch (e: NumberFormatException) {
          activity.showToast(getString(R.string.value_must_be_a_positive_integer))
          false
        }
      }

    val availableBuffers = Logcat.AVAILABLE_BUFFERS
    val defaultBuffers = PreferenceKeys.Logcat.Default.BUFFERS
    if (availableBuffers.isNotEmpty() && defaultBuffers.isNotEmpty()) {
      val bufferValues = preferenceScreen.sharedPreferences
        .getStringSet(PreferenceKeys.Logcat.KEY_BUFFERS, defaultBuffers)!!

      val toSummary = { values: Set<String> ->
        values.map { e -> availableBuffers[e.toInt()] }
          .sorted()
          .joinToString(", ")
      }

      prefBuffers.entries = availableBuffers.copyOf()
      val entryValues = mutableListOf<String>()
      for (i in availableBuffers.indices) {
        entryValues += i.toString()
      }
      prefBuffers.entryValues = entryValues.toTypedArray()
      prefBuffers.summary = toSummary(bufferValues)

      @Suppress("unchecked_cast")
      prefBuffers.onPreferenceChangeListener = Preference
        .OnPreferenceChangeListener { preference, newValue ->
          val mp = preference as MultiSelectListPreference
          val values = newValue as Set<String>

          if (values.isEmpty()) {
            false
          } else {
            mp.summary = toSummary(values)
            true
          }
        }
    } else {
      prefBuffers.isVisible = false
    }

    val maxLogs = preferenceScreen.sharedPreferences.getString(
      PreferenceKeys.Logcat.KEY_MAX_LOGS,
      PreferenceKeys.Logcat.Default.MAX_LOGS
    )!!.trim().toInt()

    prefMaxLogs.summary = NumberFormat.getInstance().format(maxLogs)
    prefMaxLogs.onPreferenceChangeListener = Preference
      .OnPreferenceChangeListener callback@{ preference, newValue ->
        val activity = requireActivity()
        try {
          val oldValue = preferenceScreen.sharedPreferences.getString(
            PreferenceKeys.Logcat.KEY_MAX_LOGS,
            PreferenceKeys.Logcat.Default.MAX_LOGS
          )!!.trim().toInt()

          val newMaxLogs = (newValue as String).trim().toInt()
          if (newMaxLogs == oldValue) {
            return@callback false
          }

          if (newMaxLogs < 1000) {
            activity.showToast(getString(R.string.cannot_be_less_than_1000))
            return@callback false
          }

          preference.summary = NumberFormat.getInstance().format(newMaxLogs)
          true
        } catch (e: NumberFormatException) {
          activity.showToast(getString(R.string.not_a_valid_number))
          false
        }
      }
  }


  private fun isExternalStorageWritable() =
    Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED


}
