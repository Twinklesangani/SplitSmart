"""Private Windows local database support; no cloud/production defaults or exports."""
import ctypes as C
import os
from pathlib import Path
import re
import shutil
import subprocess as S

BASE = Path.home() / 'AppData/Local/SplitSmart'
MYSQL = Path.home() / 'mysql/mysql-8.4.13'
POWERSHELL = shutil.which('pwsh.exe')
if not POWERSHELL:
    raise RuntimeError('PowerShell 7 must be available')
CREATE_NO_WINDOW = 0x08000000
TABLES = {'users','expense_groups','group_members','expenses','expense_splits','settlements','expense_creation_operations','group_invitations','flyway_schema_history'}
CHECKSUMS = [1632565820,-1885547116,-722028687,-1841320934,-1763812614,-624755133,980101589]


class Stop(Exception):
    pass

def require(ok, code):
    if not ok:
        raise Stop(code)

def ps(code):
    child_env = os.environ.copy()
    # Windows PowerShell 5.1 must discover its own inbox modules, rather than
    # inheriting PowerShell 7's module path from the desktop tool host.
    child_env.pop('PSModulePath', None)
    r = S.run([POWERSHELL, '-NoProfile', '-NonInteractive', '-Command',
               '$ErrorActionPreference="Stop";' + code], capture_output=True, timeout=30,
              creationflags=CREATE_NO_WINDOW, env=child_env)
    if r.returncode:
        category = re.search(r'FullyQualifiedErrorId\s*:\s*([^\r\n]+)', r.stderr.decode('utf-8', errors='replace'))
        raise Stop('WINDOWS_CHECK_FAILED_' + (category.group(1).strip() if category else str(r.returncode)))
    return r.stdout.decode('utf-8-sig').strip()

def private_directory(path):
    # Set the parent first, so every child inherits a private ACL at creation.
    if not path.exists():
        path.mkdir()
    q = str(path).replace("'", "''")
    sid = ps('[Security.Principal.WindowsIdentity]::GetCurrent().User.Value')
    acl = S.run(['icacls.exe', str(path), '/inheritance:r', '/grant:r',
                 '*'+sid+':(OI)(CI)F', '*S-1-5-18:(OI)(CI)F'],
                capture_output=True, timeout=20, creationflags=CREATE_NO_WINDOW)
    require(acl.returncode == 0, 'PRIVATE_ACL_WRITE_FAILED')
    ps("$p='" + q + "';$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User;"
       "$v=Get-Acl -LiteralPath $p; if(!$v.AreAccessRulesProtected){throw 'ACL'};"
       "if($v.Owner -ne $sid.Translate([Security.Principal.NTAccount]).Value){throw 'OWNER'};"
       "foreach($r in $v.Access){if($r.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin @($sid.Value,'S-1-5-18')){throw 'ACL'}}")

class Blob(C.Structure):
    _fields_ = [('size', C.c_uint32), ('data', C.POINTER(C.c_ubyte))]

def protect(data):
    buf = (C.c_ubyte * len(data)).from_buffer_copy(data)
    src, dst = Blob(len(data), buf), Blob()
    crypt = C.WinDLL('crypt32', use_last_error=True)
    crypt.CryptProtectData.argtypes = [C.POINTER(Blob), C.c_wchar_p, C.c_void_p,
                                      C.c_void_p, C.c_void_p, C.c_uint32, C.POINTER(Blob)]
    require(crypt.CryptProtectData(C.byref(src), None, None, None, None, 1, C.byref(dst)), 'DPAPI_FAILED')
    out = C.string_at(dst.data, dst.size)
    C.windll.kernel32.LocalFree(C.cast(dst.data, C.c_void_p))
    return out

class MySQL:
    def __init__(self, password):
        self.dll_dir = os.add_dll_directory(str(MYSQL / 'bin'))
        self.lib_dir = os.add_dll_directory(str(MYSQL / 'lib'))
        self.lib = C.CDLL(str(MYSQL / 'lib/libmysql.dll'))
        p, s, u = C.c_void_p, C.c_char_p, C.c_uint
        signatures = {
            'mysql_init': (p, [p]), 'mysql_options': (C.c_int, [p, C.c_int, p]),
            'mysql_real_connect': (p, [p, s, s, s, s, u, s, C.c_ulong]),
            'mysql_real_query': (C.c_int, [p, s, C.c_ulong]),
            'mysql_store_result': (p, [p]), 'mysql_num_fields': (u, [p]),
            'mysql_fetch_row': (C.POINTER(s), [p]),
            'mysql_free_result': (None, [p]), 'mysql_errno': (u, [p]),
            'mysql_close': (None, [p]), 'mysql_get_ssl_cipher': (s, [p]),
        }
        for name, (restype, args) in signatures.items():
            fn = getattr(self.lib, name); fn.restype = restype; fn.argtypes = args
        self.handle = self.lib.mysql_init(None)
        require(self.handle, 'CLIENT_INIT_FAILED')
        header = (MYSQL / 'include/mysql.h').read_text()
        body = re.search(r'enum mysql_option\s*\{(.*?)\};', header, re.S).group(1)
        body = re.sub(r'/\*.*?\*/|//[^\n]*', '', body, flags=re.S)
        options = {}; value = 0
        for part in body.split(','):
            part = part.strip()
            if not part: continue
            if '=' in part:
                part, explicit = part.split('=', 1); value = int(explicit.strip(), 0)
            options[part.strip()] = value; value += 1
        def opt(name, val, typ=C.c_uint):
            obj = typ(val)
            require(self.lib.mysql_options(self.handle, options[name], C.byref(obj)) == 0, 'CLIENT_OPTION_FAILED')
        opt('MYSQL_OPT_CONNECT_TIMEOUT', 5)
        opt('MYSQL_OPT_READ_TIMEOUT', 30)
        opt('MYSQL_OPT_WRITE_TIMEOUT', 30)
        opt('MYSQL_OPT_PROTOCOL', 1)  # MYSQL_PROTOCOL_TCP
        opt('MYSQL_OPT_LOCAL_INFILE', 0)
        opt('MYSQL_OPT_SSL_MODE', 3)  # SSL_MODE_REQUIRED
        opt('MYSQL_OPT_CAN_HANDLE_EXPIRED_PASSWORDS', True, C.c_bool)
        require(self.lib.mysql_real_connect(self.handle, b'127.0.0.1', b'root', password,
                None, 23307, None, 1 << 22), 'AUTHENTICATION_FAILED')
        require(self.lib.mysql_get_ssl_cipher(self.handle), 'LOCAL_TLS_REQUIRED')

    def query(self, sql):
        b = sql.encode() if isinstance(sql, str) else sql
        require(self.lib.mysql_real_query(self.handle, b, len(b)) == 0,
                'SQL_ERROR_' + str(self.lib.mysql_errno(self.handle)))
        result = self.lib.mysql_store_result(self.handle)
        rows = []
        if result:
            fields = self.lib.mysql_num_fields(result)
            while True:
                row = self.lib.mysql_fetch_row(result)
                if not row: break
                rows.append(tuple(row[i].decode('utf-8') if row[i] is not None else None for i in range(fields)))
            self.lib.mysql_free_result(result)
        return rows

    def close(self):
        self.lib.mysql_close(self.handle)
