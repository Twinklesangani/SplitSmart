"""Explicit SELECT-only synthetic cloud/local comparison. Never reads user rows.

Not an importer and not proof of equivalence for every Unicode string.
"""
import hashlib
import json
from pathlib import Path

CASES = [
    ('ascii_case','Synthetic','synthetic',True),
    ('accent','café','cafe',True),
    ('nfc_nfd','é','e\u0301',True),
    ('sharp_s','straße','strasse',True),
    ('turkish_i','I','i',True),
    ('turkish_dotted_i','İ','i',True),
    ('turkish_dotless_i','ı','i',False),
    ('sigma','Σ','ς',True),
    ('width','Ａ','A',True),
    ('ligature','ﬁ','fi',True),
    ('hangul','가','\u1100\u1161',True),
    ('emoji_distinct','😀','😃',False),
    ('space','synthetic','synthetic ',False),
]


class CheckFailed(Exception):
    pass


def require(value, code):
    if not value:raise CheckFailed(code)


def compare(cloud, local):
    result=[]
    for label,left,right,expected in CASES:
        sql='SELECT ($1::text COLLATE public.splitsmart_email_ci = $2::text COLLATE public.splitsmart_email_ci)::text'
        a=cloud.query(sql,[left,right])[0][0]
        b=local.query(sql,[left,right])[0][0]
        require(a==b==str(expected).lower(),'SYNTHETIC_EQUALITY_MISMATCH')
        sql='WITH v(x) AS (VALUES ($1::text COLLATE public.splitsmart_email_ci),($2::text COLLATE public.splitsmart_email_ci)) SELECT (COUNT(*)-COUNT(DISTINCT x))::text FROM v'
        collision=cloud.query(sql,[left,right])[0][0]
        require(collision==str(int(expected)),'SYNTHETIC_UNIQUENESS_MISMATCH')
        result.append({'case':label,'equal':expected,'uniqueness_collision':expected})
    # Exhaust the PRINTABLE SINGLE-CHARACTER ASCII domain, not arbitrary strings.
    # None of these arguments derives from production/recovery records.
    values=[chr(i) for i in range(32,127)]
    entries=','.join('('+str(i)+',$'+str(i+1)+'::text COLLATE public.splitsmart_email_ci)' for i in range(len(values)))
    sql='WITH v(i,s) AS (VALUES '+entries+') SELECT a.i::text,b.i::text,(a.s=b.s)::text,(a.s<b.s)::text FROM v a CROSS JOIN v b ORDER BY a.i,b.i'
    a,b=cloud.query(sql,values),local.query(sql,values)
    require(len(a)==9025 and a==b,'SYNTHETIC_ASCII_MATRIX_MISMATCH')
    return {'unicode_cases':result,'printable_ascii_single_character_pairs':9025,
            'ascii_matrix_hash':hashlib.sha256(json.dumps(a).encode()).hexdigest(),
            'arbitrary_unicode_equivalence_proven':False}


def main():
    import prepare_postgres_target as t
    import local_pg_rehearsal as recovery
    from postgres_compatibility import unprotect
    receipt=json.loads((recovery.RUN/'result.json').read_text())
    require(receipt['status']=='PASS','LOCAL_REHEARSAL_EVIDENCE')
    admin=recovery.Pg('ss_pg_owner',unprotect(recovery.CLUSTER/'owner-credential.dpapi'),'postgres')
    recovery.guard(admin,'postgres');admin.close()
    local=recovery.Pg(receipt['role'],unprotect(recovery.RUN/'credential.dpapi'),receipt['database'])
    recovery.guard(local,receipt['database']);recovery.safe_policy(local)
    params=t.params();state=t.state()
    cloud=t.connection(params,'test_migration',state['test_database'])
    try:
        cloud.query('BEGIN TRANSACTION READ ONLY')
        t.guard(cloud,state,True)
        query="SELECT collprovider::text,colliculocale,collversion,pg_collation_actual_version(oid),collisdeterministic::text FROM pg_collation WHERE collname='splitsmart_email_ci'"
        target,source=cloud.query(query)[0],local.query(query)[0]
        require(target[0:2]==source[0:2]==('i','und-u-ks-level1') and
                target[2]==target[3] and source[2]==source[3] and
                target[4]==source[4]=='false','COLLATION_METADATA')
        for table,column in [('users','email'),('group_invitations','email'),('settlements','request_id')]:
            require(cloud.query("SELECT collation_name FROM information_schema.columns WHERE table_schema='public' AND table_name=$1 AND column_name=$2",[table,column])==[('splitsmart_email_ci',)],'COLUMN_COLLATION')
        require(cloud.query("SELECT COUNT(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND (NOT i.indisvalid OR NOT i.indisready)")==[('0',)],'INVALID_INDEX')
        report=compare(cloud,local)
        report.update(status='PASS_SYNTHETIC_SCOPE',cloud_collation=target,local_collation=source,
                      real_values_uploaded=False,cloud_configuration_changed=False,
                      cloud_version=cloud.query('SHOW server_version')[0][0])
        cloud.query('COMMIT')
        root=recovery.l.BASE/'CloudReadiness'
        recovery.l.private_directory(root)
        (root/'collation-synthetic.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print('SYNTHETIC_CLOUD_LOCAL_13_CASES_AND_9025_ASCII_PAIRS_PASS')
    finally:
        cloud.close();local.close()


if __name__=='__main__':
    import sys
    try:
        require(sys.argv[1:]==['--synthetic-read-only'],'EXPLICIT_SYNTHETIC_MODE_REQUIRED')
        main()
    except BaseException as error:
        # No provider/client exception text, connection strings or values.
        print('STOP_'+(str(error) if isinstance(error,CheckFailed) else type(error).__name__))
        sys.exit(1)
