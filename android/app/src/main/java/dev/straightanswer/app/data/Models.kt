package dev.straightanswer.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models for the backend contract (see README "Backend API contract"). Field names match the JSON exactly.

@Serializable
data class RegisterResponse(val token: String)

@Serializable
data class WalletResponse(@SerialName("balance_inr") val balanceInr: Double)

@Serializable
data class TopUpRequest(@SerialName("amount_inr") val amountInr: Int)

@Serializable
data class TopUpResponse(
    @SerialName("checkout_url") val checkoutUrl: String,
    @SerialName("topup_id") val topupId: String,
)

@Serializable
data class TopUpConfirmRequest(@SerialName("topup_id") val topupId: String)

@Serializable
data class ModelTier(
    val tier: String,
    val label: String = "",
    @SerialName("est_cost_per_message_inr") val estCostPerMessageInr: Double,
)

@Serializable
data class ModelsResponse(val tiers: List<ModelTier> = emptyList())

@Serializable
data class ChatRequest(
    val tier: String,
    val message: String,
    @SerialName("conversation_id") val conversationId: String?,
    @SerialName("image_base64") val imageBase64: String?,
)

@Serializable
data class ChatResponse(
    val reply: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("cost_inr") val costInr: Double,
    @SerialName("wallet_balance_inr") val walletBalanceInr: Double,
    /** The model hit its length limit and the reply stops mid-way. Optional while the backend rolls it out. */
    val truncated: Boolean = false,
)

/** POST /v1/auth/signup and /v1/auth/login. */
@Serializable
data class CredentialsRequest(val email: String, val password: String)

/** POST /v1/auth/forgot-password. The server always answers with a generic "if that email exists…" success. */
@Serializable
data class ForgotPasswordRequest(val email: String)
