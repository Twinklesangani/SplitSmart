"""Reviewed transfer engine, with no provider credentials or cloud execution CLI.

Only SYNTHETIC snapshots may execute in this version. Real execution deliberately
requires a later reviewed authorization adapter; no boolean flag accepts privacy
risk for the owner. Metadata-only cloud preflight never accepts source records.
"""
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import zlib


class TransferError(Exception):
    pass


def require(value, code):
    if not value:raise TransferError(code)


def identifier(value):
    require(isinstance(value,str) and re.fullmatch('[a-z][a-z0-9_]*',value),'IDENTIFIER')
    return '"'+value+'"'


def checksums(directory):
    """Flyway CRC32: UTF-8 lines excluding BOM and line terminators."""
    result=[]
    for version in range(1,12):
        paths=list(Path(directory).glob('V'+str(version)+'__*.sql'))
        require(len(paths)==1,'MIGRATION_FILE_SET')
        crc=0
        for line in paths[0].read_text(encoding='utf-8-sig').splitlines():
            crc=zlib.crc32(line.encode('utf-8'),crc)
        result.append((str(version),str(crc if crc<2**31 else crc-2**32),'t'))
    return result


@dataclass(frozen=True)
class Target:
    database: str
    user: str
    port: int
    cloud: bool=False


def guard(connection,target):
    row=connection.query('SELECT current_database(),current_user,inet_server_port()::text')[0]
    require(row==(target.database,target.user,str(target.port)),'TARGET_IDENTITY')
    if target.cloud:
        require(target.database=='splitsmart','CLOUD_APPLICATION_DATABASE')
        require(connection.query('SELECT ssl::text FROM pg_stat_ssl WHERE pid=pg_backend_pid()')==[('true',)],'TARGET_TLS')
        # verify-full hostname/CA/endpoint pinning must also be enforced by the
        # connection factory. Server-side ssl alone is not certificate validation.
    else:
        require(target.database.startswith('ss_pg_transfer_synthetic_') and target.port==25432,'LOCAL_SYNTHETIC_SCOPE')
        require(connection.query('SELECT host(inet_server_addr())')==[('127.0.0.1',)],'LOCAL_LOOPBACK')


def metadata_preflight(connection,target,migration_dir):
    """No record parameter; safe against accidental real-value cloud probes."""
    guard(connection,target)
    require(connection.query('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank')==checksums(migration_dir),'TARGET_FLYWAY_DRIFT')
    tables={r[0] for r in connection.query("SELECT tablename FROM pg_tables WHERE schemaname='public'")}
    from local_pg_rehearsal import ORDER
    expected=(set(ORDER)-{'flyway_schema_history'})|{'flyway_schema_history','password_reset_tokens','group_invite_links','email_verification_tokens','notifications'}
    require(tables==expected,'TARGET_TABLE_SET')
    for table in sorted(expected-{'flyway_schema_history'}):
        require(connection.query('SELECT COUNT(*) FROM public.'+identifier(table))==[('0',)],'TARGET_NOT_EMPTY')
    require(connection.query("SELECT COUNT(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='public' AND NOT c.convalidated")==[('0',)],'INVALID_CONSTRAINT')
    return {'status':'PASS','schema':'V1-V11','business_tables_empty':True}


class Ledger:
    """Exclusive creation means no automatic retry after any started attempt."""
    def __init__(self,path):
        self.path=Path(path)
        with self.path.open('x',encoding='utf-8') as file:
            json.dump({'status':'PREPARED'},file)
            file.flush();os.fsync(file.fileno())

    def record(self,status,**metadata):
        with self.path.open('w',encoding='utf-8') as file:
            json.dump({'status':status,**metadata},file,indent=2)
            file.flush();os.fsync(file.fileno())


