import unittest
import subprocess
import sys
from rootless_container_checks import runtime_command,security_probe_command
from run_rootless_verification import rootless_launch_command

class RootlessBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.image='sha256:'+'a'*64
        self.runid='ssverify-fixture'
    def command(self,**kwargs):
        return runtime_command(self.image,self.runid+'-app',self.runid,{'PORT':'8080'},
            {'DB_PASSWORD':self.runid+'-db-password'},**kwargs)
    def test_wsl_launcher_uses_public_file_not_executable_source_argument(self):
        args=rootless_launch_command()
        self.assertNotIn('-c',args)
        self.assertEqual('/home/ss_oci_verify/tools/rootless_container_checks.py',args[-1])
    def test_wsl_launcher_drops_to_dedicated_identity_and_clean_environment(self):
        args=rootless_launch_command()
        self.assertEqual(['runuser','-u','ss_oci_verify'],args[args.index('runuser'):args.index('runuser')+3])
        self.assertIn('-i',args)
    def test_pins_image_digest_instead_of_mutable_tag(self):
        with self.assertRaises(ValueError):runtime_command('localhost/app:latest',self.runid+'-app',self.runid,{}, {})
    def test_rootless_application_is_nonroot_readonly_and_loopback_only(self):
        args=self.command()
        for flag in ('--user=10001:10001','--read-only','--cap-drop=all','--security-opt=no-new-privileges','127.0.0.1::8080'):
            self.assertIn(flag,args)
        self.assertNotIn('--privileged',args)
    def test_password_cannot_be_passed_as_plaintext_env_argument(self):
        with self.assertRaises(ValueError):runtime_command(self.image,self.runid+'-app',self.runid,{'DB_PASSWORD':'FAKE_MARKER'}, {})
    def test_rejects_foreign_secret_identity(self):
        with self.assertRaises(ValueError):runtime_command(self.image,self.runid+'-app',self.runid,{}, {'JWT_SECRET':'unrelated-secret'})
    def test_positive_discards_application_logs_and_only_references_secret(self):
        args=self.command()
        self.assertIn('--log-driver=none',args)
        self.assertIn(self.runid+'-db-password,type=env,target=DB_PASSWORD',args)
    def test_negative_uses_memory_only_foreground_output(self):
        args=self.command(negative=True)
        self.assertNotIn('--detach',args)
        self.assertIn('--log-driver=passthrough',args)
    def test_shell_probe_can_cross_native_process_argument_boundary(self):
        result=subprocess.run([sys.executable,'-c','import sys; assert len(sys.argv[1])>100',security_probe_command()],capture_output=True)
        self.assertEqual(0,result.returncode)
    def test_hostname_alias_cannot_be_arbitrary_domain(self):
        with self.assertRaises(ValueError):self.command(host_alias='production.example:127.0.0.1')

if __name__=='__main__':unittest.main()
