package com.andyl.ignite.data.notification

import com.andyl.ignite.domain.TransferNotifier

class JvmTransferNotifier : TransferNotifier {
    override fun onIdle(deviceName: String) = Unit
    override fun onReceiving(fileName: String, receivedBytes: Long, totalBytes: Long, progress: Float) = Unit
    override fun onSending(fileName: String, progress: Float, totalBytes: Long) = Unit
    override fun onCompleted(fileName: String, isSending: Boolean) = Unit
    override fun onFailed(fileName: String, message: String?) = Unit
}
