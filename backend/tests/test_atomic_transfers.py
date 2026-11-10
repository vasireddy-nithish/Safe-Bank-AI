"""Unit tests for BankingService: atomic transfers, idempotency, Decimal precision, and Restricted Mode."""

import pytest
from decimal import Decimal
from sqlalchemy.orm import Session
from app.models.user import User
from app.models.account import Account
from app.models.transaction import Transaction
from app.services.banking_service import BankingService

@pytest.fixture
def banking_fixture(db_session: Session):
    """Sets up two accounts for transfer testing."""
    user1 = User(username="sender_bank", email="sender@example.com", full_name="Sender User")
    user2 = User(username="receiver_bank", email="receiver@example.com", full_name="Receiver User")
    db_session.add_all([user1, user2])
    db_session.commit()

    account1 = Account(
        user_id=user1.id,
        account_number="SB-SENDER-001",
        balance=Decimal("1000.00"),
        status="active",
    )
    account2 = Account(
        user_id=user2.id,
        account_number="SB-RECV-002",
        balance=Decimal("250.00"),
        status="active",
    )
    db_session.add_all([account1, account2])
    db_session.commit()
    db_session.refresh(account1)
    db_session.refresh(account2)

    return account1, account2

def test_atomic_transfer_success(db_session: Session, banking_fixture):
    """Verify atomic debit and credit of accounts with exact Decimal precision."""
    acc1, acc2 = banking_fixture
    initial_acc1_balance = acc1.balance
    initial_acc2_balance = acc2.balance
    transfer_amount = Decimal("300.25")

    result = BankingService.execute_transfer(
        db=db_session,
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=transfer_amount,
        idempotency_key="idemp_tx_success_001",
        description="Invoice Payment #45",
    )

    assert result.success is True
    assert result.transaction is not None
    assert result.transaction.status == "completed"
    assert result.transaction.amount == transfer_amount
    assert result.transaction.is_duress_masked is False

    db_session.refresh(acc1)
    db_session.refresh(acc2)

    # Balances must be atomically updated
    assert acc1.balance == initial_acc1_balance - transfer_amount  # 699.75
    assert acc2.balance == initial_acc2_balance + transfer_amount  # 550.25

def test_atomic_transfer_insufficient_funds_rollback(db_session: Session, banking_fixture):
    """Verify transfer fails when balance is insufficient; balances remain untouched."""
    acc1, acc2 = banking_fixture
    initial_acc1_balance = acc1.balance
    initial_acc2_balance = acc2.balance

    # Attempt to transfer more than available
    excess_amount = Decimal("2500.00")

    result = BankingService.execute_transfer(
        db=db_session,
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=excess_amount,
        idempotency_key="idemp_tx_fail_002",
        description="Large Transfer",
    )

    assert result.success is False
    assert result.error_code == "INSUFFICIENT_FUNDS"
    assert result.transaction is not None
    assert result.transaction.status == "failed"

    db_session.refresh(acc1)
    db_session.refresh(acc2)

    # Balances must remain strictly unchanged
    assert acc1.balance == initial_acc1_balance
    assert acc2.balance == initial_acc2_balance

def test_idempotency_duplicate_protection(db_session: Session, banking_fixture):
    """Verify duplicate submission with the same idempotency key does not re-debit accounts."""
    acc1, acc2 = banking_fixture
    transfer_amount = Decimal("100.00")
    key = "idemp_duplicate_test_key"

    # First attempt
    res1 = BankingService.execute_transfer(
        db=db_session,
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=transfer_amount,
        idempotency_key=key,
    )
    assert res1.success is True
    tx_ref = res1.transaction.reference_number

    db_session.refresh(acc1)
    db_session.refresh(acc2)
    acc1_balance_after_first = acc1.balance
    acc2_balance_after_first = acc2.balance

    # Second attempt (replay with same idempotency key)
    res2 = BankingService.execute_transfer(
        db=db_session,
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=transfer_amount,
        idempotency_key=key,
    )
    assert res2.success is True
    assert res2.transaction.reference_number == tx_ref  # Same transaction returned

    db_session.refresh(acc1)
    db_session.refresh(acc2)

    # Balances must NOT have been deducted a second time
    assert acc1.balance == acc1_balance_after_first
    assert acc2.balance == acc2_balance_after_first

def test_coercion_restricted_mode_transfer(db_session: Session, banking_fixture):
    """
    Verify Coercion Restricted Mode:
    Under duress, transfer returns success to satisfy coercer, but marks is_duress_masked=True
    and does NOT deduct or move any actual money.
    """
    acc1, acc2 = banking_fixture
    initial_acc1_balance = acc1.balance
    initial_acc2_balance = acc2.balance
    coerced_amount = Decimal("500.00")

    result = BankingService.execute_transfer(
        db=db_session,
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=coerced_amount,
        idempotency_key="idemp_duress_masked_key",
        description="Coerced Extortion Transfer",
        is_restricted=True,  # Coercion Restricted Mode Active
    )

    # Transfer appears successful to attacker
    assert result.success is True
    assert result.transaction is not None
    assert result.transaction.status == "completed"
    assert result.transaction.is_duress_masked is True

    db_session.refresh(acc1)
    db_session.refresh(acc2)

    # CRITICAL: In restricted mode, balances must NOT change!
    assert acc1.balance == initial_acc1_balance
    assert acc2.balance == initial_acc2_balance

def test_invalid_transfer_arguments(db_session: Session, banking_fixture):
    """Verify rejection of zero amounts and transfers to same account."""
    acc1, acc2 = banking_fixture

    # Zero amount
    r1 = BankingService.execute_transfer(
        db=db_session,
        from_account_id=acc1.id,
        to_account_id=acc2.id,
        amount=Decimal("0.00"),
        idempotency_key="key_zero",
    )
    assert r1.success is False
    assert r1.error_code == "INVALID_AMOUNT"

    # Same account
    r2 = BankingService.execute_transfer(
        db=db_session,
        from_account_id=acc1.id,
        to_account_id=acc1.id,
        amount=Decimal("50.00"),
        idempotency_key="key_same",
    )
    assert r2.success is False
    assert r2.error_code == "SAME_ACCOUNT_TRANSFER"
