package com.bitcoin.merchant.app.screens

import android.content.*
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bitcoin.merchant.app.R
import com.bitcoin.merchant.app.model.Analytics
import com.bitcoin.merchant.app.model.PaymentTarget
import com.bitcoin.merchant.app.screens.dialogs.DialogHelper
import com.bitcoin.merchant.app.screens.dialogs.SnackHelper
import com.bitcoin.merchant.app.screens.features.ToolbarAwareFragment
import com.bitcoin.merchant.app.util.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoindotcom.bchprocessor.bip70.Bip70Manager
import org.bitcoindotcom.bchprocessor.bip70.Bip70PayService
import org.bitcoindotcom.bchprocessor.bip70.model.Bip70Action
import org.bitcoindotcom.bchprocessor.bip70.model.InvoiceRequest
import org.bitcoindotcom.bchprocessor.bip70.model.InvoiceStatus
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.util.*

class PaymentRequestFragment : ToolbarAwareFragment() {
    // Ensure that pressing 'BACK' button stays on the 'Payment REQUEST' screen to NOT lose the active invoice
    // unless we are exiting the screen
    private var backButtonAllowed: Boolean = false
    private lateinit var fabShare: FloatingActionButton
    private lateinit var waitingLayout: LinearLayout
    private lateinit var receivedLayout: LinearLayout
    private lateinit var tvConnectionStatus: ImageView
    private lateinit var tvFiatAmount: TextView
    private lateinit var tvCoinAmount: TextView
    private lateinit var tvExpiryTimer: TextView
    private lateinit var ivReceivingQr: ImageView
    private lateinit var progressLayout: LinearLayout
    private lateinit var ivCancel: ImageView
    private lateinit var ivDone: Button
    private lateinit var bip70Manager: Bip70Manager
    private lateinit var bip70PayService: Bip70PayService
    private var lastProcessedInvoicePaymentId: String? = null
    private var qrCodeUri: String? = null

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Bip70Action.INVOICE_PAYMENT_ACKNOWLEDGED == intent.action) {
                acknowledgePayment(InvoiceStatus.fromJson(intent.getStringExtra(Bip70Action.PARAM_INVOICE_STATUS)))
            }
            if (Bip70Action.INVOICE_PAYMENT_EXPIRED == intent.action) {
                expirePayment(InvoiceStatus.fromJson(intent.getStringExtra(Bip70Action.PARAM_INVOICE_STATUS)))
            }
            if (Bip70Action.UPDATE_CONNECTION_STATUS == intent.action) {
                updateConnectionStatus(intent.getBooleanExtra(Bip70Action.PARAM_CONNECTION_STATUS_ENABLED, false))
            }
            if (Bip70Action.NETWORK_RECONNECT == intent.action) {
                bip70Manager.reconnectIfNecessary()
            }
        }
    }

    private fun expirePayment(invoiceStatus: InvoiceStatus) {
        if (markInvoiceAsProcessed(invoiceStatus)) {
            return
        }
        exitScreen()
    }

    private var lastConnectionStatusEnabled: Boolean = false
    private fun updateConnectionStatus(enabled: Boolean) {
        if (lastConnectionStatusEnabled != enabled) {
            lastConnectionStatusEnabled = enabled
            Log.d(TAG, "Socket " + if (enabled) "connected" else "disconnected")
        }
        tvConnectionStatus.setImageResource(if (enabled) R.drawable.connected else R.drawable.disconnected)
    }

    private fun acknowledgePayment(i: InvoiceStatus) {
        if (markInvoiceAsProcessed(i)) {
            return
        }
        Analytics.invoice_paid.send()
        Log.i(TAG, "record new Tx:$i")
        val fiatFormatted = AmountUtil(activity).formatFiat(i.fiatTotal)
        app.paymentProcessor.recordInDatabase(i, fiatFormatted)
        showCheckMark()
        soundAlert()
    }

    /**
     * @return true if it was already processed, false otherwise
     */
    private fun markInvoiceAsProcessed(invoiceStatus: InvoiceStatus): Boolean {
        Settings.deleteActiveInvoice(activity)
        // Check that it has not yet been processed to avoid redundant processing
        if (lastProcessedInvoicePaymentId == invoiceStatus.paymentId) {
            Log.i(TAG, "Already processed invoice:$invoiceStatus")
            return true
        }
        lastProcessedInvoicePaymentId = invoiceStatus.paymentId
        return false
    }

    private fun soundAlert() {
        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null && audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            val mp = MediaPlayer.create(activity, R.raw.alert)
            mp.setOnCompletionListener { player: MediaPlayer ->
                player.reset()
                player.release()
            }
            mp.start()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        val v = inflater.inflate(R.layout.fragment_request_payment, container, false)
        initViews(v)
        setInvoiceReadyToShare(false)
        setToolbarVisible(false)
        registerReceiver()
        bip70PayService = Bip70PayService.create(resources.getString(R.string.bip70_bitcoin_com_host))
        bip70Manager = Bip70Manager(app)
        val args = arguments
        val amountFiat = args?.getDouble(PaymentInputFragment.AMOUNT_PAYABLE_FIAT, 0.0) ?: 0.0
        if (amountFiat > 0.0) {
            createNewInvoice(amountFiat)
        } else {
            resumeExistingInvoice()
        }
        return v
    }

    private fun createNewInvoice(amountFiat: Double) {
        // InvoiceRequest is the slowest when deriving a PubKey from an xPub
        // but even in that situation, it is fast enough to be executed
        // in the main thread instead of withContext(Dispatchers.IO)
        val invoiceRequest = createInvoiceRequest(amountFiat, Settings.getCountryCurrencyLocale(activity).currency)
        if (invoiceRequest == null) {
            unableToDisplayInvoice()
        } else {
            // do NOT delete active invoice too early
            // because PaymentInput Fragment must always be instantiated below the PaymentRequest Fragment
            // when resuming from a crash on the PaymentRequest
            Settings.deleteActiveInvoice(activity)
            tvFiatAmount.text = AmountUtil(activity).formatFiat(amountFiat)
            tvFiatAmount.visibility = View.VISIBLE
            createNewInvoice(invoiceRequest)
        }
    }

    private fun createNewInvoice(invoiceRequest: InvoiceRequest) {
        viewLifecycleOwner.lifecycleScope.launch {
            setWorkInProgress(true)
            downloadInvoice(invoiceRequest, { createNewInvoice(invoiceRequest)})?.let { invoice ->
                generateQrCode(invoice)?.also {
                    showQrCodeAndAmountFields(invoice, it)
                    // only save invoice after updating the UI to improve user experience
                    Settings.setActiveInvoice(activity, invoice)
                    connectToSocket(invoice)
                }
            }
            setWorkInProgress(false)
        }
    }

    private fun resumeExistingInvoice() {
        val invoice = Settings.getActiveInvoice(activity)
        if (invoice == null) {
            unableToDisplayInvoice()
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                setWorkInProgress(true)
                generateQrCode(invoice)?.also {
                    showQrCodeAndAmountFields(invoice, it)
                    connectToSocket(invoice)
                }
                setWorkInProgress(false)
            }
        }
    }

    private fun unableToDisplayInvoice() {
        DialogHelper.show(activity, getString(R.string.error), getString(R.string.unable_to_generate_address)) {
            exitScreen()
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter()
        filter.addAction(Bip70Action.INVOICE_PAYMENT_ACKNOWLEDGED)
        filter.addAction(Bip70Action.INVOICE_PAYMENT_EXPIRED)
        filter.addAction(Bip70Action.UPDATE_CONNECTION_STATUS)
        filter.addAction(Bip70Action.NETWORK_RECONNECT)
        LocalBroadcastManager.getInstance(activity).registerReceiver(receiver, filter)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::bip70Manager.isInitialized) {
            bip70Manager.stopSocket()
        }
        LocalBroadcastManager.getInstance(activity).unregisterReceiver(receiver)
    }

    private fun initViews(v: View) {
        tvConnectionStatus = v.findViewById(R.id.tv_connection_status)
        tvFiatAmount = v.findViewById(R.id.tv_fiat_amount)
        tvCoinAmount = v.findViewById(R.id.tv_btc_amount)
        tvExpiryTimer = v.findViewById(R.id.bip70_timer_tv)
        ivReceivingQr = v.findViewById(R.id.qr)
        progressLayout = v.findViewById(R.id.progressLayout)
        waitingLayout = v.findViewById(R.id.layout_waiting)
        receivedLayout = v.findViewById(R.id.layout_complete)
        ivCancel = v.findViewById(R.id.iv_cancel)
        ivDone = v.findViewById(R.id.iv_done)
        fabShare = v.findViewById(R.id.fab_share)
        ivCancel.setOnClickListener { deleteActiveInvoiceAndExitScreen() }
        ivReceivingQr.setOnClickListener { copyQrCodeToClipboard() }
        fabShare.setOnClickListener { qrCodeUri?.let { startShareIntent(it) } }
        tvFiatAmount.visibility = View.INVISIBLE // hide invalid value
        waitingLayout.visibility = View.VISIBLE
        receivedLayout.visibility = View.GONE
        tvCoinAmount.visibility = if (BCH_AMOUNT_DISPLAYED) View.INVISIBLE else View.GONE
        setWorkInProgress(true)
    }

    private fun setWorkInProgress(enabled: Boolean) {
        progressLayout.visibility = if (enabled) View.VISIBLE else View.GONE
        ivReceivingQr.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun deleteActiveInvoiceAndExitScreen() {
        Analytics.invoice_cancelled.send()
        Settings.deleteActiveInvoice(activity)
        exitScreen()
    }

    private fun exitScreen() {
        backButtonAllowed = true
        activity.onBackPressed()
        backButtonAllowed = false
    }

    private fun copyQrCodeToClipboard() {
        try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(qrCodeUri, qrCodeUri)
            clipboard.setPrimaryClip(clip)
            val emojiClipboard = String(Character.toChars(0x1F4CB))
            SnackHelper.show(activity, emojiClipboard + " ${qrCodeUri}")
            Log.i(TAG, "Copied to clipboard: $qrCodeUri")
        } catch (e: Exception) {
            Analytics.error_copy_to_clipboard.sendError(e)
            Log.i(TAG, "Failed to copy to clipboard: $qrCodeUri")
        }
    }

    private fun createInvoiceRequest(amountFiat: Double, currency: String): InvoiceRequest? {
        val paymentTarget = Settings.getPaymentTarget(activity)
        val i = InvoiceRequest("" + amountFiat, currency)
        when (paymentTarget.type) {
            PaymentTarget.Type.INVALID -> return null
            PaymentTarget.Type.API_KEY -> i.apiKey = paymentTarget.target
            PaymentTarget.Type.ADDRESS -> i.address = paymentTarget.legacyAddress
            PaymentTarget.Type.XPUB -> try {
                // known limitation: we only check for used addresses when setting the xPub
                // as a consequence if the same xPubKey is used on multiple cashiers/terminals
                // then addresses can be reused. Address reuse is not an issue
                // because the BIP-70 server is the one only broadcasting the TX to that address
                // and thus it is aware of which invoice is being paid without possible confusion
                i.address = app.wallet.getAddressFromXPubAndMoveToNext()
                Log.i(TAG, "BCH-address(xPub) to receive: " + i.address)
            } catch (e: Exception) {
                Analytics.error_generate_address_from_xpub.sendError(e)
                Log.e(TAG, "", e)
                return null
            }
        }
        return i
    }

    private suspend fun downloadInvoice(request: InvoiceRequest, retry: () -> Unit): InvoiceStatus? {
        return withContext(Dispatchers.IO) {
            try {
                val startMs = System.currentTimeMillis();
                val response: Response<InvoiceStatus?> = bip70PayService.createInvoice(request).execute()
                val invoice = response.body() ?: throw Exception("HTTP status:" + response.code() + " message:" + response.message())
                Analytics.invoice_created.sendDuration(System.currentTimeMillis() - startMs)
                invoice
            } catch (e: Exception) {
                Analytics.error_download_invoice.sendError(e)
                DialogHelper.showCancelOrRetry(activity, activity.getString(R.string.error),
                        activity.getString(R.string.error_check_your_network_connection),
                        { exitScreen() }, { retry() })
                null
            }
        }
    }

    private suspend fun connectToSocket(invoice: InvoiceStatus) {
        return withContext(Dispatchers.IO) {
            // analytics already sent inside websockets
            bip70Manager.startSocket(invoice.paymentId)
        }
    }

    private suspend fun generateQrCode(invoice: InvoiceStatus): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                qrCodeUri = invoice.walletUri
                QrCodeUtil.getBitmap(invoice.walletUri, activity.resources.getInteger(R.integer.qr_code_width))
            } catch (e: Exception) {
                // analytics already sent inside qr generation
                DialogHelper.show(activity, activity.getString(R.string.error), e.message) { exitScreen() }
                null
            }
        }
    }

    private fun showQrCodeAndAmountFields(i: InvoiceStatus, bitmap: Bitmap) {
        val f = AmountUtil(activity)
        tvFiatAmount.text = f.formatFiat(i.fiatTotal)
        tvFiatAmount.visibility = View.VISIBLE
        if (BCH_AMOUNT_DISPLAYED) {
            tvCoinAmount.text = MonetaryUtil.instance.getDisplayAmountWithFormatting(i.totalAmountInSatoshi) + " BCH"
            tvCoinAmount.visibility = View.VISIBLE
        }
        ivReceivingQr.setImageBitmap(bitmap)
        setInvoiceReadyToShare(true)
        initiateCountdown(i)
    }

    private fun getTimeLimit(invoiceStatus: InvoiceStatus): Long {
        // Do NOT use invoiceStatus.getTime() because it won't reflect the current time
        // when a persisted invoice is restored
        val now = System.currentTimeMillis()
        val nowInUtc = now - TimeZone.getDefault().getOffset(now)
        val expInUtc = invoiceStatus.expires.time
        return expInUtc - nowInUtc
    }

    private fun initiateCountdown(invoiceStatus: InvoiceStatus) {
        val timeLimit = getTimeLimit(invoiceStatus)
        object : CountDownTimer(timeLimit, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (isAdded) {
                    val secondsLeft = millisUntilFinished / 1000L
                    val locale = Locale.getDefault()
                    tvExpiryTimer.text = String.format(locale, "%02d:%02d", secondsLeft / 60, secondsLeft % 60)
                    updateConnectionStatus(bip70Manager.socketHandler?.isConnected ?: false)
                }
            }

            override fun onFinish() {
                if (isAdded) {
                    Settings.deleteActiveInvoice(activity)
                    exitScreen()
                }
            }
        }.start()
    }

    private fun showCheckMark() {
        tvConnectionStatus.visibility = View.GONE // hide it white top bar on green background
        waitingLayout.visibility = View.GONE
        receivedLayout.visibility = View.VISIBLE
        AppUtil.setStatusBarColor(activity, R.color.bitcoindotcom_green)
        Settings.deleteActiveInvoice(activity)
        ivDone.setOnClickListener {
            AppUtil.setStatusBarColor(activity, R.color.gray)
            exitScreen()
        }
    }

    private fun setInvoiceReadyToShare(ready: Boolean) {
        if (ready) {
            fabShare.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(app, R.color.bitcoindotcom_green))
        } else {
            qrCodeUri = null
            fabShare.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(app, R.color.gray))
        }
    }

    private fun startShareIntent(paymentUrl: String) {
        try {
            val urlWithoutPrefix = paymentUrl.replace(getString(R.string.uri_bitcoincash_bip70), "")
            val bitmap = ivReceivingQr.drawable.toBitmap(220, 220)
            val file = File(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "invoice.png")
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 80, it)
            }
            val bitmapUri = FileProvider.getUriForFile(app, activity.packageName + ".provider", file)
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, resources.getString(R.string.share_invoice_msg, urlWithoutPrefix))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                putExtra(Intent.EXTRA_STREAM, bitmapUri)
                type = "image/*"
            }
            val shareIntent = Intent.createChooser(sendIntent, null)
            shareIntent?.let { startActivity(it) }
        } catch (e: Exception) {
            Log.e(TAG, "", e)
        }
    }

    override val isBackAllowed: Boolean
        get() {
            return backButtonAllowed
        }

    companion object {
        private const val TAG = "BCR-PaymentRequest"

        // BCH amount is hidden as deemed non-necessary because it is shown on the customer wallet
        private const val BCH_AMOUNT_DISPLAYED = false
    }
}