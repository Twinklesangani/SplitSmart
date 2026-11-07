"""Explicitly approved Free-only provisioning; subsequent SQL is metadata-only.

No MySQL mutation, data import, Flyway execution, role creation or billing API.
Raw API responses and connection credentials are never printed or logged.
"""
import ctypes as C
import json
import logging
import re
import sys
import time
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlsplit,unquote
import local_database_support as local
from postgres_compatibility import Pg

sys.path.insert(0,str(Path.home()/'.local/share/splitsmart-tools/aiven-cli-4.18.0/Lib/site-packages'))
from aiven.client.client import AivenClient, RetrySpec, Error

PROJECT='twinklesangani-9b5e'
NAME='splitsmart-postgres'
PLAN='free-1-1gb'
ROOT=local.BASE/'AivenPostgresPreflight'

def placeholder(value):
    return not value or bool(re.fullmatch(r'\*+|x{4,}|<[^>]+>|\[[^]]+\]|hidden|redacted|password',str(value),re.I))

def client():
    logging.disable(logging.CRITICAL)
    cli=AivenClient('https://api.aiven.io',show_http=False,request_timeout=40,default_retry_spec=RetrySpec(attempts=1))
    credentials=json.loads((Path.home()/'.config/aiven/aiven-credentials.json').read_text())
    cli.set_auth_token(credentials['auth_token'])
    return cli

def prerequisites(cli):
    services=cli.get_services(PROJECT)
    mysql=[x for x in services if x['service_name']=='splitsmart-mysql']
    local.require(len(mysql)==1 and mysql[0]['service_type']=='mysql','MYSQL_INVENTORY')
    types=cli.get_service_types(PROJECT)
    free=[x for x in types['pg']['service_plans'] if x['service_plan']==PLAN]
    local.require(len(free)==1,'FREE_PLAN_UNAVAILABLE')
    region=free[0]['regions'].get('do-sgp')
    local.require(region and Decimal(region['price_usd'])==0,'FREE_SINGAPORE_UNAVAILABLE')
    versions=types['pg']['user_config_schema']['properties']['pg_version'].get('enum',[])
    return services,free[0],versions

