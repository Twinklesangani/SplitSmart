"""ONE approved fresh LOCAL V7 -> V11 rehearsal. No cloud/client env support.

Credentials/records remain in RAM or private DPAPI/storage. Never auto-resume an
attempt. Existing recovery databases are read-only and never schema targets.
"""
import datetime as dt
from decimal import Decimal
import hashlib
import json
import re
import secrets
import ctypes as C
from pathlib import Path

import local_database_support as l
from postgres_compatibility import Pg as BasePg, unprotect, digest, maven, WORK
from migration_validation import Column, ValidationError, validate

CLUSTER = l.BASE/'RestoreRehearsals/postgres-20261107T160405Z'
MYSQL_ROOT = l.BASE/'RestoreRehearsals/native-20261107T155155Z'
RECEIPT = l.BASE/'FreshMySQLPreservation/result.json'
RUN = l.BASE/'LocalPgRealDataRehearsal'
ORDER = ['users','expense_groups','group_members','expenses','expense_splits',
         'settlements','expense_creation_operations','group_invitations','flyway_schema_history']
ADDITIONS = {'users': {'auth_token_version','password_reset_requested_at','email_verified_at'},
             'group_invitations': {'revoked_at'}}
CHECKS = {
 ('expense_splits','chk_expense_splits_amount_positive'): 'CHECK ((amount > (0)::numeric))',
 ('expenses','chk_expenses_amount_positive'): 'CHECK ((amount > (0)::numeric))',
 ('settlements','chk_settlements_amount_positive'): 'CHECK ((amount > (0)::numeric))',
 ('settlements','chk_settlements_distinct_users'): 'CHECK ((from_user_id <> to_user_id))',
 ('expenses','chk_expense_category'): "CHECK (((category)::text = ANY ((ARRAY['FOOD_DRINKS'::character varying, 'TRAVEL'::character varying, 'SHOPPING'::character varying, 'RENT_UTILITIES'::character varying, 'ENTERTAINMENT'::character varying, 'OTHER'::character varying])::text[])))",
 ('group_invitations','ck_invitation_status'): "CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACCEPTED'::character varying, 'DECLINED'::character varying])::text[])))",
}


class Pg(BasePg):
    """Do not let libpq's default NOTICE handler print data-bearing messages."""
    def __init__(self, *args):
        super().__init__(*args)
        callback = C.CFUNCTYPE(None,C.c_void_p,C.c_char_p)
        self.notice_sink = callback(lambda _arg,_message: None)
        self.lib.PQsetNoticeProcessor.argtypes = [C.c_void_p,callback,C.c_void_p]
        self.lib.PQsetNoticeProcessor.restype = C.c_void_p
        self.lib.PQsetNoticeProcessor(self.conn,self.notice_sink,None)


VERIFIED_START = None


def private_acl(path):
    q = str(path).replace("'", "''")
    l.ps("$p='"+q+"';$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;"
         "$items=@(Get-Item -LiteralPath $p)+@(Get-ChildItem -LiteralPath $p -Recurse -Force);"
         "foreach($x in $items){if($x.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'REPARSE'};"
         "$a=Get-Acl -LiteralPath $x.FullName;foreach($r in $a.Access){"
         "if($r.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin @($sid,'S-1-5-18')){throw 'ACL'}}}")


def guard(pg, database):
    global VERIFIED_START
    row = pg.query("SELECT current_database(),host(inet_server_addr()),inet_server_port()::text,pg_postmaster_start_time()::text")[0]
    l.require(row[:3] == (database,'127.0.0.1','25432'), 'LOCAL_PG_IDENTITY')
    if database == 'postgres':
        path = pg.query("SELECT current_setting('data_directory')")[0][0]
        l.require(Path(path).resolve() == (CLUSTER/'data').resolve(), 'LOCAL_PG_PATH')
        VERIFIED_START = row[3]
    # Runtime/migration roles do not receive pg_read_all_settings merely to read
    # data_directory. Admin verifies path; every restricted session must match
    # the same loopback endpoint AND postmaster incarnation, or stop.
    l.require(VERIFIED_START is not None and row[3] == VERIFIED_START, 'LOCAL_PG_INCARNATION')


