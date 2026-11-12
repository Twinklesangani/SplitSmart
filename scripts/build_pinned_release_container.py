"""Build exact audited JAR over existing pinned Java21 runtime, no network/secrets."""
import hashlib,io,json,subprocess as s,tarfile,secrets
from pathlib import Path

def main():
    root=Path(__file__).resolve().parents[1]
    manifest=json.loads((root/'docs/release-candidate-artifacts.json').read_text())
    jar=(root/'backend/target/splitsmart-backend-0.0.1-SNAPSHOT.jar').read_bytes()
    if hashlib.sha256(jar).hexdigest()!=manifest['backendJarSha256']:raise RuntimeError('ARTIFACT_DRIFT')
    run='release-'+secrets.token_hex(6)
    helper="""import os,sys,tarfile,io,pathlib,subprocess,json
assert os.getuid()==2001
root=pathlib.Path.home()/sys.argv[1];root.mkdir(mode=0o700)
archive=tarfile.open(fileobj=io.BytesIO(sys.stdin.buffer.read()),mode='r:')
assert set(archive.getnames())=={'app.jar'}
(root/'app.jar').write_bytes(archive.extractfile('app.jar').read())
base=subprocess.run(['podman','image','inspect','localhost/splitsmart-verify:qualified-20261108','--format','{{.Id}}'],capture_output=True,check=True).stdout.decode().strip()
assert len(base.removeprefix('sha256:'))==64
(root/'Containerfile').write_text('FROM '+base+'\\nCOPY --chown=10001:10001 app.jar /app/app.jar\\n')
tag='localhost/splitsmart-release:'+sys.argv[1]
result=subprocess.run(['podman','build','--network=none','--pull=never','-t',tag,str(root)],capture_output=True,timeout=180)
if result.returncode:raise RuntimeError('OFFLINE_IMAGE_BUILD_FAILED')
image=json.loads(subprocess.run(['podman','image','inspect',tag],capture_output=True,check=True).stdout)[0]
assert image['Config']['User']=='10001:10001'
print(json.dumps({'image':tag,'id':image['Id'],'base':base}))
"""
    dest='/home/ss_oci_verify/tools/build_release.py'
    copy=s.run(['wsl','-d','Ubuntu','--','runuser','-u','ss_oci_verify','--','python3','-I','-c',"import pathlib,sys;p=pathlib.Path('/home/ss_oci_verify/tools/build_release.py');assert not p.is_symlink();p.write_bytes(sys.stdin.buffer.read())"],input=helper.encode(),capture_output=True,timeout=20)
    if copy.returncode:raise RuntimeError('HELPER_COPY')
    data=io.BytesIO()
    with tarfile.open(fileobj=data,mode='w') as tar:
        info=tarfile.TarInfo('app.jar');info.size=len(jar);info.mode=0o600;tar.addfile(info,io.BytesIO(jar))
    result=s.run(['wsl','-d','Ubuntu','--','runuser','-u','ss_oci_verify','--','env','-i','HOME=/home/ss_oci_verify','USER=ss_oci_verify','LOGNAME=ss_oci_verify','XDG_RUNTIME_DIR=/run/user/2001','PATH=/usr/bin:/bin','python3','-I',dest,run],input=data.getvalue(),capture_output=True,timeout=220)
    if result.returncode:raise RuntimeError('PINNED_IMAGE_BUILD_FAILED')
    receipt=json.loads(result.stdout);print(json.dumps(receipt))
    (root/'docs/release-container-build.json').write_text(json.dumps(receipt,indent=2))

if __name__=='__main__':
    try:main()
    except Exception as e:print('RELEASE_CONTAINER_BLOCKED_'+str(e) if isinstance(e,RuntimeError) else 'RELEASE_CONTAINER_BLOCKED');raise SystemExit(1)
