"""New local synthetic-only V11 database; never opens MySQL or cloud clients."""
import datetime as dt
import json
import secrets
from pathlib import Path
import local_pg_rehearsal as local
from postgres_compatibility import unprotect,maven
from migration_transfer import Target,transfer,checksums


def main():
    root=local.l.BASE/'AutonomousSyntheticTransfer'
    local.l.require(not root.exists(),'SYNTHETIC_RUN_ALREADY_EXISTS')
    local.private_acl(local.CLUSTER)
    local.l.private_directory(root)
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ').lower()
    db='ss_pg_transfer_synthetic_'+stamp
    role='ss_transfer_'+stamp
    password=secrets.token_urlsafe(36).encode()
    admin=local.Pg('ss_pg_owner',unprotect(local.CLUSTER/'owner-credential.dpapi'),'postgres')
    local.guard(admin,'postgres');local.quiet(admin);local.safe_policy(admin)
    local.l.require(not admin.query('SELECT 1 FROM pg_database WHERE datname=$1',[db]),'DATABASE_EXISTS')
    local.l.require(not admin.query('SELECT 1 FROM pg_roles WHERE rolname=$1',[role]),'ROLE_EXISTS')
    local.guard(admin,'postgres')
    admin.query('CREATE ROLE '+role+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+password.decode()+"'")
    local.guard(admin,'postgres');admin.query('CREATE DATABASE '+db+' OWNER '+role+' TEMPLATE template0')
    local.guard(admin,'postgres');admin.query('REVOKE ALL ON DATABASE '+db+' FROM PUBLIC')
    settings=admin.query("SELECT name,setting FROM pg_settings WHERE name IN ('log_min_messages','log_min_error_statement','log_statement','log_min_duration_statement','log_min_duration_sample','log_transaction_sample_rate','log_parameter_max_length','log_parameter_max_length_on_error')")
    for setting,value in settings:
        local.guard(admin,'postgres')
        admin.query('ALTER ROLE '+role+' IN DATABASE '+db+' SET '+setting+"='"+value+"'")
    admin.close()
    (root/'credential.dpapi').write_bytes(local.l.protect(password))
    (root/'identity.json').write_text(json.dumps({'database':db,'role':role,'synthetic_only':True}))
    def connect():
        p=local.Pg(role,password,db);local.guard(p,db);local.safe_policy(p);return p
    # Existing Flyway harness itself validates the literal local host/port/prefix.
    maven(root,password,role,db,target=11)
    pg=connect()
    schema=local.WORK/'backend/src/main/resources/db/postgresql'
    local.l.require(pg.query('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank')==checksums(schema),'CHECKSUM_ALGORITHM')
    when='2026-11-08 00:00:00.000000'
    records={
      'users':[dict(id=str(i),name='Synthetic '+str(i),email='synthetic'+str(i)+'@example.invalid',password_hash='synthetic-no-login',created_at=when) for i in (1,2)],
      'expense_groups':[dict(id='1',name='Synthetic group',created_by='1',created_at=when,archived='0')],
      'group_members':[dict(id=str(i),group_id='1',user_id=str(i)) for i in (1,2)],
      'expenses':[dict(id='1',group_id='1',description='Synthetic expense',amount='50.00',currency_code='INR',paid_by_user_id='1',created_by_user_id='1',created_at=when,expense_date='2026-11-08',category='OTHER')],
      'expense_splits':[dict(id=str(i),expense_id='1',user_id=str(i),amount='25.00') for i in (1,2)],
      'settlements':[dict(id='1',group_id='1',from_user_id='2',to_user_id='1',amount='10.00',recorded_by_user_id='1',request_id='11111111-1111-4111-8111-111111111111',note=None,created_at=when)],
      'expense_creation_operations':[dict(id='1',group_id='1',user_id='1',operation_key='22222222-2222-4222-8222-222222222222',fingerprint='a'*64,response_json='{"synthetic":true}')],
      'group_invitations':[dict(id='1',group_id='1',email='synthetic3@example.invalid',expires_at='2026-11-09 00:00:00+00:00',status='PENDING',active_slot='A')],
      'legacy_mysql_flyway_history':[dict(installed_rank=str(i+1),version=str(i+1),description='Synthetic migration',type='SQL',script='V'+str(i+1)+'__synthetic.sql',checksum=str(c),installed_by='fixture',installed_on=when,execution_time='0',success='1') for i,c in enumerate(local.l.CHECKSUMS)]
    }
    originals={}
    for table,rows in records.items():
        source='flyway_schema_history' if table=='legacy_mysql_flyway_history' else table
        meta=pg.query("SELECT column_name,data_type FROM information_schema.columns WHERE table_schema='public' AND table_name=$1 ORDER BY ordinal_position",[source])
        fields=[name for name,_ in meta if name not in local.ADDITIONS.get(table,set())]
        kinds=[kind for name,kind in meta if name in fields]
        local.l.require(all(set(row)==set(fields) for row in rows),'FIXTURE_MAPPING')
        originals[table]=(fields,kinds,[tuple(row[f] for f in fields) for row in rows])
    pg.close()
    floors={t:max(int(r['id']) for r in rows)+1 for t,rows in records.items() if t!='legacy_mysql_flyway_history'}
    result=transfer(connect,Target(db,role,25432),schema,originals,floors,root/'attempt.json','SYNTHETIC')
    (root/'result.json').write_text(json.dumps(result,indent=2))
    print('LOCAL_SYNTHETIC_NINE_TABLE_TRANSFER_AND_RECONCILIATION_PASS')


if __name__=='__main__':
    import sys
    try:main()
    except Exception as error:
        print('STOP_'+type(error).__name__)
        sys.exit(1)
