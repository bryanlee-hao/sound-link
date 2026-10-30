import json
import os
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class MetadataTests(unittest.TestCase):
    def generate(self, config=None):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        app = Path(temp.name) / 'Sound Link.app'
        env = {k: v for k, v in os.environ.items() if not k.startswith('SOUNDLINK_')}
        if config is not None:
            settings = Path(temp.name) / 'settings.json'
            settings.write_text(json.dumps(config))
            env['SOUNDLINK_BUILD_CONFIG'] = str(settings)
        subprocess.run([sys.executable, str(ROOT / 'scripts/package.py'), str(app)], cwd=ROOT, env=env, check=True, capture_output=True)
        return plistlib.loads((app / 'Contents/Info.plist').read_bytes())

    def test_development_build_has_no_remote_update_or_private_identity(self):
        info = self.generate()
        self.assertEqual(info['CFBundleIdentifier'], 'app.soundlink.mac.dev')
        self.assertEqual(info['SoundLinkSupportDirectory'], 'Sound Link Dev')
        for key in ['SUFeedURL', 'SUPublicEDKey', 'SoundLinkWebsiteURL', 'SoundLinkUpdateBaseURL']:
            self.assertNotIn(key, info)
        self.assertIn('NSBluetoothAlwaysUsageDescription', info)

    def test_custom_channel_is_injected_with_signature_checks(self):
        info = self.generate({'feedURL': 'https://updates.example.test/appcast.xml', 'publicEDKey': 'fixture-key', 'bundleIdentifier': 'org.example.soundlink', 'supportDirectory': 'Example'})
        self.assertEqual(info['CFBundleIdentifier'], 'org.example.soundlink')
        self.assertEqual(info['SoundLinkSupportDirectory'], 'Example')
        self.assertTrue(info['SURequireSignedFeed'])
        self.assertTrue(info['SUVerifyUpdateBeforeExtraction'])

    def test_partial_update_configuration_fails(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.generate({'feedURL': 'https://updates.example.test/appcast.xml'})

if __name__ == '__main__': unittest.main()
