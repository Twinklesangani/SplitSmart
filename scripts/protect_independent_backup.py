"""Local ciphertext-only USB workflow. Execution needs exact owner-approved destination.
No network, SQL, decryption, overwrite or source deletion operations.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

SOURCE = Path(r"C:\Users\TWINKLE SANGANI\AppData\Local\SplitSmart\Backups\splitsmart-v7-fresh-20261112T163528Z.sql.gpg")
EXPECTED = "a70bf90feaaf251e51b07b047e0e45e8abee84fb62fdeb9d2d8b33ca11890f1a"

class SafetyStop(Exception):
    pass

def require(ok, code):
    if not ok:
        raise SafetyStop(code)

def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def no_links(path):
    # Reject junctions/reparse points too: a USB path must not redirect elsewhere.
    for item in (path, *path.parents):
        if item.exists():
            require(not item.is_symlink() and not (getattr(item.lstat(), 'st_file_attributes', 0) & 0x400), 'REPARSE_POINT')

def copy_once(source, destination, expected):
    source, destination = Path(source), Path(destination)
    require(source.is_file() and source.name.endswith('.sql.gpg'), 'CIPHERTEXT_SOURCE')
    require(source.resolve() != destination.resolve(), 'SAME_FILE')
    require(not destination.exists(), 'DESTINATION_EXISTS')
    no_links(destination)
    require(digest(source) == expected, 'SOURCE_HASH')
    # Exclusive create closes the overwrite race. Keep any partial output for diagnosis.
    with source.open('rb') as incoming, destination.open('xb') as outgoing:
        shutil.copyfileobj(incoming, outgoing, length=1024*1024)
        outgoing.flush()
        os.fsync(outgoing.fileno())
    require(digest(destination) == expected, 'COPY_HASH')
    require(digest(source) == expected, 'SOURCE_CHANGED')
    return destination.stat().st_size

def usb_identity(directory):
    require(os.name == 'nt', 'WINDOWS_REQUIRED')
    require(re.fullmatch(r'[A-Za-z]:', directory.drive) is not None, 'LOCAL_DRIVE_REQUIRED')
    letter = directory.drive[0].upper()
    code = "$ErrorActionPreference='Stop';$p=Get-Partition -DriveLetter '"+letter+"';$d=$p|Get-Disk;$v=Get-Volume -DriveLetter '"+letter+"';[pscustomobject]@{id=$d.UniqueId;bus=[string]$d.BusType;free=$v.SizeRemaining;drive='"+letter+"'}|ConvertTo-Json -Compress"
    run = subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',code],capture_output=True,timeout=20)
    require(run.returncode == 0, 'DISK_METADATA_UNAVAILABLE')
    result = json.loads(run.stdout.decode('utf-8-sig'))
    require(result['bus'] == 'USB', 'PHYSICALLY_SEPARATE_USB_REQUIRED')
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', required=True, help='Exact owner-approved USB folder; never a cloud sync folder')
    parser.add_argument('--expected-disk-id', help='Reverified USB UniqueId')
    parser.add_argument('--execute-approved-copy', action='store_true', help='Use only after separate exact-destination approval')
    args=parser.parse_args()
    directory=Path(args.destination)
    require(directory.is_absolute(), 'ABSOLUTE_DESTINATION_REQUIRED')
    no_links(directory)
    identity=usb_identity(directory)
    require(digest(SOURCE)==EXPECTED, 'SOURCE_HASH')
    require(identity['free'] > SOURCE.stat().st_size + 1024*1024, 'USB_CAPACITY')
    output=directory/SOURCE.name
    require(not output.exists(), 'DESTINATION_EXISTS')
    if not args.execute_approved_copy:
        print(json.dumps({'status':'PLAN_ONLY_NO_COPY','destination':str(output),'usb':identity,'sha256':EXPECTED}))
        return
    require(args.expected_disk_id and identity['id']==args.expected_disk_id,'APPROVED_USB_ID_REQUIRED')
    directory.mkdir(parents=True, exist_ok=True)
    no_links(directory)
    require(usb_identity(directory)['id']==args.expected_disk_id,'USB_CHANGED')
    size=copy_once(SOURCE,output,EXPECTED)
    print(json.dumps({'status':'COPY_VERIFIED','path':str(output),'bytes':size,'sha256':EXPECTED}))

if __name__=='__main__':
    try:
        main()
    except Exception as error:
        # Never print filesystem contents, provider messages or credentials.
        print('STOP_'+(str(error) if isinstance(error,SafetyStop) else type(error).__name__))
        raise SystemExit(1)
