package com.octo4a.serial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.annotation.Keep
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.octo4a.repository.LoggerRepository
import com.octo4a.utils.preferences.MainPreferences
import java.util.concurrent.Executors


class UsbSerialDevice(
    private val context: Context,
    private val logger: LoggerRepository,
    private val mainPreferences: MainPreferences,
    val usbDevice: UsbDevice,
) : SerialInputOutputManager.Listener {
    init {
        System.loadLibrary("vsp-pty")
    }

    private external fun writeData(data: ByteArray)
    private external fun getBaudrate(data: Int): Int
    external fun runPtyThread()
    external fun cancelPtyThread()

    val vendorId = usbDevice.vendorId

    val productId = usbDevice.productId

    val id = "${vendorId}:${productId}"
    val hexId = ((vendorId shl 16) and 0xFFFF0000.toInt()) or (productId and 0x0000FFFF)

    var name: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) usbDevice.productName
            ?: usbDevice.deviceName
        else usbDevice.deviceName

    private var currentBaudrate: Int = -1

    var connected = false

    var autoConnect
        get() = mainPreferences.autoConnectDevices.contains(id)
        set(value) {
            mainPreferences.autoConnectDevices =
                if (value) mainPreferences.autoConnectDevices.plus(id)
                else mainPreferences.autoConnectDevices.minus(id)
        }

    private lateinit var serialInputManager: SerialInputOutputManager

    private val usbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private val defaultDriver: UsbSerialDriver? =
        UsbSerialProber(getCustomPrinterProber()).probeDevice(usbDevice)

    private var driver: UsbSerialDriver? = defaultDriver
        set(value) {
            field = value
            if (value is UsbSerialDriver) port = value.ports.first()
        }

    var driverClass: SerialDriverClass =
        if (mainPreferences.customDeviceDriverClasses.containsKey(id)) {
            val tmpDriverClass =
                SerialDriverClass.fromDriverName(mainPreferences.customDeviceDriverClasses[id]!!)
            driver = tmpDriverClass.toDriver()?.call(usbDevice)
            tmpDriverClass
        } else {
            SerialDriverClass.fromDriver(defaultDriver)
        }
        set(value) {
            if (connected) throw java.lang.RuntimeException("You may not change a devices driver/driver class while it is connected")
            val previous = field
            field = value
            driver = value.toDriver()?.call(usbDevice)
            mainPreferences.customDeviceDriverClasses =
                if (value != SerialDriverClass.UNKNOWN && value != previous) {
                    mainPreferences.customDeviceDriverClasses.plus(
                        id to value.driverName
                    )
                } else {
                    mainPreferences.customDeviceDriverClasses.minus(id)
                }
        }

    private var port: UsbSerialPort? = driver?.ports?.first()

    val hasPermission get() = usbManager.hasPermission(usbDevice)

    @Keep
    fun onDataReceived(data: SerialData?) {
        try {
            data?.apply {
                val newConnectionRequired =
                    ((isStartPacket || currentBaudrate != baudrate) && driver != null)
                if (newConnectionRequired || port?.isOpen != true) {
                    if (port?.isOpen == true) port?.close()
                    val connection = usbManager.openDevice(driver!!.device)
                    port = driver!!.ports.first()

                    port?.open(connection)
                    // @TODO get it from flags
                    port?.setParameters(
                        getBaudrate(baudrate),
                        UsbSerialPort.DATABITS_8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                    )
                    currentBaudrate = baudrate

                    if (newConnectionRequired) {
                        port?.dtr = true
                        port?.rts = true
                    }

                    serialInputManager = SerialInputOutputManager(port, this@UsbSerialDevice)
                    Executors.newSingleThreadExecutor().submit(serialInputManager)
                }

                if (data.data.size > 1) {
                    try {
                        port?.write(data.serialData, 2_000 /*ms*/)
                    } catch (e: Exception) {
                        port?.close()
                    }
                }
            }
        } catch (e: Exception) {
            logger.log(this) { "Exception during write ${e.message}" }
        }
    }

    override fun onNewData(data: ByteArray) {
        writeData(data)
    }

    override fun onRunError(e: Exception?) { /* ¯\_(ツ)_/¯ */
        TODO("What happened here?")
    }
}