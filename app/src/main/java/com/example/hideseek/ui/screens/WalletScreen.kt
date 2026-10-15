package com.example.hideseek.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.app
import com.example.hideseek.device.formatRs
import com.example.hideseek.ui.Body
import com.example.hideseek.ui.Chip
import com.example.hideseek.ui.ChipRow
import com.example.hideseek.ui.ErrorText
import com.example.hideseek.ui.HRow
import com.example.hideseek.ui.HsButton
import com.example.hideseek.ui.HsCard
import com.example.hideseek.ui.HsPage
import com.example.hideseek.ui.HsTextField
import com.example.hideseek.ui.Label
import com.example.hideseek.ui.Variant
import com.example.hideseek.ui.theme.Hs
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private val QUICK = listOf(50, 100, 200, 500)
private val TYPE_LABEL = mapOf(
    "DEPOSIT" to "⬇ Added money",
    "WITHDRAWAL" to "⬆ Withdrawal",
    "ENTRY_FEE" to "🎮 Game entry",
    "WINNINGS" to "🏆 Winnings",
    "REFUND" to "↩ Refund",
    "CARRIED" to "… Earlier history",
)

fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

@Composable
fun WalletScreen(onBack: () -> Unit) {
    val profile = LocalContext.current.app.profile
    val wallet by profile.wallet.collectAsState()
    var amount by remember { mutableStateOf("100") }
    var withdraw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(key: String, rs: String) {
        error = null
        try {
            val value = rs.toDoubleOrNull() ?: 0.0
            if (key == "deposit") profile.deposit(value) else profile.withdraw(value)
            if (key == "withdraw") withdraw = ""
        } catch (e: Exception) {
            error = e.message
        }
    }

    HsPage("Test wallet", onBack) {
        HsCard(borderColor = Hs.Primary) {
            Body("Offline test build: this is play money kept on this phone only. Nothing is charged or paid out.", size = 13)
        }
        HsCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Label("Balance")
                Text(formatRs(wallet.balanceRs), color = Hs.Text, fontSize = 40.sp, fontWeight = FontWeight.Black)
                Body(
                    "Added today ${formatRs(wallet.depositedTodayRs)} of ${formatRs(wallet.dailyDepositLimitRs)} daily limit",
                    dim = true, size = 13,
                )
            }
            Box(Modifier.fillMaxWidth().height(6.dp).background(Hs.SurfaceAlt, RoundedCornerShape(3.dp))) {
                val frac = (wallet.depositedTodayRs / wallet.dailyDepositLimitRs).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Hs.Primary, RoundedCornerShape(3.dp)))
            }
        }

        HsCard {
            Label("Add money")
            ChipRow {
                QUICK.filter { it <= wallet.canDepositTodayRs }.forEach { q ->
                    Chip("₹$q", amount.toDoubleOrNull() == q.toDouble(), { amount = q.toString() })
                }
            }
            HsTextField(amount, { amount = it.filter(Char::isDigit) }, "Amount in ₹", keyboardType = KeyboardType.Number, maxLength = 3)
            val amt = amount.toDoubleOrNull() ?: 0.0
            HsButton(
                "Add ${formatRs(amt)}",
                onClick = { run("deposit", amount) },
                enabled = amt > 0 && amt <= wallet.canDepositTodayRs,
            )
            Body(
                "You can add up to ${formatRs(wallet.canDepositTodayRs)} more today (max ₹500 per day).",
                dim = true, size = 12,
            )
        }

        HsCard {
            Label("Remove test money")
            HRow {
                HsTextField(withdraw, { withdraw = it.filter(Char::isDigit) }, "Amount", Modifier.weight(1f), KeyboardType.Number, 5)
                val wd = withdraw.toDoubleOrNull() ?: 0.0
                HsButton(
                    "Withdraw",
                    onClick = { run("withdraw", withdraw) },
                    modifier = Modifier.width(120.dp),
                    variant = Variant.Secondary,
                    enabled = wd > 0 && wd <= wallet.balanceRs,
                    small = true,
                )
            }
        }
        ErrorText(error)

        HsCard {
            Label("History")
            if (wallet.transactions.isEmpty()) Body("No transactions yet", dim = true)
            wallet.transactions.forEach { t ->
                HorizontalDivider(color = Hs.Border)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Body(TYPE_LABEL[t.type] ?: t.type)
                        Text(formatTime(t.at), color = Hs.TextFaint, fontSize = 12.sp)
                    }
                    Text(
                        (if (t.amountRs >= 0) "+" else "−") + formatRs(abs(t.amountRs)),
                        color = if (t.amountRs >= 0) Hs.Green else Hs.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
    }
}