def transfer(factory,target,migration_dir,originals,floors,ledger_path,classification):
    # This gate precedes credentials, connections, uploads and attempt creation.
    require(classification=='SYNTHETIC' and not target.cloud,'REAL_DATA_EXECUTION_NOT_AUTHORIZED')
    from local_pg_rehearsal import metadata,icu_check,balances
    from migration_validation import validate
    from postgres_compatibility import digest
    ledger=Ledger(ledger_path)
    connection=None
    state='PREPARED'
    try:
        connection=factory()
        metadata_preflight(connection,target,migration_dir)
        names={t:originals[t][0] for t in originals}
        for fields,_,rows in originals.values():
            require(len(fields)==len(set(fields)) and all(len(row)==len(fields) for row in rows),'SOURCE_ROW_SHAPE')
        payload={t:[dict(zip(cols,row)) for row in rows] for t,(cols,_,rows) in originals.items()}
        require(set(payload)=={'users','expense_groups','group_members','expenses','expense_splits','settlements','expense_creation_operations','group_invitations','legacy_mysql_flyway_history'},'SOURCE_TABLE_SET')
        from local_database_support import CHECKSUMS
        history=payload['legacy_mysql_flyway_history']
        require([(r['version'],r['checksum'],r['success']) for r in history]==[(str(i+1),str(c),'1') for i,c in enumerate(CHECKSUMS)],'SOURCE_V7_HISTORY')
        guard(connection,target)
        ledger.record('STARTED');state='STARTED'
        connection.query('BEGIN ISOLATION LEVEL SERIALIZABLE')
        require(connection.query('SELECT pg_try_advisory_xact_lock(7346158)')==[('t',)],'IMPORT_ALREADY_RUNNING')
        tables=[t for t in payload if t!='legacy_mysql_flyway_history']
        tables+=['password_reset_tokens','group_invite_links','email_verification_tokens',
                 'notifications','flyway_schema_history']
        connection.query('LOCK TABLE '+','.join('public.'+identifier(t) for t in tables)+' IN SHARE ROW EXCLUSIVE MODE NOWAIT')
        # Recheck under locks; no TOCTOU import into newly populated tables.
        metadata_preflight(connection,target,migration_dir)
        guard(connection,target)
        connection.query('CREATE TABLE legacy_mysql_flyway_history (LIKE flyway_schema_history INCLUDING ALL)')
        cols,unique,foreign,icu=metadata(connection,payload,names)
        clean=validate(payload,cols,unique,foreign,floors,True)
        icu_check(connection,clean,icu,cols)
        ledger_before=balances(clean)
        for table,rows in clean.items():
            fields=names[table]
            sql='INSERT INTO public.'+identifier(table)+' ('+','.join(identifier(c) for c in fields)+') VALUES ('+','.join('$'+str(i+1) for i in range(len(fields)))+')'
            for row in rows:connection.query(sql,[row[c] for c in fields])
        hashes={}
        for table,(fields,kinds,rows) in originals.items():
            key='installed_rank' if table=='legacy_mysql_flyway_history' else 'id'
            actual=connection.query('SELECT '+','.join(identifier(c) for c in fields)+' FROM public.'+identifier(table)+' ORDER BY '+identifier(key))
            require(len(actual)==len(rows) and digest(actual,kinds)==digest(rows,kinds),'DATA_RECONCILIATION')
            hashes[table]=digest(actual,kinds)
        restored={t:[dict(zip(fields,row)) for row in connection.query('SELECT '+','.join(identifier(c) for c in fields)+' FROM public.'+identifier(t))] for t,(fields,_,_) in originals.items()}
        require(balances(restored)==ledger_before,'FINANCIAL_RECONCILIATION')
        require(connection.query('SELECT COUNT(*) FROM users WHERE auth_token_version<>0 OR email_verified_at IS NOT NULL OR password_reset_requested_at IS NOT NULL')==[('0',)],'LEGACY_AUTH_DEFAULTS')
        for table,floor in floors.items():
            require(isinstance(floor,int) and 0<floor<2**63,'SEQUENCE_RANGE')
            sequence=connection.query("SELECT pg_get_serial_sequence($1,'id')",[table])[0][0]
            require(sequence and re.fullmatch(r'public\.[a-z_]+',sequence),'SEQUENCE_IDENTITY')
            # RESTART is transactional, unlike setval; rolled-back imports do not
            # leave changed identity floors. Only validated numeric DDL is joined.
            connection.query('ALTER SEQUENCE public.'+identifier(sequence.split('.')[1])+' RESTART WITH '+str(floor))
            require(connection.query('SELECT last_value,is_called FROM '+sequence)==[(str(floor),'f')],'SEQUENCE_FLOOR')
        ledger.record('COMMIT_PENDING');state='COMMIT_PENDING'
        connection.query('COMMIT')
        ledger.record('COMMITTED_UNVERIFIED');state='COMMITTED_UNVERIFIED'
        connection.close();connection=None
        connection=factory();guard(connection,target)
        for table,(fields,kinds,rows) in originals.items():
            key='installed_rank' if table=='legacy_mysql_flyway_history' else 'id'
            actual=connection.query('SELECT '+','.join(identifier(c) for c in fields)+' FROM public.'+identifier(table)+' ORDER BY '+identifier(key))
            require(len(actual)==len(rows) and digest(actual,kinds)==hashes[table],'COMMITTED_RECONCILIATION')
        ledger.record('VERIFIED',tables=len(hashes))
        return {'status':'PASS','tables':len(hashes)}
    except Exception:
        # Never print exception text or chain input-bearing driver diagnostics.
        if state=='COMMIT_PENDING':
            ledger.record('COMMIT_UNKNOWN')
        elif state=='COMMITTED_UNVERIFIED':
            ledger.record('COMMITTED_UNVERIFIED')
        elif connection and state=='STARTED':
            try:
                connection.query('ROLLBACK');ledger.record('ROLLED_BACK')
            except Exception:
                ledger.record('ROLLBACK_UNCONFIRMED')
        else:ledger.record('PREFLIGHT_FAILED')
        raise TransferError('TRANSFER_STOP_REVIEW_PRIVATE_LEDGER_NO_RETRY') from None
    finally:
        if connection:connection.close()
