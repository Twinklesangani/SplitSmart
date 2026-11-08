"""Record-free schema fingerprints derived from a fresh, trusted V11 fixture.

No owners, OIDs, passwords, row counts, sequence current values or ICU library
versions enter the contract. ICU definition is checked; version parity remains
a separate release/privacy decision.
"""
import hashlib
import json
from pathlib import Path

CONTRACT=Path(__file__).parent/'schema/postgres-v11.json'
QUERIES={
 'columns':"""SELECT table_name,column_name,data_type,is_nullable,
 character_maximum_length::text,numeric_precision::text,numeric_scale::text,
 datetime_precision::text,is_identity,identity_generation,column_default,collation_name
 FROM information_schema.columns WHERE table_schema='public'
 ORDER BY table_name,ordinal_position""",
 'constraints':"""SELECT c.relname,x.conname,x.contype::text,
 x.condeferrable::text,x.condeferred::text,x.convalidated::text,pg_get_constraintdef(x.oid)
 FROM pg_constraint x JOIN pg_class c ON c.oid=x.conrelid
 JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' ORDER BY c.relname,x.conname""",
 'indexes':"""SELECT c.relname,i.relname,pg_get_indexdef(i.oid),
 x.indisunique::text,x.indisvalid::text,x.indisready::text,x.indnullsnotdistinct::text
 FROM pg_index x JOIN pg_class c ON c.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid
 JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' ORDER BY c.relname,i.relname""",
 'relations':"""SELECT c.relname,c.relkind::text,c.relrowsecurity::text,c.relforcerowsecurity::text
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','f') ORDER BY c.relname""",
 'sequences':"""SELECT sequencename,data_type,start_value::text,min_value::text,
 max_value::text,increment_by::text,cycle::text,cache_size::text
 FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename""",
 'triggers':"""SELECT c.relname,t.tgname,t.tgenabled::text,pg_get_triggerdef(t.oid)
 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND NOT t.tgisinternal ORDER BY c.relname,t.tgname""",
 'collations':"""SELECT c.collname,c.collprovider::text,c.colliculocale,c.collisdeterministic::text
 FROM pg_collation c JOIN pg_namespace n ON n.oid=c.collnamespace
 WHERE n.nspname='public' ORDER BY c.collname""",
}


class ContractError(Exception):pass


def fingerprint(rows):
    return hashlib.sha256(json.dumps(rows,ensure_ascii=True,separators=(',',':'),sort_keys=True).encode()).hexdigest()


def migration_hashes(directory):
    return {p.name:hashlib.sha256(p.read_text(encoding='utf-8-sig').replace('\r\n','\n').encode()).hexdigest()
            for p in sorted(Path(directory).glob('V*__*.sql'))}


def capture(connection):
    # Pin deparser/name resolution to trusted schemas for this connection only.
    connection.query('SET search_path TO pg_catalog, public')
    return {name:fingerprint(connection.query(sql)) for name,sql in QUERIES.items()}


def verify_fingerprints(actual,expected):
    if set(actual)!=set(expected):raise ContractError('SCHEMA_CONTRACT_SECTION_SET')
    for name in QUERIES:
        if actual[name]!=expected[name]:raise ContractError('SCHEMA_CONTRACT_'+name.upper()+'_DRIFT')


def verify(connection,migration_dir):
    expected=json.loads(CONTRACT.read_text(encoding='utf-8'))
    if expected['migration_sha256']!=migration_hashes(migration_dir):
        raise ContractError('SCHEMA_CONTRACT_MIGRATION_DRIFT')
    verify_fingerprints(capture(connection),expected['fingerprints'])
