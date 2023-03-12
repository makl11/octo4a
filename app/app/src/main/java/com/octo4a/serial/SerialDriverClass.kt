package com.octo4a.serial

import android.hardware.usb.UsbDevice
import com.hoho.android.usbserial.driver.*
import java.util.*
import kotlin.reflect.KFunction

enum class SerialDriverClass(val driverName: String) {
    PROLIFIC("Prolific") {
        override fun toDriver() = ProlificSerialDriver::class.constructors.first()
    },
    CDC("CDC") {
        override fun toDriver() = CdcAcmSerialDriver::class.constructors.first()
    },
    FTDI("FTDI") {
        override fun toDriver() = FtdiSerialDriver::class.constructors.first()
    },
    CH341("CH341") {
        override fun toDriver() = Ch34xSerialDriver::class.constructors.first()
    },
    CP21XX("CP21xx") {
        override fun toDriver() = Cp21xxSerialDriver::class.constructors.first()
    },
    UNKNOWN("Unknown") {
        override fun toDriver() = null
    };

    /**
     * To use the driver returned by this method, you have to
     * instantiate it by calling [KFunction.call] and passing a [UsbDevice]
     * @return the constructor of a [UsbSerialDriver] or `null`
     */
    abstract fun toDriver(): KFunction<UsbSerialDriver>?

    companion object {
        fun fromDriver(driver: UsbSerialDriver?): SerialDriverClass = when (driver) {
            is ProlificSerialDriver -> PROLIFIC
            is CdcAcmSerialDriver -> CDC
            is FtdiSerialDriver -> FTDI
            is Ch34xSerialDriver -> CH341
            is Cp21xxSerialDriver -> CP21XX
            else -> UNKNOWN
        }

        fun fromDriverName(driverName: String) = try {
            valueOf(driverName.toUpperCase(Locale.ROOT))
        } catch (e: java.lang.IllegalArgumentException) {
            UNKNOWN
        }
    }
}