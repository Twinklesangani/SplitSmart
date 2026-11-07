"""Approved target-only roles/schema/tests. Never opens a MySQL connection."""
import datetime as dt
import json
import os
from pathlib import Path
import re
import secrets
import subprocess as S
import sys
import aiven_postgres_preflight as target
import local_database_support as local
from postgres_compatibility import unprotect
from pg_target_connection import connect

ROOT=local.BASE/'AivenPostgresPreparation'
WORK=Path(__file__).resolve().parents[1]
CA=target.ROOT/'project-ca.pem'
APP='splitsmart'
ROLES={'app_migration':'ss_migrator','app_runtime':'ss_runtime',
       'test_migration':'ss_test_migrator','test_runtime':'ss_test_runtime'}
BUSINESS=['users','expense_groups','group_members','expenses','expense_splits','settlements',
          'expense_creation_operations','group_invitations','password_reset_tokens',
          'group_invite_links','email_verification_tokens','notifications']
RIGHTS={'users':'SELECT,INSERT,UPDATE','expense_groups':'SELECT,INSERT,UPDATE',
        'group_members':'SELECT,INSERT,DELETE','expenses':'SELECT,INSERT,UPDATE,DELETE',
        'expense_splits':'SELECT,INSERT,DELETE','settlements':'SELECT,INSERT',
        'expense_creation_operations':'SELECT,INSERT,UPDATE',
        'group_invitations':'SELECT,INSERT,UPDATE','password_reset_tokens':'SELECT,INSERT,UPDATE',
        'group_invite_links':'SELECT,INSERT,UPDATE','email_verification_tokens':'SELECT,INSERT,UPDATE',
        'notifications':'SELECT,INSERT,UPDATE,DELETE'}

def params():
    p=json.loads(unprotect(target.ROOT/'connection.dpapi'))
    current=target.client().get_service(target.PROJECT,target.NAME)
    local.require(current['state']=='RUNNING' and current['plan']=='free-1-1gb' and current['service_type']=='pg','TARGET_NOT_FREE_RUNNING')
    meta=current['service_uri_params']
    local.require(p['host']==meta['host'] and str(p['port'])==str(meta['port']),'TARGET_ENDPOINT_MISMATCH')
    return p

def credentials():return json.loads(unprotect(ROOT/'roles.dpapi'))
def state():return json.loads((ROOT/'preparation.json').read_text(encoding='utf-8'))
def save(s):(ROOT/'preparation.json').write_text(json.dumps(s,indent=2),encoding='utf-8')
def private_roles(c):(ROOT/'roles.dpapi').write_bytes(local.protect(json.dumps(c).encode()))

def connection(p,kind,database):
    return connect(p,database=database,user=ROLES[kind],password=credentials()[ROLES[kind]],ca=CA)

def guard(pg,s,synthetic):
    expected=s['test_database'] if synthetic else APP
    local.require(pg.query('SELECT current_database()')==[(expected,)],'DATABASE_IDENTITY')
    if synthetic:
        local.require(expected.startswith('splitsmart_synthetic_') and expected!=APP,'SYNTHETIC_SCOPE')
        local.require(pg.query('SELECT nonce FROM ss_verification_guard.identity')==[(s['nonce'],)],'SYNTHETIC_NONCE')

def maven(s,kind,tests):
    c=credentials(); env=os.environ.copy()
    schema=tests=='TargetPostgresSchemaIT'
    env.update(PG_TARGET_MANIFEST=str(ROOT/'manifest.json'),PG_TARGET_CA=str(CA),
        PG_TARGET_TEST_URL=s['test_url'],PG_TARGET_MIGRATION_URL=s['application_url'] if kind=='application' else s['test_url'],
        PG_TARGET_SCHEMA_KIND=kind,PG_TARGET_MIGRATION_USER=ROLES['app_migration' if kind=='application' else 'test_migration'],
        PG_TARGET_MIGRATION_PASSWORD=c[ROLES['app_migration' if kind=='application' else 'test_migration']],
        PG_TARGET_RUNTIME_USER=ROLES['test_runtime'],PG_TARGET_RUNTIME_PASSWORD=c[ROLES['test_runtime']],
        RECOVERY_EMAIL_ENABLED='false',RECOVERY_EMAIL_DELIVERY_VERIFIED='false')
    reportdir=ROOT/('reports-'+kind+('-schema' if schema else '-integration'))
    reportdir.mkdir(exist_ok=True)
    cmd="& './mvnw.cmd' '-q' '-Dtest="+tests+"' '-Dspring.test.context.cache.maxSize=1' '-Dsurefire.reportsDirectory="+str(reportdir)+"' 'test'"
    run=S.run([local.POWERSHELL,'-NoProfile','-NonInteractive','-Command',cmd],cwd=WORK/'backend',env=env,
              capture_output=True,timeout=600,creationflags=local.CREATE_NO_WINDOW)
    if run.returncode:
        (ROOT/'maven-failure.dpapi').write_bytes(local.protect(run.stdout+run.stderr))
        raise local.Stop('TARGET_SCHEMA_TEST_FAILED' if schema else 'TARGET_INTEGRATION_FAILED')

