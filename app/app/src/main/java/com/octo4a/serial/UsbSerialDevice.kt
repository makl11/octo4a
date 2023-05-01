package com.octo4a.serial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Build
import androidx.annotation.Keep
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.octo4a.repository.LoggerRepository
import com.octo4a.utils.preferences.MainPreferences

class UsbSerialDevice(
    context: Context,
    logger: LoggerRepository,
    private val mainPreferences: MainPreferences,
    val usbDevice: UsbDevice,
) {
    init {
        System.loadLibrary("vsp-pty")
    }

    external fun init()
    external fun writeData(data: ByteArray)
    external fun getBaudrate(data: Int): Int
    external fun runPtyThread()
    external fun cancelPtyThread()

    init {
        this.init()
    }

    val vendorId = usbDevice.vendorId

    val productId = usbDevice.productId

    val id = "${vendorId}:${productId}"
    val hexId = ((vendorId shl 16) and 0xFFFF0000.toInt()) or (productId and 0x0000FFFF)

    var name: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) usbDevice.productName
            ?: usbDevice.deviceName
        else usbDevice.deviceName

    var currentBaudrate: Int = -1

    var connected = false

    var autoConnect
        get() = mainPreferences.autoConnectDevices.contains(id)
        set(value) {
            mainPreferences.autoConnectDevices =
                if (value) mainPreferences.autoConnectDevices.plus(id)
                else mainPreferences.autoConnectDevices.minus(id)
        }

    private val defaultDriver: UsbSerialDriver? =
        UsbSerialProber(getCustomPrinterProber()).probeDevice(usbDevice)

    var driver: UsbSerialDriver? = defaultDriver
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

    var port: UsbSerialPort? = driver?.ports?.first()

    @Keep
    private val usbSerialIOListener: UsbSerialIOListener =
        UsbSerialIOListener(context, logger, this)
}