def quiet(pg):
    # LOCAL admin session only: prevent data-bearing primary errors while retaining
    # cluster logging/auditing for other clients. Errors still reach SQLSTATE-only
    # client handling; PANIC remains logged. No global policy is changed.
    for name, value in [('log_min_messages','panic'),('log_min_error_statement','panic'),
                        ('log_statement','none'),('log_min_duration_statement','-1'),
                        ('log_min_duration_sample','-1'),('log_transaction_sample_rate','0'),
                        ('log_parameter_max_length','0'),('log_parameter_max_length_on_error','0')]:
        pg.query('SET '+name+" = '"+value+"'")


def safe_policy(pg):
    settings = dict(pg.query("SELECT name,setting FROM pg_settings WHERE name IN "
        "('log_min_messages','log_min_error_statement','log_statement','log_min_duration_statement',"
        "'log_min_duration_sample','log_transaction_sample_rate','log_parameter_max_length','log_parameter_max_length_on_error')"))
    l.require(settings == {'log_min_messages':'panic','log_min_error_statement':'panic',
        'log_statement':'none','log_min_duration_statement':'-1','log_min_duration_sample':'-1',
        'log_transaction_sample_rate':'0','log_parameter_max_length':'0',
        'log_parameter_max_length_on_error':'0'}, 'LOCAL_LOG_POLICY')
    l.require(pg.query("SELECT COUNT(*) FROM pg_settings WHERE name IN ('shared_preload_libraries','session_preload_libraries','local_preload_libraries') AND setting<>''") == [('0',)], 'UNREVIEWED_LOGGING_EXTENSION')


def preflight():
    receipt = json.loads(RECEIPT.read_text())
    l.require(receipt['status'] == 'PASS' and receipt['restore_attempts'] == 1 and
              receipt['restore_database'] == 'splitsmart_fresh_restore_20261107t205206z', 'FRESH_SOURCE_RECEIPT')
    backup = Path(receipt['backup'])
    l.require(backup.parent == l.BASE/'Backups' and backup.suffix == '.gpg' and
              hashlib.sha256(backup.read_bytes()).hexdigest() == receipt['sha256'], 'CIPHERTEXT_CHECKSUM')
    l.require(receipt['recipient_fingerprint'] == '1DC89048D3A259793291B71615557C70D0ADC178', 'GPG_RECIPIENT')
    protection = json.loads((l.BASE/'Backups/device-protection-verification-20261107-linked.json').read_text(encoding='utf-8-sig'))
    l.require(all(protection.get(k) for k in ('ProtectionOn','FullyEncrypted','TpmProtectorPresent','RecoveryPasswordProtectorPresent')), 'ENCRYPTION_EVIDENCE')
    # Owner independently verified escrow after this existing machine receipt.
    # A new privileged Windows query is not required or silently treated as passed.
    l.require(int(l.ps('[int64](Get-PSDrive C).Free')) > 8*1024**3, 'CAPACITY')
    for path in (CLUSTER, MYSQL_ROOT, l.BASE/'Backups', l.BASE/'GnuPG'):
        private_acl(path)
    pg = Pg('ss_pg_owner', unprotect(CLUSTER/'owner-credential.dpapi'), 'postgres')
    guard(pg, 'postgres')
    l.require(pg.query("SHOW listen_addresses") == [('127.0.0.1',)], 'LOOPBACK_ONLY')
    l.require(pg.query("SELECT current_setting('server_version_num')::integer BETWEEN 160000 AND 169999") == [('t',)], 'PG_MAJOR')
    pg.close()
    return receipt


