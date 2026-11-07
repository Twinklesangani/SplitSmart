"""Record-free cloud metadata preflight. No execution/upload mode exists."""
import json
from pathlib import Path
from migration_transfer import Target,metadata_preflight,TransferError


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
        root=t.local.BASE/'CloudReadiness'
        t.local.private_directory(root)
        (root/'loader-metadata-preflight.json').write_text(json.dumps(result,indent=2))
        print('CLOUD_LOADER_METADATA_ONLY_PREFLIGHT_PASS_EXECUTION_DISABLED')
    finally:c.close()


if __name__=='__main__':
    import sys
    try:
        if sys.argv[1:]!=['--metadata-only']:raise TransferError('REAL_DATA_EXECUTION_NOT_AUTHORIZED')
        main()
    except Exception:
        print('CLOUD_PREFLIGHT_BLOCKED_NO_RECORDS_UPLOADED')
        sys.exit(1)
