package com.octo4a.repository

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.widget.Toast
import com.octo4a.R
import com.octo4a.serial.SerialDriverClass
import com.octo4a.serial.UsbSerialDevice
import com.octo4a.service.OctoPrintService
import com.octo4a.utils.preferences.MainPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface UsbSerialDeviceRepository {
    val usbSerialDevices: StateFlow<Map<String, UsbSerialDevice>>
    fun tryToConnectDevice(device: UsbSerialDevice)
    fun disconnectDevice(device: UsbSerialDevice)
    fun destroy()
}

class UsbSerialDeviceRepositoryImpl(
    private val context: Context,
    private val logger: LoggerRepository,
    private val mainPreferences: MainPreferences
) : UsbSerialDeviceRepository, BroadcastReceiver() {

    private val BROADCAST_USB_SERIAL_DEVICE_GOT_ACCESS =
        "com.octo4a.usb_serial_device_access_received"

    init {
        context.registerReceiver(this, IntentFilter().apply {
            addAction(OctoPrintService.EVENT_USB_ATTACHED)
            addAction(OctoPrintService.EVENT_USB_DETACHED)
            addAction(BROADCAST_USB_SERIAL_DEVICE_GOT_ACCESS)
        })
    }

    private val usbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private val _usbSerialDevices =
        MutableStateFlow(usbManager.deviceList.mapValues { (_, v) -> initializeDevice(v) })
    override val usbSerialDevices = _usbSerialDevices.asStateFlow()

    private fun initializeDevice(usbDevice: UsbDevice): UsbSerialDevice {
        val device = UsbSerialDevice(context, logger, mainPreferences, usbDevice)
        if (device.autoConnect) tryToConnectDevice(device)
        return device
    }

    override fun tryToConnectDevice(device: UsbSerialDevice) {
        if (device.connected) return
        if (!usbManager.hasPermission(device.usbDevice)) return requestPermission(device)
        else if (device.driverClass == SerialDriverClass.UNKNOWN) {
            Toast.makeText(
                context, context.getString(R.string.no_driver_selected), Toast.LENGTH_LONG
            ).show()
            return
        } else {
            device.runPtyThread()
            device.connected = true
        }
    }

    override fun disconnectDevice(device: UsbSerialDevice) {
        device.cancelPtyThread()
        device.connected = false
    }

    override fun destroy() {
        usbSerialDevices.value.values.forEach { disconnectDevice(it) }
        context.unregisterReceiver(this)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            OctoPrintService.EVENT_USB_ATTACHED -> {
                val device: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val usbSerialDevice = initializeDevice(device)
                _usbSerialDevices.value =
                    _usbSerialDevices.value.plus(device.deviceName to usbSerialDevice)

            }
            OctoPrintService.EVENT_USB_DETACHED -> {
                val device: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                usbSerialDevices.value[device.deviceName]?.let {
                    disconnectDevice(it)
                    _usbSerialDevices.value = _usbSerialDevices.value.minus(device.deviceName)
                }
            }
            BROADCAST_USB_SERIAL_DEVICE_GOT_ACCESS -> {
                val deviceName = intent.extras?.getString("deviceName")
                val device = usbSerialDevices.value[deviceName]
                if (device is UsbSerialDevice) tryToConnectDevice(device) else TODO("What happened here?")
            }
        }
    }

    private fun requestPermission(device: UsbSerialDevice) = with(device) {
        val intent = Intent(context, this::class.java)
        intent.action = BROADCAST_USB_SERIAL_DEVICE_GOT_ACCESS
        intent.putExtra("deviceName", device.usbDevice.deviceName)
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_ONE_SHOT

        val pendingIntent = PendingIntent.getBroadcast(context, device.hexId, intent, flags)
        usbManager.requestPermission(device.usbDevice, pendingIntent)
        logger.log(device.usbDevice) { "REQUESTED DEVICE ${device.usbDevice}" }
        Toast.makeText(
            context, context.getString(R.string.requesting_usb_permission), Toast.LENGTH_LONG
        ).show()
    }
}