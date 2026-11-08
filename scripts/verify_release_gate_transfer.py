"""Fresh synthetic clone of the EMPTY reference; never reads recovery records."""
import datetime as dt
import json
from pathlib import Path
import local_pg_rehearsal as l
from postgres_compatibility import unprotect
from migration_transfer import Target,identifier,metadata_preflight,transfer
from verify_transfer_synthetic import synthetic_records


def main():
    root=l.l.BASE/'ReleaseGateSyntheticTransfer'
    l.l.require(not root.exists(),'SYNTHETIC_ATTEMPT_ALREADY_EXISTS')
    ref=json.loads((l.l.BASE/'SchemaContractReference/identity.json').read_text(encoding='utf-8'))
    owner=json.loads((l.l.BASE/'AutonomousSyntheticTransfer/identity.json').read_text(encoding='utf-8'))
    l.l.require(ref['synthetic_only'] is True and owner['synthetic_only'] is True and ref['role']==owner['role'],'SYNTHETIC_IDENTITIES')
    password=unprotect(l.l.BASE/'AutonomousSyntheticTransfer/credential.dpapi')
    schema=l.WORK/'backend/src/main/resources/db/postgresql'
    admin=l.Pg('ss_pg_owner',unprotect(l.CLUSTER/'owner-credential.dpapi'),'postgres')
    l.guard(admin,'postgres');l.quiet(admin);l.safe_policy(admin)
    source=l.Pg(ref['role'],password,ref['database']);l.guard(source,ref['database']);l.safe_policy(source)
    metadata_preflight(source,Target(ref['database'],ref['role'],25432),schema);source.close()
    db='ss_pg_transfer_synthetic_gate_'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')
    l.l.require(not admin.query('SELECT 1 FROM pg_database WHERE datname=$1',[db]),'DATABASE_EXISTS')
    l.l.private_directory(root)
    (root/'identity.json').write_text(json.dumps({'database':db,'role':ref['role'],'synthetic_only':True}))
    l.guard(admin,'postgres');admin.query('CREATE DATABASE '+identifier(db)+' OWNER '+identifier(ref['role'])+' TEMPLATE '+identifier(ref['database']))
    l.guard(admin,'postgres');admin.query('REVOKE ALL ON DATABASE '+identifier(db)+' FROM PUBLIC')
    settings=admin.query("SELECT name,setting FROM pg_settings WHERE name IN ('log_min_messages','log_min_error_statement','log_statement','log_min_duration_statement','log_min_duration_sample','log_transaction_sample_rate','log_parameter_max_length','log_parameter_max_length_on_error')")
    for name,value in settings:
        l.guard(admin,'postgres');admin.query('ALTER ROLE '+identifier(ref['role'])+' IN DATABASE '+identifier(db)+' SET '+name+"='"+value+"'")
    admin.close()
    def connect():
        c=l.Pg(ref['role'],password,db);l.guard(c,db);l.safe_policy(c);return c
    c=connect();records=synthetic_records();originals={}
    for table,rows in records.items():
        catalog='flyway_schema_history' if table=='legacy_mysql_flyway_history' else table
        meta=c.query("SELECT column_name,data_type FROM information_schema.columns WHERE table_schema='public' AND table_name=$1 ORDER BY ordinal_position",[catalog])
        fields=[name for name,_ in meta if name not in l.ADDITIONS.get(table,set())]
        kinds=[kind for name,kind in meta if name in fields]
        l.l.require(all(set(row)==set(fields) for row in rows),'FIXTURE_FIELDS')
        originals[table]=(fields,kinds,[tuple(row[f] for f in fields) for row in rows])
    c.close()
    floors={t:max(int(row['id']) for row in rows)+1 for t,rows in records.items() if t!='legacy_mysql_flyway_history'}
    result=transfer(connect,Target(db,ref['role'],25432),schema,originals,floors,root/'attempt.json','SYNTHETIC')
    (root/'result.json').write_text(json.dumps(result,indent=2))
    print('SCHEMA_CONTRACT_AND_NINE_TABLE_SYNTHETIC_TRANSFER_PASS')


if __name__=='__main__':
    import sys
    try:main()
    except Exception as error:print('SYNTHETIC_GATE_BLOCKED_'+type(error).__name__);sys.exit(1)
