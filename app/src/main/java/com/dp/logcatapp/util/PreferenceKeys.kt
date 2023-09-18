package com.dp.logcatapp.util

import com.dp.logcat.Logcat.Companion.AVAILABLE_BUFFERS
import com.dp.logcat.Logcat.Companion.DEFAULT_BUFFERS
import com.dp.logcat.Logcat.Companion.INITIAL_LOG_CAPACITY

object PreferenceKeys {

  const val MAIN_PREF_SCREEN = "pref_key_main_screen"

  object General {
    const val KEY_KEEP_SEND_IDENTIFICATORS = "pref_key_general_send_identificators"
    const val KEY_KEEP_SERVER_IP = "pref_key_general_server_ip"
    const val KEY_KEEP_SERVER_PORT = "pref_key_general_server_port"

    object Default {
      const val KEY_KEEP_SEND_IDENTIFICATORS = "no_identification"
      const val KEY_KEEP_SERVER_IP = "127.0.0.1"
      const val KEY_KEEP_SERVER_PORT = "50000"
    }
  }

  object Appearance {

    const val KEY_THEME = "pref_key_appearance_theme"
    const val KEY_USE_BLACK_THEME = "pref_key_appearance_use_black_theme"

    object Theme {

      const val AUTO = "0"
      const val LIGHT = "1"
      const val DARK = "2"
    }

    object Default {

      const val THEME = Theme.AUTO
      const val USE_BLACK_THEME = false
    }
  }

  object Logcat {
    const val KEY_POLL_INTERVAL = "pref_key_logcat_poll_interval"
    const val KEY_BUFFERS = "pref_key_logcat_buffers"
    const val KEY_MAX_LOGS = "pref_key_logcat_max_logs"
    const val KEY_SAVE_LOCATION = "pref_key_logcat_save_location"

    object Default {
      const val POLL_INTERVAL = "250"
      val BUFFERS: Set<String> = getDefaultBufferValues()
      const val MAX_LOGS = INITIAL_LOG_CAPACITY.toString()
      const val SAVE_LOCATION = ""

      private fun getDefaultBufferValues(): Set<String> {
        val bufferValues = mutableSetOf<String>()
        DEFAULT_BUFFERS.map { AVAILABLE_BUFFERS.indexOf(it) }
          .filter { it != -1 }
          .forEach { bufferValues += it.toString() }
        return bufferValues
      }
    }
  }

  object About {
    const val KEY_VERSION_NAME = "pref_key_about_version_name"
    const val KEY_GITHUB_PAGE = "pref_key_about_github_repo"
  }
}