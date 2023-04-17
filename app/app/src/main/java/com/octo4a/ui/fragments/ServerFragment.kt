package com.octo4a.ui.fragments

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.octo4a.R
import com.octo4a.camera.CameraService
import com.octo4a.repository.GithubRelease
import com.octo4a.repository.LoggerRepository
import com.octo4a.repository.ServerStatus
import com.octo4a.repository.UsbSerialDeviceRepository
import com.octo4a.ui.InitialActivity
import com.octo4a.ui.WebinterfaceActivity
import com.octo4a.ui.views.UsbDeviceView
import com.octo4a.utils.preferences.MainPreferences
import com.octo4a.viewmodel.IPAddressType
import com.octo4a.viewmodel.NetworkStatusViewModel
import com.octo4a.viewmodel.StatusViewModel
import kotlinx.android.synthetic.main.fragment_server.*
import kotlinx.android.synthetic.main.view_status_card.view.*
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel


class ServerFragment : Fragment() {
    private val statusViewModel: StatusViewModel by sharedViewModel()
    private val networkStatusViewModel: NetworkStatusViewModel by sharedViewModel()
    private lateinit var cameraService: CameraService
    private var boundToCameraService = false
    private val usbSerialDeviceRepository: UsbSerialDeviceRepository by inject()
    private val mainPreferences: MainPreferences by inject()
    private val logger: LoggerRepository by inject()

