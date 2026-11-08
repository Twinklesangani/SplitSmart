"""Record-free cloud metadata preflight. No execution/upload mode exists."""
import json
import datetime as dt
from pathlib import Path
from migration_transfer import Target,metadata_preflight,TransferError


def record_result(result):
    import local_database_support as local
    root=local.BASE/'CloudReadiness'
    local.private_directory(root)
    path=root/'loader-metadata-preflight.json'
    # Preserve prior evidence, but never leave an old PASS as the latest result
    # after a new authentication/configuration failure.
    prior=root/'loader-metadata-preflight-prior-success.json'
    if path.exists() and not prior.exists():
        previous=json.loads(path.read_text(encoding='utf-8'))
        if previous.get('status')=='PASS':prior.write_bytes(path.read_bytes())
    result.update(verified_utc=dt.datetime.now(dt.timezone.utc).isoformat(),
                  real_data_uploaded=False,source_records_read=False,execution_enabled=False)
    path.write_text(json.dumps(result,indent=2),encoding='utf-8')


def failure_code(error):
    from schema_contract import ContractError
    if getattr(error,'status',None)==401:return 'CLOUD_PREFLIGHT_MANAGEMENT_AUTH_REQUIRED_401_NO_UPLOAD'
    if isinstance(error,ContractError):return str(error)
    return 'CLOUD_PREFLIGHT_BLOCKED_NO_RECORDS_UPLOADED'


def main():
    import prepare_postgres_target as t
    p=t.params()
    # params pins endpoint against the named existing Free service; the factory
    # uses provider CA AND hostname validation, not merely encrypted transport.
    c=t.connection(p,'app_migration',t.APP)
    try:
        c.query('BEGIN TRANSACTION READ ONLY')
        # Managed public endpoint ports can be NAT-mapped. Pin public host/port
        # through params(), and separately record the actual SQL listener port.
        port=int(c.query('SELECT inet_server_port()')[0][0])
        result=metadata_preflight(c,Target(t.APP,t.ROLES['app_migration'],port,True),
                                  t.WORK/'backend/src/main/resources/db/postgresql')
        result.update(real_data_uploaded=False,source_records_read=False,execution_enabled=False)
        c.query('COMMIT')
        record_result(result)
        print('CLOUD_LOADER_METADATA_ONLY_PREFLIGHT_PASS_EXECUTION_DISABLED')
    finally:c.close()


if __name__=='__main__':
    import sys
    try:
        if sys.argv[1:]!=['--metadata-only']:raise TransferError('REAL_DATA_EXECUTION_NOT_AUTHORIZED')
        main()
    except Exception as error:
        code=failure_code(error)
        record_result({'status':'BLOCKED','error_code':code})
        print(code)
        sys.exit(1)
