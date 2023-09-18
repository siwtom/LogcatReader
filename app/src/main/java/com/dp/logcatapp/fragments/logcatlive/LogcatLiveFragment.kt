package com.dp.logcatapp.fragments.logcatlive

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dp.logcat.Filter
import com.dp.logcat.Log
import com.dp.logcat.Logcat
import com.dp.logcat.LogsReceivedListener
import com.dp.logcatapp.BuildConfig
import com.dp.logcatapp.R
import com.dp.logcatapp.activities.BaseActivityWithToolbar
import com.dp.logcatapp.activities.FiltersActivity
import com.dp.logcatapp.activities.SavedLogsActivity
import com.dp.logcatapp.db.FilterInfo
import com.dp.logcatapp.fragments.base.BaseFragment
import com.dp.logcatapp.fragments.filters.FilterType
import com.dp.logcatapp.fragments.logcatlive.dialogs.AskingForRootAccessDialogFragment
import com.dp.logcatapp.fragments.logcatlive.dialogs.ManualMethodToGrantPermissionDialogFragment
import com.dp.logcatapp.fragments.logcatlive.dialogs.NeedPermissionDialogFragment
import com.dp.logcatapp.fragments.logcatlive.dialogs.OnSavedBottomSheetDialogFragment
import com.dp.logcatapp.fragments.logcatlive.dialogs.RestartAppMessageDialogFragment
import com.dp.logcatapp.fragments.shared.dialogs.CopyToClipboardDialogFragment
import com.dp.logcatapp.fragments.shared.dialogs.FilterExclusionDialogFragment
import com.dp.logcatapp.services.LogcatService
import com.dp.logcatapp.services.getService
import com.dp.logcatapp.util.PreferenceKeys
import com.dp.logcatapp.util.ServiceBinder
import com.dp.logcatapp.util.SuCommander
import com.dp.logcatapp.util.containsIgnoreCase
import com.dp.logcatapp.util.getAndroidViewModel
import com.dp.logcatapp.util.getDefaultSharedPreferences
import com.dp.logcatapp.util.inflateLayout
import com.dp.logcatapp.util.showSnackbar
import com.dp.logcatapp.util.showToast
import com.dp.logcatapp.views.IndeterminateProgressSnackBar
import com.dp.logger.Logger
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogcatLiveFragment : BaseFragment(), ServiceConnection, LogsReceivedListener {
  companion object {
    val TAG = LogcatLiveFragment::class.qualifiedName
    const val LOGCAT_DIR = "logcat"

    private const val SEARCH_FILTER_TAG = "search_filter_tag"

    private val STOP_RECORDING = TAG + "_stop_recording"

    fun newInstance(stopRecording: Boolean): LogcatLiveFragment {
      val bundle = Bundle()
      bundle.putBoolean(STOP_RECORDING, stopRecording)
      val frag = LogcatLiveFragment()
      frag.arguments = bundle
      return frag
    }
  }

  private lateinit var serviceBinder: ServiceBinder
  private lateinit var recyclerView: RecyclerView
  private lateinit var linearLayoutManager: LinearLayoutManager
  private lateinit var viewModel: LogcatLiveViewModel
  private lateinit var adapter: MyRecyclerViewAdapter
  private lateinit var fabUp: FloatingActionButton
  private lateinit var fabDown: FloatingActionButton
  private lateinit var snackBarProgress: IndeterminateProgressSnackBar
  private var logcatService: LogcatService? = null
  private var ignoreScrollEvent = false
  private var searchViewActive = false
  private var lastLogId = -1
  private var lastSearchRunnable: Runnable? = null
  private var searchTask: Job? = null

  private val hideFabUpRunnable: Runnable = Runnable {
    fabUp.hide()
  }

  private val hideFabDownRunnable: Runnable = Runnable {
    fabDown.hide()
  }

  private val onScrollListener = object : RecyclerView.OnScrollListener() {
    var lastDy = 0

    override fun onScrolled(
      recyclerView: RecyclerView,
      dx: Int,
      dy: Int
    ) {
      if (dy > 0 && lastDy <= 0) {
        hideFabUp()
        showFabDown()
      } else if (dy < 0 && lastDy >= 0) {
        showFabUp()
        hideFabDown()
      }
      lastDy = dy
    }

    override fun onScrollStateChanged(
      recyclerView: RecyclerView,
      newState: Int
    ) {
      when (newState) {
        RecyclerView.SCROLL_STATE_DRAGGING -> {
          viewModel.autoScroll = false
          if (lastDy > 0) {
            hideFabUp()
            showFabDown()
          } else if (lastDy < 0) {
            showFabUp()
            hideFabDown()
          }
        }
        else -> {
          var firstPos = -1
          if (searchViewActive && !viewModel.autoScroll &&
            newState == RecyclerView.SCROLL_STATE_IDLE
          ) {
            firstPos = linearLayoutManager.findFirstCompletelyVisibleItemPosition()
            if (firstPos != RecyclerView.NO_POSITION) {
              val log = adapter[firstPos]
              lastLogId = log.id
            }
          }

          val pos = linearLayoutManager.findLastCompletelyVisibleItemPosition()
          if (pos == RecyclerView.NO_POSITION) {
            viewModel.autoScroll = false
            return
          }

          if (ignoreScrollEvent) {
            if (pos == adapter.itemCount) {
              ignoreScrollEvent = false
            }
            return
          }

          if (pos == 0) {
            hideFabUp()
          }

          if (firstPos == RecyclerView.NO_POSITION) {
            firstPos = linearLayoutManager.findFirstCompletelyVisibleItemPosition()
          }

          viewModel.scrollPosition = firstPos
          viewModel.autoScroll = pos == adapter.itemCount - 1

          if (viewModel.autoScroll) {
            hideFabUp()
            hideFabDown()
          }
        }
      }
    }
  }

  private fun showFabUp() {
    handler.removeCallbacks(hideFabUpRunnable)
    fabUp.show()
    handler.postDelayed(hideFabUpRunnable, 2000)
  }

  private fun hideFabUp() {
    handler.removeCallbacks(hideFabUpRunnable)
    fabUp.hide()
  }

  private fun showFabDown() {
    handler.removeCallbacks(hideFabDownRunnable)
    fabDown.show()
    handler.postDelayed(hideFabDownRunnable, 2000)
  }

  private fun hideFabDown() {
    handler.removeCallbacks(hideFabDownRunnable)
    fabDown.hide()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setHasOptionsMenu(true)
    serviceBinder = ServiceBinder(LogcatService::class.java, this)

    val activity = requireActivity()
    val maxLogs = activity.getDefaultSharedPreferences()
      .getString(
        PreferenceKeys.Logcat.KEY_MAX_LOGS,
        PreferenceKeys.Logcat.Default.MAX_LOGS
      )!!.trim().toInt()
    adapter = MyRecyclerViewAdapter(activity, maxLogs)
    activity.getDefaultSharedPreferences().registerOnSharedPreferenceChangeListener(adapter)

    viewModel = activity.getAndroidViewModel()
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? =
    inflateLayout(R.layout.fragment_logcat_live)

  override fun onViewCreated(
    view: View,
    savedInstanceState: Bundle?
  ) {
    super.onViewCreated(view, savedInstanceState)
    recyclerView = view.findViewById(R.id.recyclerView)
    linearLayoutManager = LinearLayoutManager(activity)
    recyclerView.layoutManager = linearLayoutManager
    recyclerView.itemAnimator = null
    recyclerView.addItemDecoration(
      DividerItemDecoration(
        activity,
        linearLayoutManager.orientation
      )
    )
    recyclerView.adapter = adapter

    recyclerView.addOnScrollListener(onScrollListener)

    fabDown = view.findViewById(R.id.fabDown)
    fabDown.setOnClickListener {
      logcatService?.logcat?.pause()
      hideFabDown()
      ignoreScrollEvent = true
      viewModel.autoScroll = true
      linearLayoutManager.scrollToPosition(adapter.itemCount - 1)
      resumeLogcat()
    }

    snackBarProgress = IndeterminateProgressSnackBar(view, getString(R.string.saving))

    fabUp = view.findViewById(R.id.fabUp)
    fabUp.setOnClickListener {
      logcatService?.logcat?.pause()
      hideFabUp()
      viewModel.autoScroll = false
      linearLayoutManager.scrollToPositionWithOffset(0, 0)
      resumeLogcat()
    }

    hideFabUp()
    hideFabDown()

    adapter.setOnClickListener { v ->
      val pos = linearLayoutManager.getPosition(v)
      if (pos >= 0) {
        viewModel.autoScroll = false
        val log = adapter[pos]
        CopyToClipboardDialogFragment.newInstance(log)
          .show(parentFragmentManager, CopyToClipboardDialogFragment.TAG)
      }
    }

    adapter.setOnLongClickListener { v ->
      val pos = linearLayoutManager.getPosition(v)
      if (pos >= 0) {
        viewModel.autoScroll = false
        val log = adapter[pos]
        FilterExclusionDialogFragment.newInstance(log)
          .show(parentFragmentManager, FilterExclusionDialogFragment.TAG)
      }
    }

    if (!checkReadLogsPermission() && !viewModel.showedGrantPermissionInstruction) {
      viewModel.showedGrantPermissionInstruction = true
      NeedPermissionDialogFragment().let {
        it.setTargetFragment(this, 0)
        it.show(parentFragmentManager, NeedPermissionDialogFragment.TAG)
      }
    }

    //Check Networkd
    if(!isOnline()) {
      requireActivity().showToast("Network disconnected!")
      logcatService?.let {
        val logcat = it.logcat
        logcat.pause()
      }
    }
    viewModel.getFilters().observe(viewLifecycleOwner, Observer { filters ->
      if (filters != null) {
        logcatService?.let {
          val logcat = it.logcat
          logcat.pause()
          logcat.clearFilters(exclude = SEARCH_FILTER_TAG)
          logcat.clearExclusions()

          for (filter in filters) {
            if (filter.exclude) {
              logcat.addExclusion(
                "${filter.hashCode()}",
                LogFilter(filter)
              )
            } else {
              logcat.addFilter(
                "${filter.hashCode()}",
                LogFilter(filter)
              )
            }
          }

          adapter.setItems(logcat.getLogsFiltered())
          updateToolbarSubtitle(adapter.itemCount)
          scrollRecyclerView()
          resumeLogcat()
        }
      }
    })
  }

  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)

    viewModel.getFileSaveNotifier().observe(viewLifecycleOwner, Observer { saveInfo ->
      saveInfo?.let {
        if (viewModel.alreadySaved) {
          return@Observer
        }
        when (it.result) {
          SaveInfo.IN_PROGRESS -> {
            snackBarProgress.show()
          }
          else -> {
            snackBarProgress.dismiss()
            when (it.result) {
              SaveInfo.SUCCESS -> {
                OnSavedBottomSheetDialogFragment.newInstance(it.fileName!!, it.uri!!)
                  .show(parentFragmentManager, OnSavedBottomSheetDialogFragment.TAG)
              }
              SaveInfo.ERROR_EMPTY_LOGS -> {
                showSnackbar(view, getString(R.string.nothing_to_save))
              }
              else -> {
                showSnackbar(view, getString(R.string.failed_to_save_logs))
              }
            }
          }
        }
      }
    })
  }

  private fun checkReadLogsPermission() = ContextCompat.checkSelfPermission(
    requireContext(),
    Manifest.permission.READ_LOGS
  ) == PackageManager.PERMISSION_GRANTED

  override fun onCreateOptionsMenu(
    menu: Menu,
    inflater: MenuInflater
  ) {
    super.onCreateOptionsMenu(menu, inflater)

    inflater.inflate(R.menu.logcat_live, menu)
    val searchItem = menu.findItem(R.id.action_search)
    val searchView = searchItem.actionView as SearchView

    var reachedBlank = false
    searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
      override fun onQueryTextChange(newText: String): Boolean {
        searchViewActive = true
        removeLastSearchRunnableCallback()

        if (newText.isBlank()) {
          reachedBlank = true
          onSearchViewClose()
        } else {
          reachedBlank = false
          logcatService?.logcat?.let {
            lastSearchRunnable = Runnable {
              runSearchTask(it, newText)
            }.also {
              handler.postDelayed(it, 100)
            }
          }
        }
        return true
      }

      override fun onQueryTextSubmit(query: String) = false
    })

    val playPauseItem = menu.findItem(R.id.action_play_pause)
    val recordToggleItem = menu.findItem(R.id.action_record_toggle)

    searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
      playPauseItem.isVisible = !hasFocus
      recordToggleItem.isVisible = !hasFocus
    }

    searchView.setOnCloseListener {
      removeLastSearchRunnableCallback()
      searchViewActive = false
      if (!reachedBlank) {
        onSearchViewClose()
      }
      playPauseItem.isVisible = true
      recordToggleItem.isVisible = true
      false
    }
  }

  private fun onSearchViewClose() {
    logcatService?.logcat?.let {
      it.pause()
      it.removeFilter(SEARCH_FILTER_TAG)

      adapter.clear()
      addAllLogs(it.getLogsFiltered())
      if (lastLogId == -1) {
        scrollRecyclerView()
      } else {
        viewModel.autoScroll = linearLayoutManager.findLastCompletelyVisibleItemPosition() ==
          adapter.itemCount - 1
        if (!viewModel.autoScroll) {
          viewModel.scrollPosition = lastLogId
          linearLayoutManager.scrollToPositionWithOffset(lastLogId, 0)
        }
        lastLogId = -1
      }
    }

    resumeLogcat()
  }

  override fun onPrepareOptionsMenu(menu: Menu) {
    super.onPrepareOptionsMenu(menu)

    val playPauseItem = menu.findItem(R.id.action_play_pause)
    val recordToggleItem = menu.findItem(R.id.action_record_toggle)

    val context = requireContext()
    logcatService?.let {
      if (it.paused) {
        playPauseItem.icon = ContextCompat.getDrawable(
          context,
          R.drawable.ic_play_arrow_white_24dp
        )
        playPauseItem.title = getString(R.string.resume)
      } else {
        playPauseItem.icon = ContextCompat.getDrawable(
          context,
          R.drawable.ic_pause_white_24dp
        )
        playPauseItem.title = getString(R.string.pause)
      }

      if (it.recording) {
        recordToggleItem.icon = ContextCompat.getDrawable(
          context,
          R.drawable.ic_stop_white_24dp
        )
        recordToggleItem.title = getString(R.string.stop_recording)
      } else {
        recordToggleItem.icon = ContextCompat.getDrawable(
          context,
          R.drawable.ic_fiber_manual_record_white_24dp
        )
        recordToggleItem.title = getString(R.string.start_recording)
      }
    }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      R.id.action_search -> {
        true
      }
      R.id.action_play_pause -> {
        logcatService?.let {
          val newPausedState = !it.paused
          if (newPausedState) {
            it.logcat.pause()
          } else {
            it.logcat.resume()
          }
          it.paused = newPausedState
          activity?.invalidateOptionsMenu()
        }
        true
      }
      R.id.action_record_toggle -> {
        logcatService?.let {
          val recording = !it.recording

          if (recording && it.paused) {
            return@let
          }

          it.updateNotification(recording)

          val logcat = it.logcat
          if (recording) {
            Snackbar.make(
              requireView(), getString(R.string.started_recording),
              Snackbar.LENGTH_SHORT
            ).show()
            logcat.startRecording()
          } else {
            saveToFile(true)
          }

          it.recording = recording
          activity?.invalidateOptionsMenu()
        }
        true
      }
      R.id.clear_action -> {
        logcatService?.logcat?.clearLogs {
          adapter.clear()
          updateToolbarSubtitle(adapter.itemCount)
        }
        true
      }
      R.id.filters_action -> {
        moveToFilterActivity(false)
        true
      }
      R.id.exclusions_action -> {
        moveToFilterActivity(true)
        true
      }
      R.id.action_save -> {
        saveToFile(false)
        true
      }
      R.id.action_view_saved_logs -> {
        startActivity(Intent(activity, SavedLogsActivity::class.java))
        true
      }
      R.id.action_restart_logcat -> {
        adapter.clear()
        logcatService?.logcat?.restart()
        true
      }
      else -> return super.onOptionsItemSelected(item)
    }
  }

  private fun moveToFilterActivity(isExclusion: Boolean) {
    val intent = Intent(requireActivity(), FiltersActivity::class.java)
    intent.putExtra(FiltersActivity.EXTRA_EXCLUSIONS, isExclusion)
    startActivity(intent)
  }

  fun tryStopRecording() {
    viewModel.stopRecording = true
    if (logcatService != null) {
      stopRecording()
    }
  }

  private fun stopRecording() {
    logcatService?.let {
      if (it.recording) {
        it.updateNotification(false)
        saveToFile(true)
        it.recording = false
        activity?.invalidateOptionsMenu()
      }
    }
    viewModel.stopRecording = false
  }

  private fun saveToFile(recorded: Boolean) {
    viewModel.save {
      if (recorded) {
        logcatService?.logcat?.stopRecording()
      } else {
        logcatService?.logcat?.getLogsFiltered()
      }
    }
  }

  override fun onStart() {
    super.onStart()
    serviceBinder.bind(requireActivity())
  }

  override fun onStop() {
    super.onStop()
    serviceBinder.unbind(requireActivity())
  }

  private fun removeLastSearchRunnableCallback() {
    lastSearchRunnable?.let {
      handler.removeCallbacks(it)
    }
    lastSearchRunnable = null
  }

  override fun onDestroy() {
    super.onDestroy()
    requireContext().getDefaultSharedPreferences()
      .unregisterOnSharedPreferenceChangeListener(adapter)

    removeLastSearchRunnableCallback()
    searchTask?.cancel()

    recyclerView.removeOnScrollListener(onScrollListener)
    logcatService?.let {
      it.logcat.removeEventListener(this)
      it.logcat.unbind(activity as AppCompatActivity)
    }
    serviceBinder.close()
  }

  override fun onServiceConnected(
    name: ComponentName,
    service: IBinder
  ) {
    Logger.debug(LogcatLiveFragment::class, "onServiceConnected")
    logcatService = service.getService()
    val logcat = logcatService!!.logcat
    logcat.pause() // resume on updateFilters callback

    if (adapter.itemCount == 0) {
      Logger.debug(LogcatLiveFragment::class, "Added all logs")
      addAllLogs(logcat.getLogsFiltered())
    } else if (logcatService!!.restartedLogcat) {
      Logger.debug(LogcatLiveFragment::class, "Logcat restarted")
      logcatService!!.restartedLogcat = false
      adapter.clear()
    }

    scrollRecyclerView()

    logcat.addEventListener(this)
    logcat.bind(activity as AppCompatActivity)

    if (viewModel.stopRecording || arguments?.getBoolean(STOP_RECORDING) == true) {
      arguments?.putBoolean(STOP_RECORDING, false)
      stopRecording()
    }

    viewModel.reloadFilters()
  }

  override fun onReceivedLogs(logs: List<Log>) {
    adapter.addItems(logs)
    updateToolbarSubtitle(adapter.itemCount)
    if (viewModel.autoScroll) {
      linearLayoutManager.scrollToPosition(adapter.itemCount - 1)
    }
  }

  private fun addAllLogs(logs: List<Log>) {
    adapter.addItems(logs)
    updateToolbarSubtitle(adapter.itemCount)
  }

  private fun scrollRecyclerView() {
    if (viewModel.autoScroll) {
      linearLayoutManager.scrollToPosition(adapter.itemCount - 1)
    } else {
      linearLayoutManager.scrollToPositionWithOffset(viewModel.scrollPosition, 0)
    }
  }

  private fun updateToolbarSubtitle(count: Int) {
    if (count > 1) {
      (activity as BaseActivityWithToolbar).toolbar.subtitle = "$count"
    } else {
      (activity as BaseActivityWithToolbar).toolbar.subtitle = null
    }
  }

  private fun resumeLogcat() {
    logcatService?.let {
      if (!it.paused) {
        it.logcat.resume()
      }
    }
  }

  override fun onServiceDisconnected(name: ComponentName) {
    Logger.debug(LogcatLiveFragment::class, "onServiceDisconnected")
    logcatService = null
  }

  private class SearchFilter(private val searchText: String) : Filter {
    override fun apply(log: Log): Boolean {
      return log.tag.containsIgnoreCase(searchText) ||
        log.msg.containsIgnoreCase(searchText)
    }
  }

  private fun runSearchTask(
    logcat: Logcat,
    searchText: String
  ) {
    searchTask?.cancel()
    searchTask = scope.launch {
      logcat.pause()
      logcat.addFilter(SEARCH_FILTER_TAG, SearchFilter(searchText))

      val filteredLogs = withContext(Default) { logcat.getLogsFiltered() }
      adapter.setItems(filteredLogs)
      viewModel.autoScroll = false
      linearLayoutManager.scrollToPositionWithOffset(0, 0)

      resumeLogcat()
    }
  }

  fun useRootToGrantPermission() {
    scope.launch {
      val dialog = AskingForRootAccessDialogFragment()
      dialog.show(parentFragmentManager, AskingForRootAccessDialogFragment.TAG)

      val result = withContext(IO) {
        val cmd = "pm grant ${BuildConfig.APPLICATION_ID} ${Manifest.permission.READ_LOGS}"
        SuCommander(cmd).run()
      }

      dialog.dismissAllowingStateLoss()
      if (result) {
        RestartAppMessageDialogFragment.newInstance().show(
          parentFragmentManager,
          RestartAppMessageDialogFragment.TAG
        )
      } else {
        requireActivity().showToast(getString(R.string.fail))
        ManualMethodToGrantPermissionDialogFragment().show(
          parentFragmentManager,
          ManualMethodToGrantPermissionDialogFragment.TAG
        )
      }
    }
  }
  fun isOnline(): Boolean {
    val connectivityManager =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (connectivityManager != null) {
      val capabilities =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
          } else {
            // if the android version is below M
            @Suppress("DEPRECATION") val networkInfo =
                    connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
          }
      if (capabilities != null) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
          Logger.debug(LogcatLiveFragment::class, "NetworkCapabilities.TRANSPORT_CELLULAR")
          return true
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
          Logger.debug(LogcatLiveFragment::class, "NetworkCapabilities.TRANSPORT_WIFI")
          return true
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
          Logger.debug(LogcatLiveFragment::class, "NetworkCapabilities.TRANSPORT_ETHERNET")
          return true
        }
      }
    }
    return false
  }
  private class LogFilter(filterInfo: FilterInfo) : Filter {
    private val type = filterInfo.type
    private val content = filterInfo.content
    private val priorities: Set<String> = if (type == FilterType.LOG_LEVELS) {
      filterInfo.content.split(",").toSet()
    } else {
      emptySet()
    }

    override fun apply(log: Log): Boolean {
      if (content.isEmpty()) {
        return true
      }

      return when (type) {
        FilterType.LOG_LEVELS -> {
          log.priority in priorities
        }
        FilterType.KEYWORD -> log.msg.containsIgnoreCase(content)
        FilterType.TAG -> log.tag.containsIgnoreCase(content)
        FilterType.PID -> log.pid.containsIgnoreCase(content)
        FilterType.TID -> log.tid.containsIgnoreCase(content)
        else -> false
      }
    }
  }
}
