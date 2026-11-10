package com.safebank.ai.ui.model

enum class TransactionType {
    DEBIT,
    CREDIT,
    TRANSFER
}

enum class TransactionStatus {
    COMPLETED,
    PENDING,
    SIMULATED_DURESS,
    FAILED
}

enum class RiskTier {
    LOW,
    MODERATE,
    HIGH;

    companion object {
        fun fromScore(score: Int): RiskTier = when {
            score < 30 -> LOW
            score < 70 -> MODERATE
            else -> HIGH
        }
    }
}

data class AccountUiModel(
    val id: String = "acc-mock-01",
    val accountNumber: String = "ACCT-8492-3841",
    val maskedAccountNumber: String = "•••• 3841",
    val accountType: String = "Primary Checking",
    val balance: Double = 12450.80,
    val formattedBalance: String = "$12,450.80",
    val currency: String = "USD",
    val isPrimary: Boolean = true
)

data class TransactionUiModel(
    val id: String,
    val reference: String,
    val counterparty: String,
    val amount: Double,
    val formattedAmount: String,
    val type: TransactionType,
    val status: TransactionStatus,
    val category: String,
    val timestamp: String,
    val note: String = ""
)

data class SecurityStatusUiModel(
    val overallScore: Int = 96,
    val isProtected: Boolean = true,
    val hardwareKeystoreBound: Boolean = true,
    val hardwareKeystoreType: String = "Android StrongBox Keymaster",
    val biometricEnrolled: Boolean = true,
    val biometricLevel: String = "Class 3 (Strong Biometrics)",
    val passkeyEnrolled: Boolean = true,
    val passkeyRpId: String = "safebank.ai",
    val coercionDefenseArmed: Boolean = true,
    val behavioralRiskScore: Int = 14,
    val riskTier: RiskTier = RiskTier.LOW,
    val lastAttestationTime: String = "Today, 18:40 UTC"
)

data class UserProfileUiModel(
    val userId: String = "usr_mock_alex_chen",
    val fullName: String = "Alex Chen",
    val email: String = "alex.chen@safebank.example",
    val phoneNumber: String = "+1 (555) 349-2041",
    val enrolledDevicesCount: Int = 1,
    val currentDeviceName: String = "Google Pixel 8 Pro",
    val osVersion: String = "Android 15 (API 35)",
    val appVersion: String = "SafeBank AI v1.0.0-prototype",
    val dailyTransferLimit: Double = 5000.0,
    val formattedDailyLimit: String = "$5,000.00"
)

object MockDataProvider {
    val sampleAccount = AccountUiModel()

    val sampleSecurityStatus = SecurityStatusUiModel()

    val sampleUserProfile = UserProfileUiModel()

    val sampleTransactions = listOf(
        TransactionUiModel(
            id = "tx-001",
            reference = "TX-9F82A-01",
            counterparty = "Apex Payroll Direct Deposit",
            amount = 3250.00,
            formattedAmount = "+$3,250.00",
            type = TransactionType.CREDIT,
            status = TransactionStatus.COMPLETED,
            category = "Income",
            timestamp = "Today, 09:30 AM",
            note = "Bi-weekly salary deposit"
        ),
        TransactionUiModel(
            id = "tx-002",
            reference = "TX-8A31C-02",
            counterparty = "Quantum Cloud Infrastructure",
            amount = -149.99,
            formattedAmount = "-$149.99",
            type = TransactionType.DEBIT,
            status = TransactionStatus.COMPLETED,
            category = "Subscriptions",
            timestamp = "Yesterday, 04:15 PM",
            note = "Monthly hosting subscription"
        ),
        TransactionUiModel(
            id = "tx-003",
            reference = "TX-7B29D-03",
            counterparty = "Sarah Miller",
            amount = -85.00,
            formattedAmount = "-$85.00",
            type = TransactionType.TRANSFER,
            status = TransactionStatus.COMPLETED,
            category = "Transfer",
            timestamp = "Nov 8, 07:22 PM",
            note = "Dinner split"
        ),
        TransactionUiModel(
            id = "tx-004",
            reference = "TX-6C18E-04",
            counterparty = "Nordic Coffee Lab",
            amount = -6.75,
            formattedAmount = "-$6.75",
            type = TransactionType.DEBIT,
            status = TransactionStatus.COMPLETED,
            category = "Food & Drink",
            timestamp = "Nov 8, 08:14 AM",
            note = "Morning espresso"
        ),
        TransactionUiModel(
            id = "tx-005",
            reference = "TX-5D07F-05",
            counterparty = "Marcus Vance (Coercion Sim)",
            amount = 0.00,
            formattedAmount = "$500.00 (Isolated)",
            type = TransactionType.TRANSFER,
            status = TransactionStatus.SIMULATED_DURESS,
            category = "Duress Isolated",
            timestamp = "Nov 7, 11:45 PM",
            note = "Simulated honeypot transfer — zero ledger balance modified"
        ),
        TransactionUiModel(
            id = "tx-006",
            reference = "TX-4E96G-06",
            counterparty = "Horizon Power Utilities",
            amount = -112.40,
            formattedAmount = "-$112.40",
            type = TransactionType.DEBIT,
            status = TransactionStatus.PENDING,
            category = "Utilities",
            timestamp = "Nov 6, 02:10 PM",
            note = "Electric utility bill payment"
        )
    )
}
