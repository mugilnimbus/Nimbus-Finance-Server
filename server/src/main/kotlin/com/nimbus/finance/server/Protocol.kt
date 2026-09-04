package com.nimbus.finance.server

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

data class AuthUser(val userId: UUID, val username: String, val displayName: String, val sessionId: UUID)

@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class HealthResponse(
    val status: String,
    val version: String,
    val revision: String,
    val builtAt: String,
    val schemaVersion: Int
)
@Serializable data class AdminInviteRequest(val expiresInHours: Int = 24, val maxUses: Int = 1)
@Serializable data class UserInviteRequest(val expiresInHours: Int = 24)
@Serializable data class GroupInviteRequest(val expiresInHours: Int = 72)
@Serializable data class InviteResponse(val code: String, val expiresAt: String)
@Serializable data class RegisterRequest(val inviteCode: String, val username: String, val displayName: String, val password: String, val deviceName: String = "Android phone")
@Serializable data class LoginRequest(val username: String, val password: String, val deviceName: String = "Android phone")
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class UserResponse(val id: String, val username: String, val displayName: String)
@Serializable data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresInSeconds: Int, val user: UserResponse)
@Serializable data class UsernameAvailabilityResponse(val username: String, val available: Boolean, val valid: Boolean, val message: String)
@Serializable data class UpdateProfileRequest(val displayName: String)
@Serializable data class DeleteOwnAccountRequest(val username: String, val password: String, val mode: String = "TRASH")
@Serializable data class AccountDeletionResponse(val status: String, val purgeAfter: String? = null)
@Serializable data class AcceptInviteRequest(val code: String)
@Serializable data class AcceptInviteResponse(val groupId: String, val bootstrapRequired: Boolean)
@Serializable data class SyncOperation(
    val operationId: String, val entityType: String, val entityId: String, val action: String,
    val scopeType: String, val scopeId: String, val baseVersion: Long? = null, val payload: JsonElement? = null
)
@Serializable data class SyncPushRequest(val operations: List<SyncOperation>)
@Serializable data class SyncOperationResult(
    val operationId: String, val status: String, val serverVersion: Long? = null, val serverSeq: Long? = null,
    val authoritativePayload: JsonElement? = null, val errorCode: String? = null
)
@Serializable data class SyncPushResponse(val results: List<SyncOperationResult>)
@Serializable data class SyncChange(
    val serverSeq: Long, val operationId: String? = null, val scopeType: String, val scopeId: String,
    val entityType: String, val entityId: String, val action: String, val serverVersion: Long, val payload: JsonElement? = null
)
@Serializable data class SyncPullResponse(val changes: List<SyncChange>, val nextCursor: Long, val hasMore: Boolean)
@Serializable data class SyncBootstrapResponse(val records: List<SyncChange>, val cursor: Long, val hasMore: Boolean = false)
@Serializable internal data class ProfilePayload(val ownerName: String, val currencyCode: String, val monthStartDay: Int = 1)
@Serializable internal data class FinancialAccountPayload(
    val id: String, val name: String, val type: String, val active: Boolean = true,
    val openingBalanceMinor: Long = 0, val openingEpochDay: Long? = null,
    val nature: String = "ASSET", val liquidity: String = "LIQUID"
)
@Serializable internal data class CategoryPayload(val id: String, val name: String, val type: String, val active: Boolean = true)
@Serializable internal data class TransactionPayload(
    val id: String, val amountMinor: Long, val type: String, val category: String, val account: String, val epochDay: Long, val note: String = "",
    val attachments: List<AttachmentPayload> = emptyList(), val expenseSetId: String? = null,
    val lineItems: List<TransactionLineItemPayload> = emptyList(),
    val scheduledPaymentId: String? = null, val scheduledDueEpochDay: Long? = null,
    val minuteOfDay: Int? = null, val paymentMethod: String = ""
)
@Serializable internal data class TransactionLineItemPayload(val id: String, val description: String, val amountMinor: Long, val category: String)
@Serializable internal data class BudgetPayload(val category: String, val monthlyLimitMinor: Long)
@Serializable internal data class ExpenseSetPayload(val id: String, val name: String, val description: String = "")
@Serializable internal data class TransferPayload(val id: String, val amountMinor: Long, val fromAccount: String, val toAccount: String, val epochDay: Long, val note: String = "")
@Serializable internal data class LoanPayload(
    val id: String, val counterparty: String, val direction: String, val principalMinor: Long, val account: String,
    val startEpochDay: Long, val planType: String = "ONE_TIME", val annualInterestBps: Int = 0,
    val interestMode: String = "KNOWN_RATE",
    val termMonths: Int = 1, val dueDay: Int = 1, val monthlyPaymentMinor: Long = 0, val note: String = "",
    val firstDueEpochDay: Long? = null, val reminderDaysBefore: Int = 1,
    val customRepayments: List<PlannedLoanPaymentPayload> = emptyList(),
    val cashTreatment: String = "FULL_HISTORY", val initialCashImpactMinor: Long = principalMinor,
    val reconciledBalanceMinor: Long? = null, val reconciledEpochDay: Long? = null,
    val purpose: String = "CASH", val downPaymentMinor: Long = 0,
    val initialAccountChangeMinor: Long = if (direction == "BORROWED") initialCashImpactMinor else -initialCashImpactMinor
)
@Serializable internal data class PlannedLoanPaymentPayload(val id: String, val dueEpochDay: Long, val amountMinor: Long)
@Serializable internal data class LoanPaymentPayload(
    val id: String, val loanId: String, val amountMinor: Long, val principalMinor: Long, val interestMinor: Long,
    val account: String, val epochDay: Long, val note: String = "", val scheduledDueEpochDay: Long? = null,
    val affectsCashBalance: Boolean = true, val origin: String = "MANUAL"
)
@Serializable internal data class ScheduledPaymentPayload(
    val id: String, val title: String, val amountMinor: Long, val category: String, val account: String,
    val startEpochDay: Long, val intervalCount: Int = 1, val intervalUnit: String = "MONTHS",
    val reminderDaysBefore: Int = 1, val note: String = "", val active: Boolean = true,
    val lastCompletedDueEpochDay: Long? = null, val completedDueEpochDays: List<Long> = emptyList()
)
@Serializable internal data class InvestmentPayload(
    val id: String, val name: String, val kind: String, val ownershipBps: Int = 10_000,
    val startEpochDay: Long, val description: String = "", val archived: Boolean = false,
    val ledgerScope: String = "WHOLE_COMPANY"
)
@Serializable internal data class InvestmentAccountPayload(
    val id: String, val investmentId: String, val name: String, val type: String, val active: Boolean = true
)
@Serializable internal data class InvestmentEntryPayload(
    val id: String, val investmentId: String, val type: String, val amountMinor: Long, val category: String,
    val personalAccountId: String? = null, val investmentAccountId: String? = null, val toInvestmentAccountId: String? = null,
    val basisMinor: Long = 0, val principalMinor: Long = 0, val counterparty: String = "", val epochDay: Long,
    val note: String = "", val attachments: List<AttachmentPayload> = emptyList()
)
@Serializable internal data class InvestmentValuationPayload(
    val id: String, val investmentId: String, val valueMinor: Long, val epochDay: Long, val note: String = ""
)
@Serializable internal data class GroupPayload(val id: String, val name: String, val members: List<String>)
@Serializable internal data class GroupExpensePayload(
    val id: String, val title: String, val amountMinor: Long, val paidBy: String, val participants: List<String>, val epochDay: Long, val note: String = "",
    val type: String = "EXPENSE", val category: String = "Other", val account: String = "Bank", val attachments: List<AttachmentPayload> = emptyList(),
    val paidByUserId: String? = null, val participantUserIds: List<String> = emptyList()
)
@Serializable internal data class AttachmentPayload(val id: String, val fileName: String, val mimeType: String, val sizeBytes: Long, val base64Data: String)
@Serializable internal data class SettlementPayload(
    val id: String, val from: String, val to: String, val amountMinor: Long, val epochDay: Long,
    val fromUserId: String? = null, val toUserId: String? = null
)
