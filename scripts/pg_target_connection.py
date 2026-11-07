"""Private libpq connection factory; no connection values are printed."""
import ctypes as C
import os
from pathlib import Path
from postgres_compatibility import Pg
import local_database_support as local

def connect(params,database=None,user=None,password=None,ca=None):
    pg=Pg.__new__(Pg)
    directory=Path(os.environ.get('ProgramFiles',r'C:\Program Files'))/'PostgreSQL/16/bin'
    pg.directory=os.add_dll_directory(str(directory))
    pg.lib=C.CDLL(str(directory/'libpq.dll'))
    p,s,i=C.c_void_p,C.c_char_p,C.c_int
    for name,restype,args in [('PQconnectdbParams',p,[C.POINTER(s),C.POINTER(s),i]),('PQstatus',i,[p]),
        ('PQexec',p,[p,s]),('PQexecParams',p,[p,s,i,p,C.POINTER(s),p,p,i]),('PQresultStatus',i,[p]),
        ('PQntuples',i,[p]),('PQnfields',i,[p]),('PQgetvalue',s,[p,i,i]),('PQgetisnull',i,[p,i,i]),
        ('PQclear',None,[p]),('PQfinish',None,[p]),('PQresultErrorField',s,[p,i]),('PQsslInUse',i,[p])]:
        fn=getattr(pg.lib,name);fn.restype=restype;fn.argtypes=args
    values={'host':params['host'],'port':str(params['port']),'user':user or params['user'],
            'password':password or params['password'],'dbname':database or params['dbname'],
            'connect_timeout':'10','options':'-c timezone=UTC'}
    if ca:values.update(sslmode='verify-full',sslrootcert=str(ca))
    keys=[k.encode() for k in values]+[None]
    vals=[v if isinstance(v,bytes) else str(v).encode() for v in values.values()]+[None]
    pg.conn=pg.lib.PQconnectdbParams((s*len(keys))(*keys),(s*len(vals))(*vals),0)
    local.require(pg.conn and pg.lib.PQstatus(pg.conn)==0,'PRIVATE_PG_CONNECTION_FAILED')
    if ca:local.require(pg.lib.PQsslInUse(pg.conn)==1,'TLS_REQUIRED')
    # libpq otherwise prints NOTICE payloads directly to stderr, bypassing our
    # SQLSTATE-only exception handling. Keep a strong reference to the callback.
    notice=C.CFUNCTYPE(None,C.c_void_p,C.c_char_p)
    pg.notice_sink=notice(lambda _argument,_message: None)
    pg.lib.PQsetNoticeProcessor.argtypes=[p,notice,p]
    pg.lib.PQsetNoticeProcessor.restype=p
    pg.lib.PQsetNoticeProcessor(pg.conn,pg.notice_sink,None)
    return pg
