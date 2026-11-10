"""0002_add_security_check_constraints

Revision ID: c25c62be0009
Revises: fc61214fe1b1
Create Date: 2026-11-10 20:13:18.840129

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

# revision identifiers, used by Alembic.
revision: str = 'c25c62be0009'
down_revision: Union[str, Sequence[str], None] = 'fc61214fe1b1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

def upgrade() -> None:
    # 1. Accounts constraints
    with op.batch_alter_table('accounts', recreate='always') as batch_op:
        batch_op.create_check_constraint('check_account_balance_non_negative', 'balance >= 0')
        batch_op.create_check_constraint('check_account_status_valid', "status IN ('active', 'frozen', 'restricted')")
        batch_op.create_check_constraint('check_account_type_valid', "account_type IN ('checking', 'savings')")

    # 2. Transactions constraints
    with op.batch_alter_table('transactions', recreate='always') as batch_op:
        batch_op.create_check_constraint('check_transaction_amount_positive', 'amount > 0')
        batch_op.create_check_constraint(
            'check_transaction_status_valid',
            "status IN ('pending', 'completed', 'failed', 'cancelled', 'simulated_duress')"
        )
        batch_op.create_check_constraint(
            'check_transaction_type_valid',
            "transaction_type IN ('transfer', 'payment', 'deposit')"
        )

    # 3. Risk events constraints
    with op.batch_alter_table('risk_events', recreate='always') as batch_op:
        batch_op.create_check_constraint('check_risk_score_range', 'risk_score >= 0 AND risk_score <= 100')
        batch_op.create_check_constraint('check_risk_level_valid', "risk_level IN ('low', 'medium', 'high')")
        batch_op.create_check_constraint(
            'check_behavioral_verdict_valid',
            "behavioral_verdict IN ('consistent', 'uncertain', 'deviation')"
        )
        batch_op.create_check_constraint(
            'check_risk_action_taken_valid',
            "action_taken IN ('allow', 'step_up', 'restrict')"
        )

    # 4. Devices constraints
    with op.batch_alter_table('devices', recreate='always') as batch_op:
        batch_op.create_check_constraint('check_device_status_valid', "status IN ('active', 'revoked')")
        batch_op.create_check_constraint(
            'check_device_attestation_type_valid',
            "attestation_type IN ('strongbox', 'tee', 'software')"
        )

    # 5. Duress events constraints
    with op.batch_alter_table('duress_events', recreate='always') as batch_op:
        batch_op.create_check_constraint(
            'check_duress_delivery_status_valid',
            "delivery_status IN ('delivered', 'queued')"
        )

    # 6. Security events constraints
    with op.batch_alter_table('security_events', recreate='always') as batch_op:
        batch_op.create_check_constraint(
            'check_security_event_severity_valid',
            "severity IN ('info', 'warning', 'critical')"
        )

def downgrade() -> None:
    with op.batch_alter_table('security_events', recreate='always') as batch_op:
        batch_op.drop_constraint('check_security_event_severity_valid', type_='check')

    with op.batch_alter_table('duress_events', recreate='always') as batch_op:
        batch_op.drop_constraint('check_duress_delivery_status_valid', type_='check')

    with op.batch_alter_table('devices', recreate='always') as batch_op:
        batch_op.drop_constraint('check_device_status_valid', type_='check')
        batch_op.drop_constraint('check_device_attestation_type_valid', type_='check')

    with op.batch_alter_table('risk_events', recreate='always') as batch_op:
        batch_op.drop_constraint('check_risk_score_range', type_='check')
        batch_op.drop_constraint('check_risk_level_valid', type_='check')
        batch_op.drop_constraint('check_behavioral_verdict_valid', type_='check')
        batch_op.drop_constraint('check_risk_action_taken_valid', type_='check')

    with op.batch_alter_table('transactions', recreate='always') as batch_op:
        batch_op.drop_constraint('check_transaction_amount_positive', type_='check')
        batch_op.drop_constraint('check_transaction_status_valid', type_='check')
        batch_op.drop_constraint('check_transaction_type_valid', type_='check')

    with op.batch_alter_table('accounts', recreate='always') as batch_op:
        batch_op.drop_constraint('check_account_balance_non_negative', type_='check')
        batch_op.drop_constraint('check_account_status_valid', type_='check')
        batch_op.drop_constraint('check_account_type_valid', type_='check')
