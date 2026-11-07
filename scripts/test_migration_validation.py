"""Synthetic-only tests; failures must never reach a writer or disclose input."""
import copy
import contextlib
import io
import unittest
from migration_validation import Column as C, ValidationError, field, validate_then_write


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.payload = {'parents': [{'id': '1', 'label': 'safe', 'at': '2026-11-08 01:00:00'}],
                        'children': [{'id': '2', 'parent': '1'}]}
        self.columns = {'parents': {'id': C('bigint'), 'label': C('character varying', length=8),
                                   'at': C('timestamp without time zone')},
                        'children': {'id': C('bigint'), 'parent': C('bigint')}}
        self.calls = []

    def run_gate(self, data):
        return validate_then_write(self.calls.append, data, self.columns,
            {'parents': [('id',)], 'children': [('id',)]},
            [('children', ('parent',), 'parents', ('id',))],
            {'parents': 3, 'children': 3}, True)

    def rejected(self, mutation, expected):
        data = copy.deepcopy(self.payload)
        mutation(data)
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            with self.assertRaises(ValidationError) as caught:
                self.run_gate(data)
        self.assertEqual(expected, str(caught.exception))
        self.assertNotIn('SYNTHETIC_PRIVATE_MARKER', str(caught.exception) + output.getvalue())
        self.assertEqual([], self.calls)

    def test_valid_calls_writer_once(self):
        self.run_gate(self.payload)
        self.assertEqual(1, len(self.calls))

    def test_invalid_integer(self):
        self.rejected(lambda d: d['parents'][0].update(id='SYNTHETIC_PRIVATE_MARKER'), 'INTEGER')

    def test_duplicate_key(self):
        self.rejected(lambda d: d['parents'].append(dict(d['parents'][0])), 'DUPLICATE_KEY')

    def test_foreign_key(self):
        self.rejected(lambda d: d['children'][0].update(parent='999'), 'FOREIGN_KEY')

    def test_text_length(self):
        self.rejected(lambda d: d['parents'][0].update(label='SYNTHETIC_PRIVATE_MARKER'), 'TEXT_LENGTH')

    def test_malformed_timestamp(self):
        self.rejected(lambda d: d['parents'][0].update(at='SYNTHETIC_PRIVATE_MARKER'), 'TIMESTAMP')

    def test_invalid_calendar_timestamp(self):
        self.rejected(lambda d: d['parents'][0].update(at='2026-02-30 00:00:00'), 'TIMESTAMP')

    def test_field_mapping(self):
        self.rejected(lambda d: d['parents'][0].update(unexpected='SYNTHETIC_PRIVATE_MARKER'), 'FIELD_MAPPING')

    def test_unicode(self):
        self.rejected(lambda d: d['parents'][0].update(label='\ud800'), 'UNICODE')

    def test_nullability(self):
        self.rejected(lambda d: d['parents'][0].update(label=None), 'NULLABILITY')

    def test_nul(self):
        self.rejected(lambda d: d['parents'][0].update(label='a\0b'), 'NUL_TEXT')

    def test_integer_boundary(self):
        for value in (str(2**63), str(-2**63-1), '1.0', 'NaN'):
            with self.assertRaises(ValidationError): field(value, C('bigint'))
        self.assertEqual(str(2**63-1), field(str(2**63-1), C('bigint')))

    def test_decimal_no_rounding_or_overflow(self):
        for value in ('1.001', '100000000000000000.00', 'NaN', 'Infinity', '1e2'):
            with self.assertRaises(ValidationError): field(value, C('numeric', precision=19, scale=2))
        self.assertEqual('50.00', field('50.00', C('numeric', precision=19, scale=2)))

    def test_timestamp_utc_and_precision(self):
        self.assertEqual('2026-11-07T18:30:00.000000+00:00',
            field('2026-11-08 00:00:00+05:30', C('timestamp with time zone')))
        with self.assertRaises(ValidationError):
            field('2026-11-08 00:00:00.0000001', C('timestamp without time zone'))

    def test_unknown_type_and_checks_fail_closed(self):
        with self.assertRaises(ValidationError): field('safe', C('jsonb'))
        with self.assertRaises(ValidationError):
            validate_then_write(self.calls.append, {}, {}, {}, [], {}, False)
        self.assertEqual([], self.calls)

    def test_sequence_floor(self):
        self.rejected(lambda d: d['parents'][0].update(id='3'), 'SEQUENCE_FLOOR')

    def test_split_mismatch(self):
        from migration_validation import validate_finance
        with self.assertRaisesRegex(ValidationError, '^SPLIT_SUM$'):
            validate_finance({'expenses': [{'id':'1','amount':'50.00','category':'OTHER'}],
                              'expense_splits': [{'expense_id':'1','amount':'49.00'}]})

    def test_boolean_and_date(self):
        self.assertEqual('false', field('0', C('boolean')))
        for value, col in [('unknown', C('boolean')), ('2026-02-30', C('date'))]:
            with self.assertRaises(ValidationError): field(value, col)

    def test_enum_amount_and_settlement_checks(self):
        from migration_validation import validate_finance
        for data, code in [
            ({'expenses':[{'category':'INVALID','amount':'1'}]}, 'CATEGORY_CHECK'),
            ({'group_invitations':[{'status':'INVALID'}]}, 'INVITATION_CHECK'),
            ({'settlements':[{'amount':'0'}]}, 'AMOUNT_CHECK'),
            ({'settlements':[{'amount':'1','from_user_id':'1','to_user_id':'1'}]}, 'SETTLEMENT_ENDPOINTS')]:
            with self.assertRaisesRegex(ValidationError, '^'+code+'$'):validate_finance(data)

    def test_nullable_unique_keys(self):
        from migration_validation import validate
        rows={'x':[{'slot':None},{'slot':None}]}
        self.assertEqual(rows,validate(rows,{'x':{'slot':C('text',nullable=True)}},{'x':[('slot',)]},[],{},True))

    def test_fixed_character_padding_rejected_before_write(self):
        with self.assertRaisesRegex(ValidationError,'^FIXED_TEXT_LENGTH$'):
            field('IN',C('character',length=3))
        self.assertEqual('INR',field('INR',C('character',length=3)))

    def test_restricted_identity_guard_needs_no_settings_grant(self):
        import local_pg_rehearsal as controller
        previous=controller.VERIFIED_START
        controller.VERIFIED_START='synthetic-incarnation'
        class Fake:
            def query(inner, sql):
                self.assertNotIn('data_directory',sql)
                return [('ss_pg_synthetic','127.0.0.1','25432','synthetic-incarnation')]
        try: controller.guard(Fake(),'ss_pg_synthetic')
        finally: controller.VERIFIED_START=previous

    def test_icu_uuid_uses_schema_type_and_checks_conflicts(self):
        from local_pg_rehearsal import icu_check
        import local_database_support as local
        class Fake:
            def query(inner, sql, params):
                self.assertIn('$2::text COLLATE splitsmart_email_ci', sql)
                self.assertNotIn('$2::bigint', sql)
                return [('1',)]  # Synthetic ICU-equivalent UUID conflict.
        with self.assertRaisesRegex(local.Stop,'^ICU_UNIQUE_CONFLICT$'):
            icu_check(Fake(),{'settlements':[{'group_id':'1','request_id':'SYNTHETIC_PRIVATE_MARKER'}]},
                [('settlements',('group_id','request_id'),{'request_id':'splitsmart_email_ci'})],
                {'settlements':{'group_id':C('bigint'),'request_id':C('character varying',length=36)}})


if __name__ == '__main__':
    unittest.main()
