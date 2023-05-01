package com.octo4a.serial

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.annotation.Keep
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.octo4a.repository.LoggerRepository
import java.util.concurrent.Executors

class UsbSerialIOListener(
    private val context: Context,
    private val logger: LoggerRepository,
    private val device: UsbSerialDevice,
) : SerialInputOutputManager.Listener {
    private val usbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private lateinit var serialIOManager: SerialInputOutputManager

    @Keep
    fun onDataReceived(data: SerialData?) {
        assert(device.driver != null)
        try {
            data?.apply {
                val newConnectionRequired =
                    ((isStartPacket || device.currentBaudrate != baudrate) && device.driver != null)
                if (newConnectionRequired || device.port?.isOpen != true) {
                    if (device.port?.isOpen == true) device.port?.close()
                    val connection = usbManager.openDevice(device.driver!!.device)
                    device.port = device.driver!!.ports.first()

                    device.port?.open(connection)
                    // @TODO get it from flags
                    device.port?.setParameters(
                        device.getBaudrate(baudrate),
                        UsbSerialPort.DATABITS_8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                    )
                    device.currentBaudrate = baudrate

                    if (newConnectionRequired) {
                        device.port?.dtr = true
                        device.port?.rts = true
                    }

                    serialIOManager =
                        SerialInputOutputManager(device.port, this@UsbSerialIOListener)
                    Executors.newSingleThreadExecutor().submit(serialIOManager)
                }

                if (data.data.size > 1) {
                    try {
                        device.port?.write(data.serialData, 2_000 /*ms*/)
                    } catch (e: Exception) {
                        device.port?.close()
                    }
                }
            }
        } catch (e: Exception) {
            logger.log(this) { "Exception during write ${e.message}" }
        }
    }

    override fun onNewData(data: ByteArray) {
        device.writeData(data)
    }

    override fun onRunError(e: Exception?) { /* ¯\_(ツ)_/¯ */
        TODO("What happened here?")
    }
}