def source_payload(receipt):
    src = l.MySQL(unprotect(MYSQL_ROOT/'local-credential.dpapi'))
    try:
        row = src.query('SELECT @@datadir,@@port,@@server_uuid')[0]
        identity = json.loads((MYSQL_ROOT/'result.json').read_text())
        l.require(Path(row[0]).resolve() == (MYSQL_ROOT/'data').resolve() and row[1] == '23307'
                  and row[2] == identity['server_uuid'], 'LOCAL_MYSQL_IDENTITY')
        src.query('USE `'+receipt['restore_database']+'`')
        src.query("SET SESSION time_zone='+00:00'")
        src.query('SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        src.query('SET TRANSACTION READ ONLY')
        src.query('START TRANSACTION WITH CONSISTENT SNAPSHOT')
        l.require({t for (t,) in src.query('SHOW TABLES')} == l.TABLES, 'SOURCE_TABLES')
        payload, originals = {}, {}
        for table in ORDER:
            meta = src.query("SELECT COLUMN_NAME,DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+table+"' ORDER BY ORDINAL_POSITION")
            names = [x[0] for x in meta]
            kinds = [('boolean' if (table,col) in {('expense_groups','archived'),('flyway_schema_history','success')} else kind) for col,kind in meta]
            rows = src.query('SELECT '+','.join('`'+x+'`' for x in names)+' FROM `'+table+'` ORDER BY `'+('installed_rank' if table=='flyway_schema_history' else 'id')+'`')
            target = 'legacy_mysql_flyway_history' if table == 'flyway_schema_history' else table
            payload[target] = [dict(zip(names,row)) for row in rows]
            originals[target] = (names,kinds,rows)
        floors = {t:int(n) for t,n in src.query('SELECT TABLE_NAME,AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND AUTO_INCREMENT IS NOT NULL')}
        l.require(src.query('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank') ==
                  [(str(i+1),str(c),'1') for i,c in enumerate(l.CHECKSUMS)], 'SOURCE_V7_HISTORY')
        l.require({t:len(originals['legacy_mysql_flyway_history' if t=='flyway_schema_history' else t][2]) for t in ORDER} == receipt['restored']['rows'], 'SOURCE_MANIFEST_COUNTS')
        for table in ('expenses','expense_splits','settlements'):
            l.require(sum((Decimal(row['amount']) for row in payload[table]),Decimal(0)) ==
                      Decimal(receipt['restored']['financial'][table]), 'SOURCE_MANIFEST_TOTALS')
        src.query('COMMIT')
        return payload, originals, floors
    finally:
        src.close()


def metadata(pg, payload, source_names=None):
    columns, collations = {}, {}
    for table in payload:
        rows = pg.query("SELECT column_name,data_type,is_nullable,character_maximum_length,numeric_precision,numeric_scale,collation_name FROM information_schema.columns WHERE table_schema='public' AND table_name=$1 ORDER BY ordinal_position",[table])
        target = {row[0] for row in rows}
        # Empty source tables still require exact source-field metadata supplied by
        # the caller; never infer the mapping from the first record.
        names = (source_names if source_names is not None else SOURCE_NAMES)[table]
        l.require(len(names)==len(set(names)) and set(names)==target-ADDITIONS.get(table,set()), 'TARGET_FIELD_MAPPING')
        columns[table] = {name:Column(kind,nullable=='YES',int(length) if length else None,
                        int(precision) if precision else None,int(scale) if scale else None)
                        for name,kind,nullable,length,precision,scale,_ in rows if name in names}
        collations[table] = {row[0]:row[6] for row in rows if row[6]}
    checks = {(t,n):definition for t,n,definition in pg.query("SELECT c.relname,x.conname,pg_get_constraintdef(x.oid) FROM pg_constraint x JOIN pg_class c ON c.oid=x.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND x.contype='c'")}
    l.require(checks == CHECKS, 'CHECK_DEFINITION_DRIFT')
    unique, icu, foreign = {}, [], []
    rows = pg.query("""SELECT c.relname,x.contype,
        array_to_json(ARRAY(SELECT a.attname FROM unnest(x.conkey) WITH ORDINALITY k(n,ord)
            JOIN pg_attribute a ON a.attrelid=x.conrelid AND a.attnum=k.n ORDER BY k.ord))::text,
        p.relname,array_to_json(ARRAY(SELECT a.attname FROM unnest(x.confkey) WITH ORDINALITY k(n,ord)
            JOIN pg_attribute a ON a.attrelid=x.confrelid AND a.attnum=k.n ORDER BY k.ord))::text,
        x.convalidated::text
        FROM pg_constraint x JOIN pg_class c ON c.oid=x.conrelid
        JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_class p ON p.oid=x.confrelid
        WHERE n.nspname='public' AND x.contype IN ('p','u','f')""")
    for table,kind,keys,parent,parent_keys,valid in rows:
        l.require(valid=='true', 'UNVALIDATED_CONSTRAINT')
        if table not in payload:
            continue
        keys = tuple(json.loads(keys))
        if kind in ('p','u'):
            if any(key in collations[table] for key in keys):
                l.require(all(collations[table].get(key) in (None,'splitsmart_email_ci') for key in keys), 'UNKNOWN_COLLATION')
                icu.append((table,keys,collations[table]))
            else:
                unique.setdefault(table,[]).append(keys)
        else:
            l.require(parent in payload, 'UNMAPPED_FOREIGN_KEY')
            foreign.append((table,keys,parent,tuple(json.loads(parent_keys))))
    # Unique expression/partial indexes could add conditions not represented by
    # pg_constraint. Fail closed rather than silently omitting them.
    l.require(pg.query("SELECT COUNT(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND (NOT i.indisvalid OR (i.indisunique AND NOT EXISTS (SELECT 1 FROM pg_constraint x WHERE x.conindid=i.indexrelid)))") == [('0',)], 'UNSUPPORTED_INDEX')
    return columns,unique,foreign,icu


def icu_check(pg, cleaned, keyspec, columns):
    for table,keys,collations in keyspec:
        rows = [row for row in cleaned[table] if all(row[k] is not None for k in keys)]
        if not rows:
            continue
        l.require(len(rows)*len(keys) <= 60000, 'ICU_VALIDATION_CAPACITY')
        params, expressions = [], []
        for row in rows:
            parts = []
            for key in keys:
                params.append(row[key])
                # request_id is text, despite its suffix. Use verified target
                # metadata rather than guessing a PostgreSQL type from a name.
                kind = columns[table][key].kind
                kind = kind if kind in ('bigint','integer','smallint') else 'text'
                expr = '$'+str(len(params))+'::'+kind
                if key in collations:
                    expr += ' COLLATE splitsmart_email_ci'
                parts.append(expr)
            expressions.append('('+','.join(parts)+')')
        aliases = ','.join('k'+str(i) for i in range(len(keys)))
        sql = 'WITH v('+aliases+') AS (VALUES '+','.join(expressions)+') SELECT COUNT(*) FROM (SELECT 1 FROM v GROUP BY '+aliases+' HAVING COUNT(*)>1) conflicts'
        l.require(pg.query(sql,params) == [('0',)], 'ICU_UNIQUE_CONFLICT')


def balances(data):
    """Exact per-group/currency/user ledger; never print or persist identities."""
    result = {}
    expenses = {row['id']:row for row in data['expenses']}
    def add(group,currency,user,amount):
        key = (group,currency,user)
        result[key] = result.get(key,Decimal(0)) + amount
    for row in expenses.values():
        add(row['group_id'],row['currency_code'],row['paid_by_user_id'],Decimal(row['amount']))
    for row in data['expense_splits']:
        expense = expenses[row['expense_id']]
        add(expense['group_id'],expense['currency_code'],row['user_id'],-Decimal(row['amount']))
    # Settlements have no currency column; compare a separate group/user ledger.
    for row in data['settlements']:
        add(row['group_id'],'SETTLEMENT',row['from_user_id'],Decimal(row['amount']))
        add(row['group_id'],'SETTLEMENT',row['to_user_id'],-Decimal(row['amount']))
    totals = {}
    for (group,currency,_),amount in result.items():
        totals[(group,currency)] = totals.get((group,currency),Decimal(0))+amount
    l.require(all(x == 0 for x in totals.values()), 'LEDGER_CONSERVATION')
    return result


SOURCE_NAMES = {}


def run(resume_preparation=False):
    previous=json.loads((RUN/'result.json').read_text()) if (RUN/'result.json').exists() else None
    if resume_preparation:
        l.require(previous and previous.get('real_data_attempts')==0 and
                  previous.get('error_code')=='PG_SQL_42501', 'NO_SAFE_PREPARATION_RESUME')
    else:
        l.require(previous is None, 'REHEARSAL_ALREADY_RECORDED_NO_RETRY')
    receipt = preflight()
    l.private_directory(RUN)
    report = previous or {'status':'PREFLIGHT','real_data_attempts':0,'cloud_accessed':False,
              'encryption_evidence':'existing ProtectionOn receipt plus owner-verified recovery',
              'source_receipt':str(RECEIPT)}
    def save():
        (RUN/'result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    save()
    admin = pg = None
    try:
        admin = Pg('ss_pg_owner',unprotect(CLUSTER/'owner-credential.dpapi'),'postgres')
        guard(admin,'postgres');quiet(admin);safe_policy(admin)
        stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ').lower()
        db, role = (report['database'],report['role']) if resume_preparation else ('ss_pg_local_real_'+stamp, 'ss_local_migrate_'+stamp)
        l.require(re.fullmatch('[a-z0-9_]+',db) and re.fullmatch('[a-z0-9_]+',role), 'IDENTIFIER')
        if resume_preparation:
            l.require(admin.query('SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=$1',[db])==[(role,)],'PREPARATION_OWNER')
            password=unprotect(RUN/'credential.dpapi')
        else:
            l.require(not admin.query('SELECT 1 FROM pg_database WHERE datname=$1',[db]) and
                      not admin.query('SELECT 1 FROM pg_roles WHERE rolname=$1',[role]), 'DESTINATION_OCCUPIED')
            password = secrets.token_urlsafe(36).encode()
            (RUN/'credential.dpapi').write_bytes(l.protect(password))
        report.update(database=db,role=role);save()
        if not resume_preparation:
            guard(admin,'postgres')
            admin.query('CREATE ROLE '+role+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+password.decode()+"'")
            guard(admin,'postgres');admin.query('CREATE DATABASE '+db+' OWNER '+role+' TEMPLATE template0 ENCODING \'UTF8\'')
            guard(admin,'postgres');admin.query('REVOKE ALL ON DATABASE '+db+' FROM PUBLIC')
            for setting,value in [('log_min_messages','panic'),('log_min_error_statement','panic'),('log_statement','none'),('log_min_duration_statement','-1'),('log_min_duration_sample','-1'),('log_transaction_sample_rate','0'),('log_parameter_max_length','0'),('log_parameter_max_length_on_error','0')]:
                guard(admin,'postgres')
                admin.query('ALTER ROLE '+role+' IN DATABASE '+db+' SET '+setting+" = '"+value+"'")
        admin.close();admin=None
        pg=Pg(role,password,db);guard(pg,db);safe_policy(pg)
        l.require(pg.query("SELECT COUNT(*) FROM pg_tables WHERE schemaname='public'")==[('0',)],'PREPARATION_MUST_BE_EMPTY')
        guard(pg,db);pg.query('REVOKE CREATE ON SCHEMA public FROM PUBLIC')
        # Synthetic primary-error probe precedes real values and schema migrations.
        marker='SYNTHETIC_LOCAL_PRIVACY_'+secrets.token_hex(16)
        try:
            pg.query('SELECT CAST($1 AS integer)',[marker])
            raise l.Stop('SYNTHETIC_ERROR_EXPECTED')
        except l.Stop as error:
            l.require(str(error)=='PG_SQL_22P02','SYNTHETIC_ERROR_CLASS')
        log=CLUSTER/'server.log'
        l.require(marker.encode() not in log.read_bytes(), 'LOCAL_PRIMARY_ERROR_LOG_LEAK')
        report['synthetic_primary_error_privacy']='PASS';save()
        pg.close();pg=None
        # Helper asserts the literal local host/port and ss_pg_ database prefix.
        check=Pg(role,password,db);guard(check,db);safe_policy(check);check.close()
        maven(RUN,password,role,db,target=11)
        pg=Pg(role,password,db);guard(pg,db);safe_policy(pg)
        l.require(pg.query('SELECT version,success FROM flyway_schema_history ORDER BY installed_rank') == [(str(i),'t') for i in range(1,12)], 'TARGET_FLYWAY')
        for (table,) in pg.query("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'"):
            l.require(re.fullmatch('[a-z_]+',table) is not None, 'TABLE_IDENTIFIER')
            l.require(pg.query('SELECT COUNT(*) FROM '+table)==[('0',)], 'TARGET_NOT_EMPTY')
        guard(pg,db)
        pg.query('CREATE TABLE legacy_mysql_flyway_history (installed_rank integer PRIMARY KEY,version varchar(50),description varchar(200) NOT NULL,type varchar(20) NOT NULL,script varchar(1000) NOT NULL,checksum integer,installed_by varchar(100) NOT NULL,installed_on timestamp(6) NOT NULL,execution_time integer NOT NULL,success boolean NOT NULL)')
        # Compare fake case/accent variants using this server's actual ICU,
        # before reading records or allowing a real-data writer.
        probe_columns={'users':{'email':Column('character varying',length=255)}}
        for pair in [('SYNTHETIC@example.invalid','synthetic@example.invalid'),
                     ('synthetic-é@example.invalid','synthetic-e@example.invalid')]:
            try:
                icu_check(pg,{'users':[{'email':v} for v in pair]},
                          [('users',('email',),{'email':'splitsmart_email_ci'})],probe_columns)
                raise l.Stop('SYNTHETIC_ICU_CONFLICT_EXPECTED')
            except l.Stop as error:
                l.require(str(error)=='ICU_UNIQUE_CONFLICT','SYNTHETIC_ICU_GATE')
        report['synthetic_icu_conflicts']='PASS';save()
        payload, originals, floors=source_payload(receipt)
        SOURCE_NAMES.update({t:originals[t][0] for t in payload})
        columns,unique,foreign,icu=metadata(pg,payload)
        cleaned=validate(payload,columns,unique,foreign,floors,True)
        icu_check(pg,cleaned,icu,columns)
        ledger=balances(cleaned)
        report.update(prevalidation='PASS',icu=pg.query("SELECT collversion,pg_collation_actual_version(oid) FROM pg_collation WHERE collname='splitsmart_email_ci'")[0],schema='V1-V11 PASS',source_checksum=receipt['sha256'])
        save();print('LOCAL_PREVALIDATION_AND_PRIVACY_PASS',flush=True)
        # Mark sole real-data attempt BEFORE BEGIN/first INSERT. Never retry.
        guard(pg,db);safe_policy(pg)
        report.update(real_data_attempts=1,status='IMPORTING');save()
        pg.query('BEGIN')
        for table,rows in cleaned.items():
            names=SOURCE_NAMES[table]
            sql='INSERT INTO '+table+' ('+','.join(names)+') VALUES ('+','.join('$'+str(i+1) for i in range(len(names)))+')'
            for row in rows:
                pg.query(sql,[row[name] for name in names])
        counts,hashes={},{}
        restored_payload={}
        for table,(names,kinds,rows) in originals.items():
            target=pg.query('SELECT '+','.join(names)+' FROM '+table+' ORDER BY '+('installed_rank' if table=='legacy_mysql_flyway_history' else 'id'))
            l.require(len(target)==len(rows) and digest(target,kinds)==digest(rows,kinds),'COLUMN_HASH_RECONCILIATION')
            counts[table]=len(target);hashes[table]=digest(target,kinds)
            restored_payload[table]=[dict(zip(names,row)) for row in target]
        l.require(balances(restored_payload)==ledger,'BALANCE_RECONCILIATION')
        for table in ('expenses','expense_splits','settlements'):
            expected=sum((Decimal(row['amount']) for row in cleaned[table]),Decimal(0))
            l.require(Decimal(pg.query('SELECT COALESCE(SUM(amount),0) FROM '+table)[0][0])==expected,'FINANCIAL_TOTALS')
        l.require(pg.query('SELECT COUNT(*) FROM users WHERE auth_token_version<>0 OR email_verified_at IS NOT NULL OR password_reset_requested_at IS NOT NULL')==[('0',)],'LEGACY_AUTH_DEFAULTS')
        for table in ('password_reset_tokens','group_invite_links','email_verification_tokens','notifications'):
            l.require(pg.query('SELECT COUNT(*) FROM '+table)==[('0',)],'EXTERNAL_SIDE_EFFECT_TABLES')
        for table,floor in floors.items():
            pg.query("SELECT setval(pg_get_serial_sequence($1,'id'),$2,false)",[table,floor])
            sequence=pg.query("SELECT pg_get_serial_sequence($1,'id')",[table])[0][0]
            l.require(re.fullmatch(r'public\.[a-z_]+',sequence) is not None,'SEQUENCE_IDENTIFIER')
            l.require(pg.query('SELECT last_value,is_called FROM '+sequence)==[(str(floor),'f')],'SEQUENCE_FLOOR')
        safe_policy(pg)
        l.require(marker.encode() not in log.read_bytes(),'FINAL_SYNTHETIC_LOG_SCAN')
        # Scan credential bytes only; no production values printed or copied.
        l.require(password not in log.read_bytes(),'CREDENTIAL_LOG_SCAN')
        pg.query('COMMIT')
        # Verify durable visibility through a NEW authenticated local session.
        pg.close();pg=Pg(role,password,db);guard(pg,db);safe_policy(pg)
        for table,(names,kinds,_) in originals.items():
            target=pg.query('SELECT '+','.join(names)+' FROM '+table+' ORDER BY '+('installed_rank' if table=='legacy_mysql_flyway_history' else 'id'))
            l.require(len(target)==counts[table] and digest(target,kinds)==hashes[table], 'COMMITTED_RECONCILIATION')
        report.update(status='PASS',row_counts=counts,column_hashes=hashes,financial='PASS',
            balances='PASS',constraints='PASS',identity_floors='PASS',legacy_history='V1-V7 preserved separately',
            active_history='V1-V11',no_application_started=True,cloud_compatibility='UNVERIFIED_ICU_AND_LOGGING')
        save();print('ONE_LOCAL_REAL_DATA_REHEARSAL_PASS',flush=True)
    except BaseException as error:
        if pg:
            try:pg.query('ROLLBACK')
            except BaseException:pass
        report.update(status='BLOCKED',error_code=str(error) if isinstance(error,(l.Stop,ValidationError)) else type(error).__name__)
        save();print('STOP_'+report['error_code'],flush=True)
        return 1
    finally:
        if pg:pg.close()
        if admin:admin.close()
    private_acl(RUN)
    return 0


if __name__=='__main__':
    import sys
    try:
        if sys.argv[1:] == ['--preflight']:
            preflight();print('LOCAL_SECURITY_PREFLIGHT_PASS')
        elif sys.argv[1:] == ['--run-once']:
            sys.exit(run())
        elif sys.argv[1:] == ['--resume-empty-preparation']:
            sys.exit(run(resume_preparation=True))
        else:
            raise l.Stop('EXPLICIT_LOCAL_MODE_REQUIRED')
    except BaseException as error:
        if isinstance(error,SystemExit):raise
        print('STOP_'+(str(error) if isinstance(error,(l.Stop,ValidationError)) else type(error).__name__))
        sys.exit(1)