def setup():
    p=params();admin=connect(p,ca=CA)
    local.private_directory(ROOT)
    if (ROOT/'preparation.json').exists():
        s=state();local.require(s['service']==target.NAME,'OWNERSHIP_MARKER')
    else:
        # Never adopt/alter pre-existing names without a recorded ownership receipt.
        existing={x[0] for x in admin.query('SELECT rolname FROM pg_roles')}
        local.require(not existing.intersection(ROLES.values()),'ROLE_NAME_ALREADY_OCCUPIED')
        local.require(not admin.query("SELECT 1 FROM pg_database WHERE datname='splitsmart'"),'APPLICATION_DATABASE_ALREADY_EXISTS')
        stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ').lower()
        testdb='splitsmart_synthetic_'+stamp
        local.require(not admin.query('SELECT 1 FROM pg_database WHERE datname=$1',[testdb]),'TEST_DATABASE_EXISTS')
        s={'service':target.NAME,'project':target.PROJECT,'application_database':APP,'test_database':testdb,
           'created_roles':[],'created_databases':[],'nonce':secrets.token_hex(24),'status':'PREPARING',
           'production_data_uploaded':False,'mysql_contacted':False}
        save(s);private_roles({})
    c=credentials();cli=target.client()
    for kind,name in ROLES.items():
        if name not in s['created_roles']:
            local.require(not admin.query('SELECT 1 FROM pg_roles WHERE rolname=$1',[name]),'UNOWNED_EXISTING_ROLE')
            u=cli.create_service_user(target.PROJECT,target.NAME,name)
            if target.placeholder(u.get('password')):
                u=cli.verify(cli.get,cli.build_path('project',target.PROJECT,'service',target.NAME,'user',name),
                             params={'include_secrets':'true'},result_key='user')
            local.require(not target.placeholder(u.get('password')),'FRESH_ROLE_SECRET_UNAVAILABLE')
            c[name]=u['password'];private_roles(c);s['created_roles'].append(name);save(s)
        flags=admin.query('SELECT rolsuper,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=$1',[name])[0]
        local.require(flags==('f','f','f'),'NEW_ROLE_EXCESSIVE_PRIVILEGES')
        # No password appears in SQL. Only our four new roles may be altered.
        limit=8 if kind=='test_runtime' else 5 if kind=='app_runtime' else 2
        admin.query('ALTER ROLE '+name+' NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT '+str(limit))
        memberships=admin.query('SELECT r.rolname FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid JOIN pg_roles u ON u.oid=m.member WHERE u.rolname=$1',[name])
        for (memberof,) in memberships:
            local.require(re.fullmatch('[a-zA-Z0-9_]+',memberof),'MEMBERSHIP_NAME')
            admin.query('REVOKE '+memberof+' FROM '+name)
        admin.query('ALTER ROLE '+name+" SET timezone='UTC'")
        admin.query('ALTER ROLE '+name+" SET search_path=public")
        admin.query('ALTER ROLE '+name+" SET statement_timeout='60s'")
        admin.query('ALTER ROLE '+name+" SET idle_in_transaction_session_timeout='60s'")
    for database,migr,runtime in [(APP,ROLES['app_migration'],ROLES['app_runtime']),
                                  (s['test_database'],ROLES['test_migration'],ROLES['test_runtime'])]:
        if database not in s['created_databases']:
            local.require(not admin.query('SELECT 1 FROM pg_database WHERE datname=$1',[database]),'UNOWNED_DATABASE')
            # Keep provider admin attributes/memberships untouched. Migration
            # roles manage public-schema objects; they need no database ownership.
            admin.query('CREATE DATABASE '+database+" TEMPLATE template0 ENCODING 'UTF8'")
            s['created_databases'].append(database);save(s)
        admin.query('REVOKE ALL ON DATABASE '+database+' FROM PUBLIC')
        admin.query('GRANT CONNECT ON DATABASE '+database+' TO '+migr+','+runtime)
        db=connect(p,database=database,ca=CA)
        db.query('REVOKE ALL ON SCHEMA public FROM PUBLIC')
        db.query('GRANT USAGE,CREATE ON SCHEMA public TO '+migr)
        db.query('GRANT USAGE ON SCHEMA public TO '+runtime)
        if database==s['test_database'] and not s.get('test_guard_created'):
            db.query('CREATE SCHEMA ss_verification_guard')
            db.query('CREATE TABLE ss_verification_guard.identity(nonce text PRIMARY KEY)')
            db.query('INSERT INTO ss_verification_guard.identity VALUES($1)',[s['nonce']])
            db.query('GRANT USAGE ON SCHEMA ss_verification_guard TO '+migr)
            db.query('GRANT SELECT ON ss_verification_guard.identity TO '+migr)
            s['test_guard_created']=True;save(s)
        db.close()
    admin.close()
    s.update(application_url='jdbc:postgresql://'+p['host']+':'+str(p['port'])+'/'+APP,
             test_url='jdbc:postgresql://'+p['host']+':'+str(p['port'])+'/'+s['test_database'])
    (ROOT/'manifest.json').write_text(json.dumps({k:s[k] for k in ['service','project','test_database','application_url','test_url']},indent=2))
    save(s);print('TARGET_ROLES_AND_DATABASES_CREATED',flush=True)
    for kind,database in [('application',APP),('test',s['test_database'])]:
        mig=connection(p,'app_migration' if kind=='application' else 'test_migration',database)
        if kind=='test':guard(mig,s,True)
        else:guard(mig,s,False)
        if not s.get(kind+'_schema'):
            local.require(mig.query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")==[('0',)],'SCHEMA_NOT_EMPTY')
            mig.close();maven(s,kind,'TargetPostgresSchemaIT')
            s[kind+'_schema']='PASS';save(s)
            mig=connection(p,'app_migration' if kind=='application' else 'test_migration',database)
        runtime=ROLES['app_runtime' if kind=='application' else 'test_runtime']
        names={x[0] for x in mig.query("SELECT tablename FROM pg_tables WHERE schemaname='public'")}
        local.require(names==set(BUSINESS+['flyway_schema_history']),'TABLE_MANIFEST')
        for table in BUSINESS:
            rights=RIGHTS[table] if kind=='application' else 'SELECT,INSERT,UPDATE,DELETE'
            mig.query('GRANT '+rights+' ON public.'+table+' TO '+runtime)
        # Only business identity sequences; no UPDATE/setval or ownership.
        for (seq,) in mig.query("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='S' AND n.nspname='public'"):
            mig.query('GRANT USAGE ON SEQUENCE public.'+seq+' TO '+runtime)
        mig.close()
        rt=connection(p,'app_runtime' if kind=='application' else 'test_runtime',database)
        guard(rt,s,False) if kind=='application' else local.require(rt.query('SELECT current_database()')==[(s['test_database'],)],'TEST_RUNTIME_IDENTITY')
        local.require(rt.query("SELECT has_schema_privilege('public','CREATE'),has_database_privilege(current_database(),'CREATE'),has_table_privilege('public.flyway_schema_history','SELECT'),has_table_privilege('public.flyway_schema_history','UPDATE')")==[('f','f','f','f')],'RUNTIME_DDL_OR_HISTORY_ACCESS')
        local.require(rt.query('SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=current_user')==[('f','f','f','f','f')],'RUNTIME_ROLE_FLAGS')
        rt.close()
    check=connect(p,ca=CA)
    local.require(check.query('SELECT has_database_privilege($1,$2,\'CONNECT\'),has_database_privilege($3,$4,\'CONNECT\')',
        [ROLES['app_runtime'],s['test_database'],ROLES['test_runtime'],APP])==[('f','f')],'CROSS_DATABASE_ACCESS')
    check.close();s.update(privileges='PASS',status='SCHEMAS_READY');save(s)
    print('FLYWAY_AND_MINIMUM_PRIVILEGES_PASS',flush=True)

def run_tests():
    s=state();local.require(s.get('privileges')=='PASS','PRIVILEGE_GATE')
    p=params();check=connection(p,'test_migration',s['test_database']);guard(check,s,True);check.close()
    maven(s,'test','TargetPostgresRecoveryIT,TargetPostgresInvitationIT,TargetPostgresNotificationIT,TargetPostgresFinancialIT')
    s.update(integration='PASS',status='TESTED');save(s);print('TARGET_SYNTHETIC_INTEGRATION_PASS')

if __name__=='__main__':
    try:
        if '--setup' in sys.argv:setup()
        elif '--tests' in sys.argv:run_tests()
        else:raise local.Stop('EXPLICIT_PHASE_REQUIRED')
    except Exception as ex:
        code=str(ex) if isinstance(ex,local.Stop) else type(ex).__name__
        print('STOP_'+code);sys.exit(1)