def main():
    cli=client()
    try:
        services,plan,versions=prerequisites(cli)
        if '--check' in sys.argv:
            print(json.dumps({'authentication':'PASS','existing_mysql_unchanged_state':
                next(x['state'] for x in services if x['service_name']=='splitsmart-mysql'),
                'target_exists':any(x['service_name']==NAME for x in services),'free_singapore':
                plan['regions']['do-sgp'],'pg_versions':versions,'backup_plan_metadata':plan.get('backup_config')}))
            return 0
        local.private_directory(ROOT)
        if '--create-free-once' in sys.argv:
            local.require(not any(x['service_name']==NAME for x in services),'TARGET_ALREADY_EXISTS')
            local.require(not any(x['service_type']=='pg' and x.get('plan')==PLAN for x in services),'FREE_PG_ALREADY_PRESENT')
            local.require('16' in versions,'TESTED_MAJOR_UNAVAILABLE')
            marker=ROOT/'create-submitted.json'
            with marker.open('x') as f:
                json.dump({'project':PROJECT,'name':NAME,'plan':PLAN,'cloud_requested':'do-sgp',
                           'pg_version_requested':'16','price_usd_hour':'0.000','create_requests':1},f)
            service=cli.create_service(project=PROJECT,service=NAME,service_type='pg',plan=PLAN,
                cloud='do-sgp',user_config={'pg_version':'16'},project_vpc_id=None,
                termination_protection=True,service_integrations=[])
            local.require(service['service_name']==NAME and service['plan']==PLAN,'CREATED_PLAN_MISMATCH')
            print('ONE_FREE_SERVICE_CREATE_ACCEPTED')
        else:
            service=cli.get_service(PROJECT,NAME)
        if '--wait-running' in sys.argv:
            until=time.monotonic()+420
            while service['state']!='RUNNING' and time.monotonic()<until:
                time.sleep(30)
                service=cli.get_service(PROJECT,NAME)
        local.require(service['service_type']=='pg' and service['plan']==PLAN,'NOT_FREE_POSTGRES')
        region=service.get('cloud_name')
        local.require(region in plan['regions'] and Decimal(plan['regions'][region]['price_usd'])==0,'NONZERO_TARGET_PRICE')
        report={'service':NAME,'project':PROJECT,'plan':service['plan'],'state':service['state'],
                'cloud':region,'price_usd_hour':plan['regions'][region]['price_usd'],
                'resources':plan['regions'][region],'pg_version_config':service.get('user_config',{}).get('pg_version'),
                'service_version':service.get('service_version'),'termination_protection':service.get('termination_protection'),
                'backup_plan_metadata':plan.get('backup_config'),'data_uploaded':False,'flyway_executed':False,
                'mysql_modified':False,'billing_modified':False}
        # Persist only explicitly selected non-secret metadata, never service URIs.
        (ROOT/'target-preflight.json').write_text(json.dumps(report,indent=2))
        if service['state']!='RUNNING':
            print(json.dumps(report));return 0
        if '--sql-metadata' in sys.argv:
            params=dict(service.get('service_uri_params',{}))
            needed=['host','port','user','password','dbname']
            if not all(params.get(x) for x in needed) and service.get('service_uri'):
                uri=urlsplit(service['service_uri'])
                local.require(uri.scheme in ('postgres','postgresql'),'UNEXPECTED_CONNECTION_SCHEME')
                fallback={'host':uri.hostname,'port':uri.port,'user':unquote(uri.username or ''),
                          'password':unquote(uri.password or ''),'dbname':uri.path.lstrip('/')}
                for k in needed:
                    if not params.get(k):params[k]=fallback[k]
            if placeholder(params.get('password')):
                # Official Aiven API redaction opt-in, authorized GET only.
                # Old CLI get_service_user() does not expose this query option.
                private_user=cli.verify(cli.get,cli.build_path('project',PROJECT,'service',NAME,'user',params.get('user','avnadmin')),
                    params={'include_secrets':'true'},result_key='user')
                params['password']=private_user.get('password')
            local.require(all(params.get(x) for x in needed),'PRIVATE_CONNECTION_FIELDS_UNAVAILABLE')
            local.require(not placeholder(params['password']),'PASSWORD_MASKED')
            ca=cli.get_project_ca(PROJECT)
            local.require(ca.get('certificate','').startswith('-----BEGIN CERTIFICATE-----'),'PROJECT_CA_UNAVAILABLE')
            ca_path=ROOT/'project-ca.pem';ca_path.write_text(ca['certificate'])
            (ROOT/'connection.dpapi').write_bytes(local.protect(json.dumps({k:params[k] for k in needed}).encode()))
            pg=Pg.__new__(Pg)
            # Reuse the installed libpq wrapper's result handling, with this
            # private connection explicitly bound to provider hostname + CA.
            import os
            pg.directory=os.add_dll_directory(r'C:\Program Files\PostgreSQL\16\bin')
            pg.lib=C.CDLL(r'C:\Program Files\PostgreSQL\16\bin\libpq.dll')
            p,s,i=C.c_void_p,C.c_char_p,C.c_int
            for name,restype,args in [('PQconnectdbParams',p,[C.POINTER(s),C.POINTER(s),i]),('PQstatus',i,[p]),
                ('PQexec',p,[p,s]),('PQexecParams',p,[p,s,i,p,C.POINTER(s),p,p,i]),('PQresultStatus',i,[p]),
                ('PQntuples',i,[p]),('PQnfields',i,[p]),('PQgetvalue',s,[p,i,i]),('PQgetisnull',i,[p,i,i]),
                ('PQclear',None,[p]),('PQfinish',None,[p]),('PQresultErrorField',s,[p,i]),
                ('PQsslInUse',i,[p])]:
                fn=getattr(pg.lib,name);fn.restype=restype;fn.argtypes=args
            keys=[b'host',b'port',b'user',b'password',b'dbname',b'sslmode',b'sslrootcert',b'connect_timeout',None]
            vals=[str(params[k]).encode() for k in needed]+[b'verify-full',str(ca_path).encode(),b'10',None]
            pg.conn=pg.lib.PQconnectdbParams((s*len(keys))(*keys),(s*len(vals))(*vals),0)
            if not pg.conn or pg.lib.PQstatus(pg.conn)!=0:
                pg.lib.PQerrorMessage.argtypes=[p];pg.lib.PQerrorMessage.restype=s
                message=(pg.lib.PQerrorMessage(pg.conn) or b'').decode(errors='replace').lower()
                report['tls_failure_categories']=[label for label,pattern in {
                    'certificate_validation':r'certificate|does not match|hostname mismatch',
                    'authentication':r'authentication failed|no password|password authentication',
                    'network_timeout':r'timed out|timeout',
                    'dns':r'could not translate|name or service not known|no such host',
                    'connection_refused':r'connection refused|actively refused|could not connect',
                    'server_startup':r'starting up|server closed|not yet accepting',
                    'access_rules':r'pg_hba',
                    'client_key':r'private key|client certificate',
                }.items() if re.search(pattern,message)]
                (ROOT/'target-preflight.json').write_text(json.dumps(report,indent=2))
                print('TLS_FAILURE_CATEGORIES='+json.dumps(report['tls_failure_categories']))
            local.require(pg.conn and pg.lib.PQstatus(pg.conn)==0,'VERIFIED_TLS_CONNECTION_FAILED')
            local.require(pg.lib.PQsslInUse(pg.conn)==1,'TLS_NOT_IN_USE')
            pg.query('BEGIN READ ONLY')
            report['tls']='PASS_verify-full'
            report['tls_session']=pg.query('SELECT ssl,version,bits FROM pg_stat_ssl WHERE pid=pg_backend_pid()')[0]
            report['sql_version']=pg.query('SELECT version()')[0][0]
            report['default_database']=pg.query('SELECT current_database()')[0][0]
            report['account_privileges']=pg.query('SELECT rolsuper,rolcreaterole,rolcreatedb,rolcanlogin,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=current_user')[0]
            report['database_privileges']=pg.query("SELECT has_database_privilege(current_database(),'CONNECT'),has_database_privilege(current_database(),'CREATE'),has_schema_privilege('public','USAGE'),has_schema_privilege('public','CREATE')")[0]
            report['max_connections']=pg.query('SHOW max_connections')[0][0]
            report['superuser_reserved_connections']=pg.query('SHOW superuser_reserved_connections')[0][0]
            report['reserved_connections']=pg.query('SHOW reserved_connections')[0][0]
            report['timezone']=pg.query('SHOW TimeZone')[0][0]
            report['timestamp_precision']=pg.query("SELECT timestamp '2026-11-07 09:37:51.123456'::text,(timestamptz '2026-11-07 09:37:51.123456+00' AT TIME ZONE 'UTC')::text")[0]
            report['numeric_precision']=pg.query("SELECT (99999999999999999.99::numeric(19,2))::text,(0.1::numeric(19,2)+0.2::numeric(19,2))::text")[0]
            report['icu_collation_count']=pg.query("SELECT COUNT(*) FROM pg_collation WHERE collprovider='i'")[0][0]
            report['icu_versions']=pg.query("SELECT DISTINCT collversion FROM pg_collation WHERE collprovider='i' ORDER BY collversion")
            report['nondeterministic_icu_count']=pg.query("SELECT COUNT(*) FROM pg_collation WHERE collprovider='i' AND NOT collisdeterministic")[0][0]
            report['application_tables']=pg.query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")[0][0]
            report['existing_accounts']=len(pg.query('SELECT rolname FROM pg_roles WHERE rolcanlogin'))
            pg.query('COMMIT');pg.close()
            (ROOT/'target-preflight.json').write_text(json.dumps(report,indent=2))
            backups=cli.get_service_backups(PROJECT,NAME)
            report['provider_backup_count']=len(backups)
            report['provider_backup_fields']=sorted({k for v in backups for k in v if not any(x in k.lower() for x in ['password','uri','token'])})
            report['backup_verified_restorable']=False
            (ROOT/'target-preflight.json').write_text(json.dumps(report,indent=2))
        print(json.dumps(report))
        return 0
    except Error as e:
        # Provider errors might contain secrets: show only status and stable code.
        print('AIVEN_REQUEST_BLOCKED_HTTP_'+str(e.status));return 1
    except local.Stop as e:
        print('STOP_'+str(e));return 1
    except Exception as e:
        print('STOP_'+type(e).__name__);return 1

if __name__=='__main__':sys.exit(main())
