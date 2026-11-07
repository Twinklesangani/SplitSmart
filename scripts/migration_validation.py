"""Fail-closed, value-redacted V7 -> PostgreSQL V11 pre-write validation.

No database or filesystem access. Target metadata is supplied by the guarded
controller. ICU uniqueness must additionally be checked on the LOCAL server.
This reduces predictable input errors; it is not PostgreSQL log redaction.
"""
from dataclasses import dataclass
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation, localcontext
import re


class ValidationError(Exception):
    """Only fixed codes, never exception messages containing input values."""


def require(condition, code):
    if not condition:
        raise ValidationError(code)


@dataclass(frozen=True)
class Column:
    kind: str
    nullable: bool = False
    length: int | None = None
    precision: int | None = None
    scale: int | None = None


def field(value, column):
    if value is None:
        require(column.nullable, 'NULLABILITY')
        return None
    require(isinstance(value, str), 'INPUT_REPRESENTATION')
    try:
        value.encode('utf-8', errors='strict')
    except UnicodeError:
        raise ValidationError('UNICODE') from None
    require('\0' not in value, 'NUL_TEXT')
    kind = column.kind
    if kind in ('smallint', 'integer', 'bigint'):
        require(re.fullmatch(r'[+-]?[0-9]+', value) is not None, 'INTEGER')
        # Bound length before int conversion, including hostile huge inputs.
        require(len(value.lstrip('+-').lstrip('0')) <= 19, 'INTEGER_RANGE')
        number = int(value)
        bits = {'smallint': 16, 'integer': 32, 'bigint': 64}[kind]
        require(-(2 ** (bits-1)) <= number < 2 ** (bits-1), 'INTEGER_RANGE')
        return str(number)
    if kind == 'numeric':
        require(column.precision is not None and column.scale is not None, 'NUMERIC_SCHEMA')
        require(re.fullmatch(r'[+-]?[0-9]+(?:\.[0-9]+)?', value) is not None, 'NUMERIC')
        try:
            with localcontext() as context:
                context.prec = max(40, len(value) + 2)
                number = Decimal(value)
                quantum = Decimal(1).scaleb(-column.scale)
                require(number.is_finite() and number == number.quantize(quantum), 'NUMERIC_SCALE')
                require(abs(number) < Decimal(10) ** (column.precision-column.scale), 'NUMERIC_RANGE')
                return format(number.quantize(quantum), 'f')
        except InvalidOperation:
            raise ValidationError('NUMERIC') from None
    if kind == 'boolean':
        require(value in ('0', '1', 'f', 't', 'false', 'true'), 'BOOLEAN')
        return 'true' if value in ('1', 't', 'true') else 'false'
    if kind in ('date', 'timestamp without time zone', 'timestamp with time zone'):
        try:
            if kind == 'date':
                require(re.fullmatch(r'\d{4}-\d{2}-\d{2}', value) is not None, 'DATE')
                return date.fromisoformat(value).isoformat()
            require(re.fullmatch(r'\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})?', value) is not None, 'TIMESTAMP')
            parsed = datetime.fromisoformat(value)
            if kind == 'timestamp without time zone':
                require(parsed.tzinfo is None, 'NAIVE_TIMESTAMP_REQUIRED')
            else:
                # Source MySQL session is explicitly UTC, including TIMESTAMP.
                parsed = parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
            return parsed.isoformat(timespec='microseconds')
        except (ValueError, OverflowError):
            raise ValidationError('TIMESTAMP') from None
    require(kind in ('character varying', 'character', 'text'), 'UNSUPPORTED_TYPE')
    require(column.length is None or len(value) <= column.length, 'TEXT_LENGTH')
    # Reject a representation that CHAR padding would change before insertion,
    # rather than discover a canonical-hash difference after writing records.
    require(kind != 'character' or len(value) == column.length, 'FIXED_TEXT_LENGTH')
    return value


def validate(payload, columns, unique_keys, foreign_keys, sequence_floors, check_gate):
    """All source columns, keys and finance checked before writer can be called.

    payload is table -> list[dict[column, str|None]], columns covers exactly the
    imported fields. unique_keys excludes ICU keys, checked separately via local
    PostgreSQL's actual collation (casefold is NOT an ICU substitute).
    """
    require(set(payload) == set(columns), 'TABLE_MAPPING')
    require(check_gate, 'UNRECOGNIZED_CHECK_CONSTRAINT')
    cleaned = {}
    for table, rows in payload.items():
        cleaned[table] = []
        for row in rows:
            require(set(row) == set(columns[table]), 'FIELD_MAPPING')
            cleaned[table].append({name: field(row[name], col) for name, col in columns[table].items()})
        for keys in unique_keys.get(table, []):
            seen = set()
            for row in cleaned[table]:
                key = tuple(row[k] for k in keys)
                if None in key:  # PostgreSQL default UNIQUE NULLS DISTINCT.
                    continue
                require(key not in seen, 'DUPLICATE_KEY')
                seen.add(key)
        if 'id' in columns[table]:
            ids = [int(row['id']) for row in cleaned[table]]
            require(all(x > 0 for x in ids), 'IDENTITY_RANGE')
            floor = sequence_floors.get(table)
            require(isinstance(floor, int) and max(ids, default=0) < floor < 2**63, 'SEQUENCE_FLOOR')
    positions = {table: index for index, table in enumerate(payload)}
    for table, keys, parent, parent_keys in foreign_keys:
        require(positions[parent] < positions[table], 'INSERTION_ORDER')
        references = {tuple(row[k] for k in parent_keys) for row in cleaned[parent]}
        for row in cleaned[table]:
            key = tuple(row[k] for k in keys)
            require(None in key or key in references, 'FOREIGN_KEY')
    validate_finance(cleaned)
    return cleaned


def validate_finance(data):
    categories = {'FOOD_DRINKS','TRAVEL','SHOPPING','RENT_UTILITIES','ENTERTAINMENT','OTHER'}
    for row in data.get('expenses', []):
        require(row['category'] in categories, 'CATEGORY_CHECK')
    for row in data.get('group_invitations', []):
        require(row['status'] in {'PENDING','ACCEPTED','DECLINED'}, 'INVITATION_CHECK')
    for table in ('expenses', 'expense_splits', 'settlements'):
        for row in data.get(table, []):
            require(Decimal(row['amount']) > 0, 'AMOUNT_CHECK')
    for row in data.get('settlements', []):
        require(row['from_user_id'] != row['to_user_id'], 'SETTLEMENT_ENDPOINTS')
    if 'expenses' in data and 'expense_splits' in data:
        totals = {}
        for row in data['expense_splits']:
            totals[row['expense_id']] = totals.get(row['expense_id'], Decimal(0)) + Decimal(row['amount'])
        for row in data['expenses']:
            require(totals.get(row['id']) == Decimal(row['amount']), 'SPLIT_SUM')


def validate_then_write(writer, *args, **kwargs):
    """One gate: writer is unreachable on any local-validation failure."""
    cleaned = validate(*args, **kwargs)
    return writer(cleaned)