    private val cameraServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            boundToCameraService = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            boundToCameraService = false
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_server, container, false)
    }

    override fun onStart() {
        super.onStart()
        val activity = requireActivity()
        Intent(activity, CameraService::class.java).also { intent ->
            activity.bindService(intent, cameraServiceConnection, Context.BIND_AUTO_CREATE)
        }
        networkStatusViewModel.scanIPAddresses()
    }

    override fun onStop() {
        super.onStop()
        requireActivity().unbindService(cameraServiceConnection)
        boundToCameraService = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusViewModel.updateAvailable.observe(viewLifecycleOwner) {
            logger.log(this) { "Update available" }
            showUpdateDialog(it)
        }

        statusViewModel.usbSerialDevices.observe(viewLifecycleOwner) { devices ->
            usbDevicesList.removeAllViews()
            devices.values.forEach {
                val usbDeviceView = UsbDeviceView(requireContext(), usbSerialDeviceRepository)
                usbDevicesList.addView(usbDeviceView)
                usbDeviceView.setUsbDevice(it)
            }
        }

        camServerStatus.setOnClickListener {
            if (statusViewModel.cameraStatus.value == true) {
                showPreviewDialog()
            }
        }

        serverStatus.setOnClickListener {
            if (statusViewModel.serverStatus.value == ServerStatus.Running) {
                openWebInterface()
            }
        }

        statusViewModel.cameraStatus.observe(viewLifecycleOwner) {
            if (it) {
                camServerStatus.title = getString(R.string.camserver_running)
                camServerStatus.subtitle = getString(R.string.camserver_status_tap)
            } else {
                camServerStatus.title = getString(R.string.camserver_disabled)
                camServerStatus.subtitle = getString(R.string.camserver_enable)
            }
        }

        statusViewModel.serverStatus.observe(viewLifecycleOwner) {
            when (it) {
                ServerStatus.Running -> {
                    serverStatus.setDrawableAndColor(R.drawable.ic_stop_24px, android.R.color.holo_red_light)
                    serverStatus.title = resources.getString(R.string.status_running)
                    serverStatus.onActionClicked = {
                        statusViewModel.stopServer()
                    }
//                    serverStatus.subtitle = statusViewModel.getServerAddress()
                    serverStatus.showIpAddresses = true
                }

                ServerStatus.BootingUp -> {
                    serverStatus.title = resources.getString(R.string.status_starting)
                    serverStatus.subtitle = resources.getString(R.string.status_starting_subtitle)
                    serverStatus.showIpAddresses = false
                }

                ServerStatus.ShuttingDown -> {
                    serverStatus.title = resources.getString(R.string.status_shutting_down)
                    serverStatus.subtitle = resources.getString(R.string.status_shutting_down_subtitle)
                    serverStatus.showIpAddresses = false
                }

                ServerStatus.Stopped -> {
                    serverStatus.setDrawableAndColor(R.drawable.ic_play_arrow_24px, R.color.iconGreen)
                    serverStatus.title = resources.getString(R.string.status_stopped)
                    serverStatus.subtitle = resources.getString(R.string.status_stopped_start)
                    serverStatus.onActionClicked = {
                        statusViewModel.startServer()
                    }
                    serverStatus.showIpAddresses = false
                }

                ServerStatus.Corrupted -> {
                    serverStatus.setDrawableAndColor(R.drawable.ic_baseline_heart_broken_24, android.R.color.holo_red_light)
                    serverStatus.title = getString(R.string.installation_corrupt)
                    serverStatus.subtitle = getString(R.string.tap_to_reinstall)
                    serverStatus.onActionClicked = {
                        clearDataAndRestartApp()
                    }
                    serverStatus.showIpAddresses = false
                }
                else -> {}
            }
            serverStatus.actionProgressbar.isGone = !it.progress
            serverStatus.actionButton.isGone = it.progress
            if (it == ServerStatus.Running) {
                serverStatus.setOnClickListener {
                    openWebInterface()
                }
            } else {
                serverStatus.setOnClickListener(null)
            }
        }

        networkStatusViewModel.ipAddresses.observe(viewLifecycleOwner) {
            Log.d("ServerFragment", "IP addresses: ${it.joinToString(", ") { it.address }} }}")
            serverStatus.ipAddresses = it.map { it.copy(port = "5000") }.toTypedArray()
            val hasNoLocalNetwork = it.isEmpty() || it.all { it.type == IPAddressType.Cellular }
            if(hasNoLocalNetwork) {
                serverStatus.warning = getString(R.string.no_local_network)
            } else {
                serverStatus.warning = ""
            }
        }

        // Fetch autoupdater
        statusViewModel.checkUpdateAvailable()
    }



    private fun showUpdateDialog(update: GithubRelease) {
        if (update.tagName != mainPreferences.updateDismissed) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.update_available))
                .setMessage(getString(R.string.update_available_message).format(update.tagName))
                .setPositiveButton(getString(R.string.action_download)) { dialog, _ ->
                    val i = Intent(Intent.ACTION_VIEW)
                    i.data = Uri.parse(update.htmlUrl)
                    startActivity(i)
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.action_later)) { dialog, _ ->
                    dialog.dismiss()
                    mainPreferences.updateDismissed = update.tagName
                }
                .show()
        }
    }

    private fun openWebInterface() {
        startActivity(Intent(context, WebinterfaceActivity::class.java))
    }

    private fun showPreviewDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val dialog = MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.camera_preview)
                .setView(R.layout.dialog_camera_preview)
                .setPositiveButton(R.string.action_ok) {dialog, _ -> dialog.dismiss() }
                .show()

            dialog.findViewById<PreviewView>(R.id.previewView)?.apply {


                if (boundToCameraService) {
                    cameraService.getPreview().setSurfaceProvider(surfaceProvider)
                }
            }
        } else {
            Toast.makeText(context, getString(R.string.api_too_low), Toast.LENGTH_LONG).show()
        }
    }

    private fun clearDataAndRestartApp() {
        val builder = AlertDialog.Builder(context)
        builder.apply {
            setTitle(getString(R.string.reinstall_dialog_title))
            setMessage(R.string.app_will_restart_to_clear)
            setNegativeButton(getString(R.string.reinstall_dialog_dismiss)) { dialog, id ->

            }
            setPositiveButton(getString(R.string.reinstall_dialog_continue)) { dialog, id ->
                try {
                    val activityManager = requireActivity().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    // clearing app data
                    if (Build.VERSION_CODES.KITKAT <= Build.VERSION.SDK_INT) {
                        activityManager.clearApplicationUserData()
                    } else {
                        val packageName = requireActivity().applicationContext.packageName
                        val runtime = Runtime.getRuntime();
                        runtime.exec("pm clear $packageName");
                    }

                    // restart the app
                    val intent = Intent(context, InitialActivity::class.java)
                    intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
                    requireActivity().startActivity(intent)

                    Runtime.getRuntime().exit(0)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        val dialog = builder.create()
        dialog.show()

    }
}