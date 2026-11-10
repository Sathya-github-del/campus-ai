package dev.quatrioai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.quatrioai.app.core.ErrorText
import dev.quatrioai.app.core.Money
import dev.quatrioai.app.core.TierPrice
import dev.quatrioai.app.core.TopUpAmount
import dev.quatrioai.app.data.Backend
import dev.quatrioai.app.payments.TopUpFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WalletUiState(
    val balance: Double? = null,
    val prices: List<TierPrice> = emptyList(),
    /** Selected preset chip; ignored while [custom] has text. */
    val chip: Int = TopUpAmount.MIN,
    val custom: String = "",
    val busy: Boolean = false,
    val success: String? = null,
    val error: String? = null,
    val testMode: Boolean = false,
) {
    val amountCheck: TopUpAmount.Check
        get() = if (custom.isBlank()) TopUpAmount.Check.Ok(chip) else TopUpAmount.check(custom)
    val amount: Int? get() = (amountCheck as? TopUpAmount.Check.Ok)?.amount
    val customError: String? get() = if (custom.isBlank()) null else (amountCheck as? TopUpAmount.Check.Invalid)?.reason
}

class WalletViewModel(private val backend: Backend, private val flow: TopUpFlow) : ViewModel() {
    private val _state = MutableStateFlow(WalletUiState(testMode = flow.isTestMode))
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { backend.balance.collect { b -> _state.update { it.copy(balance = b) } } }
        viewModelScope.launch { backend.tiers.collect { p -> _state.update { it.copy(prices = p) } } }
        // Fresh prices for the "≈ N answers" figures (they may have changed since the ask screen loaded them).
        viewModelScope.launch { runCatching { backend.refreshTiers() } }
        viewModelScope.launch {
            try {
                backend.refreshBalance()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = ErrorText.of(e)) }
            }
        }
    }

    fun pickChip(amount: Int) = _state.update { it.copy(chip = amount, custom = "", success = null, error = null) }

    fun setCustom(text: String) =
        _state.update { it.copy(custom = text.filter { c -> c.isDigit() }.take(5), success = null, error = null) }

    fun pay() {
        val s = _state.value
        val amount = s.amount ?: return
        if (s.busy) return
        _state.update { it.copy(busy = true, success = null, error = null) }
        viewModelScope.launch {
            val next = try {
                when (val r = flow.topUp(amount)) {
                    is TopUpFlow.Result.Done ->
                        _state.value.copy(success = "Fare added: ${Money.inrWhole(amount)}. Your balance is now ${Money.inr(r.balanceInr)}.", custom = "")
                    TopUpFlow.Result.Cancelled ->
                        _state.value.copy(error = "Payment cancelled. No money was taken.")
                    is TopUpFlow.Result.Failed -> _state.value.copy(error = r.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value.copy(error = ErrorText.of(e))
            }
            _state.value = next.copy(busy = false)
        }
    }
}
