"""Deployment preflight regressions; never launch or stop real services."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('local_stack', Path(__file__).parents[1] / 'local_stack.py')
stack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stack)


class DockerPreflightTests(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.object(stack.shutil, 'which', return_value='/usr/local/bin/docker'))
        self.enterContext(patch.object(stack.sys, 'platform', 'darwin'))

    def test_running_docker_does_not_launch_desktop(self):
        with patch.object(stack, 'docker_ready', return_value=True), patch.object(stack.subprocess, 'run') as run:
            stack.ensure_docker()
            run.assert_not_called()

    def test_stopped_desktop_recovers_after_waiting(self):
        with patch.object(stack, 'docker_ready', side_effect=[False, False, True]), patch.object(stack.subprocess, 'run') as run, patch.object(stack.time, 'sleep') as sleep:
            stack.ensure_docker()
            self.assertEqual(run.call_args.args[0], ['open', '-g', '-a', 'Docker'])
            sleep.assert_called_once_with(2)

    def test_missing_cli_fails_before_launch(self):
        with patch.object(stack.shutil, 'which', return_value=None), patch.object(stack.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'CLI is missing'):
                stack.ensure_docker()
            run.assert_not_called()

    def test_start_failure_is_actionable(self):
        with patch.object(stack, 'docker_ready', return_value=False), patch.object(stack.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'open')):
            with self.assertRaisesRegex(RuntimeError, 'Open it manually'):
                stack.ensure_docker()

    def test_readiness_wait_has_a_deadline(self):
        with patch.object(stack, 'docker_ready', return_value=False), patch.object(stack.subprocess, 'run'), patch.object(stack.time, 'monotonic', side_effect=[0, 91]):
            with self.assertRaisesRegex(RuntimeError, 'within 90 seconds'):
                stack.ensure_docker()

    def test_non_mac_does_not_launch_desktop(self):
        with patch.object(stack.sys, 'platform', 'linux'), patch.object(stack, 'docker_ready', return_value=False), patch.object(stack.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'Start the Docker daemon'):
                stack.ensure_docker()
            run.assert_not_called()

    def test_hung_docker_probe_is_not_ready(self):
        with patch.object(stack.subprocess, 'run', side_effect=subprocess.TimeoutExpired('docker', 3)):
            self.assertFalse(stack.docker_ready())

    def test_failed_preflight_preserves_release_and_skips_build(self):
        with patch.object(stack, 'ensure_docker', side_effect=RuntimeError('unavailable')), patch.object(stack, 'prepare_release') as build, patch.object(stack, 'stop') as stop, patch.object(stack, 'write') as write:
            with self.assertRaises(RuntimeError):
                stack.deploy()
            build.assert_not_called()
            stop.assert_not_called()
            write.assert_not_called()


if __name__ == '__main__':
    unittest.main()
