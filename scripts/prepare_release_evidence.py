"""LOCAL artifact audit only. No network, production credentials or deployment."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile
import local_database_support as local

WORK=Path(__file__).resolve().parents[1]
PRODUCTION_BASELINE='418d757855dd10965c2872176022f93ac5e35a8a'


def git(*args):
    result=subprocess.run(['git',*args],cwd=WORK,capture_output=True,timeout=15)
    local.require(result.returncode==0,'GIT_EVIDENCE')
    return result.stdout


def main():
    local.require(git('branch','--show-current').decode().strip()=='codex/password-recovery-backend','RELEASE_BRANCH')
    git('merge-base','--is-ancestor',PRODUCTION_BASELINE,'HEAD')
    critical=['backend/src/main','backend/pom.xml','backend/Dockerfile','backend/docker-entrypoint.sh','backend/.dockerignore','frontend/src','frontend/public','frontend/package.json','frontend/package-lock.json']
    local.require(not git('diff','--name-only','HEAD','--',*critical).strip(),'PACKAGED_SOURCE_DIRTY')
    for path in (WORK/'backend/src/main/resources/db/migration').glob('V*__*.sql'):
        number=int(path.name.split('__')[0][1:])
        if number<=7:
            original=git('show',PRODUCTION_BASELINE+':'+path.relative_to(WORK).as_posix())
            local.require(original.replace(b'\r\n',b'\n')==path.read_bytes().replace(b'\r\n',b'\n'),'ORIGINAL_MYSQL_MIGRATION_CHANGED')
    jars=list((WORK/'backend/target').glob('*.jar'))
    local.require(len(jars)==1,'PACKAGED_JAR_SET')
    jar=jars[0]
    with zipfile.ZipFile(jar) as zipped:
        major=int.from_bytes(zipped.read('BOOT-INF/classes/com/splitsmart/SplitSmartApplication.class')[6:8],'big')
        local.require(major==65,'JAVA21_BYTECODE')
        for path in (WORK/'backend/src/main/resources/db/postgresql').glob('*.sql'):
            packed=zipped.read('BOOT-INF/classes/db/postgresql/'+path.name)
            local.require(packed.replace(b'\r\n',b'\n')==path.read_bytes().replace(b'\r\n',b'\n'),'PACKAGED_MIGRATION_DRIFT')
        local.require(not any('application-postgres-it.properties' in n or 'application-test.properties' in n for n in zipped.namelist()),'TEST_PROFILE_PACKAGED')
    root=local.BASE/'CloudReadiness'
    config=json.loads((root/'frontend-artifact-config.json').read_text())
    local.require(config['email_enabled'] is False and config['api_base_url'].startswith('https://'),'FRONTEND_CONFIG')
    files=[p for p in (WORK/'frontend/dist').rglob('*') if p.is_file()]
    local.require(files and any(config['api_base_url'].encode() in p.read_bytes() for p in files if p.suffix=='.js'),'FRONTEND_ARTIFACT_API')
    report={'head':git('rev-parse','HEAD').decode().strip(),'production_ancestor':PRODUCTION_BASELINE,
      'mysql_v1_v7_preserved':True,'java21_class_major':major,'backend_jar':jar.name,
      'backend_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
      'frontend_files':{str(p.relative_to(WORK/'frontend/dist')):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
      'email_disabled':True,'deployed':False,'docker_image_verified':False,
      'production_go':'NO-GO_PRIVACY_APPROVAL_CONTAINER_AND_EXECUTION_GATES'}
    local.private_directory(root)
    (root/'release-artifacts.json').write_text(json.dumps(report,indent=2))
    print('LOCAL_JAVA21_AND_FRONTEND_ARTIFACT_AUDIT_PASS_PRODUCTION_NO_GO')


if __name__=='__main__':
    import sys
    try:
        local.require(sys.argv[1:]==['--local-only'],'EXPLICIT_LOCAL_ONLY_MODE')
        main()
    except Exception as error:
        print('ARTIFACT_AUDIT_BLOCKED_'+type(error).__name__)
        sys.exit(1)
