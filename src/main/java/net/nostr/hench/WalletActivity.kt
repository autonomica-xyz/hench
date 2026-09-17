package net.nostr.hench

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import net.nostr.hench.nwc.NwcServerConnection
import net.nostr.hench.nwc.NwcServerStore
import net.nostr.hench.settings.SharedPreferencesSettingsRepository
import net.nostr.hench.settings.WalletMode
import net.nostr.hench.wallet.BreezSparkSdkClient
import net.nostr.hench.wallet.SparkLightningWallet
import net.nostr.hench.wallet.WalletBalanceCache
import net.nostr.hench.wallet.WalletPaymentKind
import net.nostr.hench.wallet.WalletRequestParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WalletActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: SharedPreferencesSettingsRepository
    private lateinit var walletBalanceCache: WalletBalanceCache
    private var transactions: List<net.nostr.hench.wallet.SparkPaymentSnapshot> = emptyList()
    private val bg = Color.rgb(8, 9, 10)
    private val panel = Color.rgb(25, 26, 27)
    private val border = Color.rgb(36, 40, 44)
    private val primaryText = Color.rgb(247, 248, 248)
    private val muted = Color.rgb(138, 143, 152)
    private val accent = Color.rgb(94, 106, 210)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SharedPreferencesSettingsRepository(this)
        walletBalanceCache = WalletBalanceCache(this)
        render(walletBalanceCache.load()?.let { "${it.sats} sats" } ?: "Loading wallet…")
        refreshBalance()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(balanceText: String) {
        val settings = repository.load()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(56), dp(22), dp(28))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "Wallet"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primaryText)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(actionButton("Done") { finish() })
        root.addView(titleRow)

        root.addView(card(listOf(
            TextView(this).apply { text = balanceText; textSize = 36f; typeface = Typeface.DEFAULT_BOLD; setTextColor(primaryText); gravity = Gravity.CENTER },
            helper("Worker earnings are spendable from this built-in Breez Spark wallet."),
        )), matchTop(dp(18)))

        val receiveAmount = field("Amount sats", "21")
        val receiveMemo = field("Memo", "hench wallet receive")
        val invoiceOut = helper("Create an invoice to receive sats.")
        root.addView(section("Receive", listOf(
            receiveAmount,
            receiveMemo,
            actionButton("Create invoice", primary = true) {
                val sats = receiveAmount.text.toString().toLongOrNull() ?: 0L
                runWallet(invoiceOut) { wallet ->
                    val invoice = wallet.createInvoice(sats * 1_000L, receiveMemo.text.toString()).bolt11
                    copy("Lightning invoice", invoice)
                    invoiceOut.text = invoice
                }
            },
            invoiceOut,
        )))

        val payInput = field("Invoice or lightning address", "", singleLine = false)
        val payAmount = field("Amount sats for lightning address", "21")
        val payStatus = helper("Paste a BOLT11 invoice or lightning address, or scan a QR code.")
        root.addView(section("Send", listOf(
            payInput,
            payAmount,
            horizontal(
                actionButton("Paste") {
                    val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    payInput.setText(clip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty())
                },
                actionButton("Scan QR") { launchQrScanner() },
            ),
            actionButton("Pay", primary = true) {
                val target = runCatching { WalletRequestParser.parse(payInput.text.toString()) }
                    .getOrElse { error -> payStatus.text = error.message ?: "Invalid payment request"; return@actionButton }
                runWallet(payStatus) { wallet ->
                    val result = when (target.kind) {
                        WalletPaymentKind.BOLT11_INVOICE -> wallet.payInvoice(target.value)
                        WalletPaymentKind.LIGHTNING_ADDRESS -> wallet.payLightningAddress(
                            target.value,
                            payAmount.text.toString().toLongOrNull() ?: 0L,
                            "hench wallet payment",
                        )
                    }
                    payStatus.text = "Paid ${result.amountSats} sats. Payment id: ${result.paymentId}"
                    refreshBalance()
                }
            },
            payStatus,
        )))

        root.addView(section("Transactions", buildList {
            if (transactions.isEmpty()) {
                add(helper("No transactions yet."))
            } else {
                transactions.forEach { tx -> add(transactionRow(tx)) }
            }
        }))

        val nwcUriOut = helper("Create an NWC connection string to spend this worker wallet from other apps.")
        root.addView(section("Use from other apps — NWC server", listOf(
            actionButton("Create / copy NWC connection", primary = true) {
                val connection = NwcServerStore.loadOrCreate(this@WalletActivity, repository.load())
                val uri = connection.toUri()
                copy("hench NWC connection", uri)
                nwcUriOut.text = uri
            },
            helper("Uses the same configured Nostr relays as worker communication. Supported: ${NwcServerConnection.supportedMethodsCsv()}. Keep this URI private; it can spend wallet funds."),
            nwcUriOut,
        )))

        if (settings.walletMode != WalletMode.BUILT_IN) {
            root.addView(helper("This wallet UI spends the built-in Breez Spark wallet. Switch Wallet mode to built-in in Settings to use it."), matchTop(dp(18)))
        }
        setContentView(scroll)
    }

    private fun refreshBalance() {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val wallet = wallet()
                    Pair(wallet.balanceSats(), wallet.recentTransactions())
                }
            }
            result.fold(
                onSuccess = { (sats, txs) ->
                    walletBalanceCache.save(sats)
                    transactions = txs
                    render("$sats sats")
                },
                onFailure = {
                    transactions = emptyList()
                    render("Wallet unavailable")
                },
            )
        }
    }

    /** One row per payment: time, direction, amount, status, optional description. */
    private fun transactionRow(tx: net.nostr.hench.wallet.SparkPaymentSnapshot): TextView =
        TextView(this).apply {
            val time = java.time.Instant.ofEpochSecond(tx.timestampSecs)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            val dir = if (tx.isReceive) "receive" else "send"
            val sign = if (tx.isReceive) "+" else "\u2212"
            val status = when (tx.status) {
                net.nostr.hench.wallet.InvoiceStatus.Settled -> "Complete"
                net.nostr.hench.wallet.InvoiceStatus.Pending -> "Pending"
                net.nostr.hench.wallet.InvoiceStatus.Expired -> "Failed"
                net.nostr.hench.wallet.InvoiceStatus.Unknown -> "Unknown"
            }
            val fee = if (!tx.isReceive && tx.feeSats > 0) " (fee ${tx.feeSats})" else ""
            val desc = tx.description?.takeIf { it.isNotBlank() }?.let { " \u00b7 ${it.take(40)}" } ?: ""
            text = "$time  $dir  $sign${tx.amountSats} sats$fee  \u00b7  $status$desc"
            textSize = 13f
            setTextColor(muted)
        }

    private fun runWallet(status: TextView, block: suspend (SparkLightningWallet) -> Unit) {
        status.text = "Working…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block(wallet()) } }
                .onFailure { status.text = it.message ?: "Wallet operation failed" }
        }
    }

    private fun wallet(): SparkLightningWallet {
        val settings = repository.load()
        require(settings.walletMode == WalletMode.BUILT_IN) { "Switch Settings → Wallet to built-in Breez Spark first" }
        val apiKey = requireNotNull(settings.sparkApiKey) { "Breez Spark API key missing" }
        val seed = requireNotNull(settings.sparkSeedHex) { "Spark seed missing" }
        return SparkLightningWallet(BreezSparkSdkClient(applicationContext, apiKey, seed))
    }

    private fun launchQrScanner() {
        runCatching { startActivityForResult(Intent("com.google.zxing.client.android.SCAN"), 4100) }
            .onFailure { Toast.makeText(this, "No QR scanner app installed; paste the invoice instead", Toast.LENGTH_LONG).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4100 && resultCode == RESULT_OK) {
            copy("Scanned payment request", data?.getStringExtra("SCAN_RESULT").orEmpty())
            Toast.makeText(this, "Scanned QR copied. Paste it into Send.", Toast.LENGTH_LONG).show()
        }
    }

    private fun copy(label: String, value: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "Copied $label", Toast.LENGTH_SHORT).show()
    }

    private fun section(title: String, views: List<android.view.View>) = card(listOf(TextView(this).apply {
        text = title; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(primaryText)
    }) + views).also { it.layoutParams = matchTop(dp(18)) }

    private fun card(views: List<android.view.View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = rounded(panel, dp(18), border)
        views.forEachIndexed { index, view -> addView(view, if (index == 0) matchTop(0) else matchTop(dp(10))) }
    }

    private fun horizontal(left: Button, right: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        addView(right, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
    }

    private fun field(hint: String, value: String, singleLine: Boolean = true) = EditText(this).apply {
        this.hint = hint; setText(value); setSingleLine(singleLine); minHeight = dp(if (singleLine) 48 else 84)
        setTextColor(primaryText); setHintTextColor(muted); textSize = 14f; setPadding(dp(12), dp(8), dp(12), dp(8)); background = rounded(Color.rgb(15,16,17), dp(10), border)
    }
    private fun helper(textValue: String) = TextView(this).apply { text = textValue; textSize = 13f; setTextColor(muted) }
    private fun actionButton(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply { text = label; textSize = 13f; setTextColor(primaryText); background = rounded(if (primary) accent else Color.rgb(15,16,17), dp(12), if (primary) null else border); setOnClickListener { onClick() } }
    private fun matchTop(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
    private fun rounded(color: Int, radius: Int, strokeColor: Int?) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = radius.toFloat(); if (strokeColor != null) setStroke(dp(1), strokeColor) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
