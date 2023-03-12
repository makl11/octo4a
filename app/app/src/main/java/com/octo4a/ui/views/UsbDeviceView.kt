package com.octo4a.ui.views

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.octo4a.R
import com.octo4a.repository.UsbSerialDeviceRepository
import com.octo4a.serial.SerialDriverClass
import com.octo4a.serial.UsbSerialDevice
import kotlinx.android.synthetic.main.select_driver_bottom_sheet.*
import kotlinx.android.synthetic.main.view_usb_devices_item.view.*

@SuppressLint("ViewConstructor")
class UsbDeviceView @JvmOverloads constructor(
    ctx: Context,
    private val usbSerialDeviceRepository: UsbSerialDeviceRepository,

    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0,

    ) : ConstraintLayout(ctx, attributeSet, defStyleAttr) {
    private val adapter = ArrayAdapter(
        context,
        R.layout.support_simple_spinner_dropdown_item,
        SerialDriverClass.values().map(SerialDriverClass::driverName)
    )

    init {
        val inflater = ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        inflater.inflate(R.layout.view_usb_devices_item, this)
        layoutTransition = LayoutTransition()
    }


    private fun drawDriverInfo(usbDevice: UsbSerialDevice) {
        serialDriverText.text =
            "${usbDevice.driverClass.driverName} ${context.getString(R.string.serial_driver)}"
        if (usbDevice.driverClass == SerialDriverClass.UNKNOWN) {
            serialDriverText.text =
                serialDriverText.text.toString() + context.getString(R.string.tap_to_select)
        }
    }

    fun setUsbDevice(usbDevice: UsbSerialDevice) {
        vidPidText.text =
            "VID " + usbDevice.vendorId.toString(16) + " / PID " + usbDevice.productId.toString(16)
        titleText.text = usbDevice.name
        drawDriverInfo(usbDevice)

        setOnClickListener {
            openBottomSheet(usbDevice)
        }
    }

    private fun openBottomSheet(usbDevice: UsbSerialDevice) {
        val bottomSheetDialog = BottomSheetDialog(context)
        bottomSheetDialog.setContentView(R.layout.select_driver_bottom_sheet)

        bottomSheetDialog.apply {
            deviceSheetHeader.text = usbDevice.name

            // Driver   SerialDriverClass & usbDevice.driverClass
            driverDropdown.adapter = adapter
            val currentDriverPos = adapter.getPosition(usbDevice.driverClass.driverName)
            driverDropdown.setSelection(currentDriverPos, true)
            driverDropdown.isEnabled = !usbDevice.connected
            driverDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    adapter.getItem(position)?.let {
                        val driverClass = SerialDriverClass.fromDriverName(it)
                        usbDevice.driverClass = driverClass
                        drawDriverInfo(usbDevice)
                        autoConnectSwitch.isChecked =
                            autoConnectSwitch.isChecked && usbDevice.driverClass != SerialDriverClass.UNKNOWN
                        autoConnectSwitch.isEnabled =
                            autoConnectSwitch.isChecked || usbDevice.driverClass != SerialDriverClass.UNKNOWN
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    /* Because a UsbSerialDevices driver class defaults to UNKNOWN, the
                       dropdown should never be in a state where nothing is selected    */
                    assert("Unreachable".isBlank())
                }
            }

            autoConnectSwitch.isChecked = usbDevice.autoConnect
            autoConnectSwitch.isEnabled =
                autoConnectSwitch.isChecked || usbDevice.driverClass != SerialDriverClass.UNKNOWN
            autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
                usbDevice.autoConnect = isChecked
            }

            // Connect / Disconnect button
            if (usbDevice.connected) {
                connectButton.setBackgroundColor(
                    ContextCompat.getColor(
                        context, android.R.color.holo_red_light
                    )
                )
                connectButton.text = "Disconnect"
            } else {
                connectButton.setBackgroundColor(ContextCompat.getColor(context, R.color.iconGreen))
                connectButton.text = "Connect"
            }


            connectButton.setOnClickListener {
                if (usbDevice.connected) {
                    usbSerialDeviceRepository.disconnectDevice(usbDevice)
                } else {
                    usbSerialDeviceRepository.tryToConnectDevice(usbDevice)
                }
                dismiss()
            }

            closeDialog.setOnClickListener {
                dismiss()
            }
        }
        bottomSheetDialog.show()
    }
}