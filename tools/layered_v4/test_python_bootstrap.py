"""No GPU/model downloads: simulate missing ensurepip and bounded uv recovery."""
import json, subprocess, tempfile, unittest
from pathlib import Path
from types import SimpleNamespace as NS
from unittest.mock import patch
from .diagnostics import DiagnosticSession, ensure_target_python, python_health

ROOT=Path(__file__).resolve().parents[2]

class BootstrapTests(unittest.TestCase):
    def exercise(self,base,failures=0,partial=False):
        s=DiagnosticSession(base,ROOT);py=s.base/'python312/bin/python';calls=[];attempts=[]
        markers=[s.base/'cache/weights',s.base/'project-001/source.png',s.base/'project-001/pearl_mask.png']
        for f in markers:f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(b'preserve')
        if partial:py.parent.mkdir(parents=True);py.touch()
        def command(args,check=True):
            args=list(map(str,args));calls.append(args)
            self.assertNotEqual(args[1:3],['-m','venv']) # system Python has no ensurepip
            self.assertNotIn('ensurepip',args)
            if 'venv' in args:
                attempts.append(1);py.parent.mkdir(parents=True,exist_ok=True);py.touch()
                if len(attempts)<=failures:raise subprocess.CalledProcessError(2,args,output='creation stdout',stderr='seed failed')
            if 'find' in args:return NS(returncode=0,stdout='/managed/bin/python3.12')
            if '-c' in args:return NS(returncode=0,stdout=json.dumps({'version':[3,12],'prefix':str(py.parent.parent)}))
            if args[-2:]==['pip','--version'] and partial and not attempts:return NS(returncode=1,stdout='',stderr='No module named pip')
            return NS(returncode=0,stdout='pip 25',stderr='')
        with patch.object(s,'command',side_effect=command),patch('tools.layered_v4.diagnostics.uv_command',return_value=['uv-pinned']):
            if failures>=2:
                with self.assertRaises(subprocess.CalledProcessError):ensure_target_python(s)
            else:self.assertEqual(ensure_target_python(s),py)
        for f in markers:self.assertEqual(f.read_bytes(),b'preserve')
        return s,calls
    def test_missing_ensurepip_uses_uv_seed_not_stdlib_venv(self):
        with tempfile.TemporaryDirectory() as d:
            s,calls=self.exercise(d)
            create=next(c for c in calls if 'venv' in c)
            self.assertIn('--seed',create);self.assertIn('--no-python-downloads',create)
            self.assertFalse(s.report['python_environment']['reused'])
    def test_partial_environment_without_pip_is_quarantined(self):
        with tempfile.TemporaryDirectory() as d:
            s,_=self.exercise(d,partial=True)
            self.assertEqual(len(s.report['quarantined_venvs']),1)
            self.assertTrue(list(Path(d).glob('python312.quarantined-*/bin/python')))
    def test_failed_creation_retries_with_managed_python_once(self):
        with tempfile.TemporaryDirectory() as d:
            s,calls=self.exercise(d,failures=1)
            creates=[c for c in calls if 'venv' in c];self.assertEqual(len(creates),2)
            self.assertIn('/managed/bin/python3.12',creates[1]);self.assertEqual(s.report['python_environment']['creation_attempt'],2)
    def test_second_failure_stops_without_unbounded_retry(self):
        with tempfile.TemporaryDirectory() as d:
            s,calls=self.exercise(d,failures=2)
            self.assertEqual(len([c for c in calls if 'venv' in c]),2)
            self.assertFalse(s.report['passed']);self.assertFalse(s.report['models_started'])
    def test_healthy_environment_reused_without_uv(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'python312/bin/python';p.parent.mkdir(parents=True);p.touch();s=DiagnosticSession(d,ROOT)
            def command(args,check=True):return NS(returncode=0,stdout=json.dumps({'version':[3,12],'prefix':str(p.parent.parent)}) if '-c' in args else 'pip available')
            with patch.object(s,'command',side_effect=command),patch('tools.layered_v4.diagnostics.uv_command') as uv:
                self.assertEqual(ensure_target_python(s),p);uv.assert_not_called()
    def test_symlink_environment_preserved_and_refused(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);other=p/'other';other.mkdir();(p/'python312').symlink_to(other,target_is_directory=True)
            with self.assertRaisesRegex(RuntimeError,'symlink'):ensure_target_python(DiagnosticSession(p,ROOT))
            self.assertTrue(other.exists())
    def test_wrong_python_version_or_prefix_cannot_pass(self):
        with tempfile.TemporaryDirectory() as d:
            s=DiagnosticSession(d,ROOT)
            for info in ({'version':[3,11],'prefix':d},{'version':[3,12],'prefix':'/usr'}):
                with patch.object(s,'command',return_value=NS(returncode=0,stdout=json.dumps(info))):
                    self.assertFalse(python_health(s,Path(d)/'python312/bin/python')[0])

if __name__=='__main__':unittest.main()
