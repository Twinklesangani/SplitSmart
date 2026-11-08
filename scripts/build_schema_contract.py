"""One new EMPTY local synthetic V11 fixture; generates metadata-only contract."""
import datetime as dt
import json
import os
from pathlib import Path
import local_pg_rehearsal as l
from postgres_compatibility import unprotect,maven
import schema_contract as contract


def main():
    root=l.l.BASE/'SchemaContractReference'
    l.l.require(not root.exists() and not contract.CONTRACT.exists(),'REFERENCE_ALREADY_EXISTS_NO_REPLAY')
    l.l.private_directory(root)
    prior=l.l.BASE/'AutonomousSyntheticTransfer'
    owner=json.loads((prior/'identity.json').read_text(encoding='utf-8'))
    l.l.require(owner['synthetic_only'] is True,'SYNTHETIC_ROLE')
    password=unprotect(prior/'credential.dpapi')
    db='ss_pg_transfer_synthetic_contract_'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')
    admin=l.Pg('ss_pg_owner',unprotect(l.CLUSTER/'owner-credential.dpapi'),'postgres')
    l.guard(admin,'postgres');l.quiet(admin);l.safe_policy(admin)
    l.l.require(not admin.query('SELECT 1 FROM pg_database WHERE datname=$1',[db]),'DATABASE_EXISTS')
    l.guard(admin,'postgres');admin.query('CREATE DATABASE '+db+' OWNER '+owner['role']+' TEMPLATE template0')
    l.guard(admin,'postgres');admin.query('REVOKE ALL ON DATABASE '+db+' FROM PUBLIC')
    settings=admin.query("SELECT name,setting FROM pg_settings WHERE name IN ('log_min_messages','log_min_error_statement','log_statement','log_min_duration_statement','log_min_duration_sample','log_transaction_sample_rate','log_parameter_max_length','log_parameter_max_length_on_error')")
    for name,value in settings:
        l.guard(admin,'postgres');admin.query('ALTER ROLE '+owner['role']+' IN DATABASE '+db+' SET '+name+"='"+value+"'")
    admin.close()
    (root/'identity.json').write_text(json.dumps({'database':db,'role':owner['role'],'synthetic_only':True}))
    os.environ['JAVA_HOME']=str(Path.home()/'.jdks/temurin-21')
    maven(root,password,owner['role'],db,target=11)
    c=l.Pg(owner['role'],password,db);l.guard(c,db);l.safe_policy(c)
    for (table,) in c.query("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'"):
        l.l.require(c.query('SELECT COUNT(*) FROM public.'+table)==[('0',)],'REFERENCE_NOT_EMPTY')
    migration_dir=l.WORK/'backend/src/main/resources/db/postgresql'
    value={'format':1,'schema':'PostgreSQL V1-V11','migration_sha256':contract.migration_hashes(migration_dir),'fingerprints':contract.capture(c)}
    c.close()
    contract.CONTRACT.parent.mkdir(exist_ok=True)
    contract.CONTRACT.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
    print('FRESH_EMPTY_V11_SCHEMA_CONTRACT_CREATED_NO_RECORDS')


if __name__=='__main__':
    import sys
    try:main()
    except Exception as error:print('REFERENCE_BLOCKED_'+type(error).__name__);sys.exit(1)
