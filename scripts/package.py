"""Build-time metadata only; the distributed app has no Python dependency."""
import argparse, json, os, plistlib
from pathlib import Path
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('app', type=Path)
parser.add_argument('--xcode-info', type=Path, help='Write an input plist for Xcode to process, instead of the final bundle plist.')
args = parser.parse_args()
app = args.app
resources = app / 'Contents/Resources'
resources.mkdir(parents=True, exist_ok=True)
# Retire the old bundled installer even when reusing a previous build directory.
for legacy_resource in ('sound-link.apk', 'release.json'):
    (resources / legacy_resource).unlink(missing_ok=True)
mac_release = json.loads(Path(os.environ.get('SOUNDLINK_VERSION_FILE', 'apps/macos/version.json')).read_text())
info = {'CFBundleIdentifier':'app.soundlink.mac.dev', 'CFBundleName':'Sound Link', 'CFBundleDisplayName':'Sound Link',
        'CFBundleIconFile':'SoundLink.icns', 'CFBundleExecutable':'SoundLink', 'CFBundlePackageType':'APPL', 'CFBundleShortVersionString':mac_release['versionName'],
        'CFBundleVersion':str(mac_release['versionCode']), 'LSMinimumSystemVersion':'14.0', 'NSHighResolutionCapable':True,
        'NSLocalNetworkUsageDescription':'Sound Link 在局域网内与配对的手机建立加密连接。',
        'NSBluetoothAlwaysUsageDescription':'Sound Link 通过蓝牙让已配对的手机发现此 Mac 并读取最新地址，以恢复加密连接。',
        'NSBluetoothPeripheralUsageDescription':'Sound Link 广播本机连接入口，供已配对的手机恢复连接。',
        'NSAppleEventsUsageDescription':'耳机切换成功后，Sound Link 尝试暂停此前播放的 Music 或 Spotify。',
        'NSPrincipalClass':'NSApplication'}
# Optional build settings contain no defaults for any hosted service.
config_path = os.environ.get('SOUNDLINK_BUILD_CONFIG')
config = json.loads(Path(config_path).read_text()) if config_path else {}
info['CFBundleIdentifier'] = config.get('bundleIdentifier', 'app.soundlink.mac.dev')
info['CFBundleDisplayName'] = config.get('applicationName', 'Sound Link Dev')
info['SoundLinkSupportDirectory'] = config.get('supportDirectory', 'Sound Link Dev')
for source, target in [('websiteURL', 'SoundLinkWebsiteURL'), ('updateBaseURL', 'SoundLinkUpdateBaseURL')]:
    if config.get(source):
        info[target] = config[source]
if config.get('feedURL') or config.get('publicEDKey'):
    if not config.get('feedURL', '').startswith('https://') or not config.get('publicEDKey'):
        raise ValueError('An update feed requires HTTPS and a verification public key')
    info.update(SUFeedURL=config['feedURL'], SUPublicEDKey=config['publicEDKey'],
                SURequireSignedFeed=True, SUVerifyUpdateBeforeExtraction=True,
                SUEnableAutomaticChecks=False, SUAllowsAutomaticUpdates=False)
# Advertise supported languages to macOS and localize system permission prompts.
info['CFBundleDevelopmentRegion'] = 'en'
info['CFBundleLocalizations'] = ['en', 'zh-Hans']
permission_copy = {
    'en': {
        'NSLocalNetworkUsageDescription': 'Sound Link connects securely to your paired phone on the local network.',
        'NSBluetoothAlwaysUsageDescription': 'Sound Link connects your headphones and helps your paired phone discover this Mac.',
        'NSBluetoothPeripheralUsageDescription': 'Sound Link lets your paired phone discover this Mac and restore its connection.',
        'NSAppleEventsUsageDescription': 'After switching headphones, Sound Link can pause Music or Spotify on the previous device.'
    },
    'zh-Hans': {key: info[key] for key in ('NSLocalNetworkUsageDescription', 'NSBluetoothAlwaysUsageDescription', 'NSBluetoothPeripheralUsageDescription', 'NSAppleEventsUsageDescription')}
}
for locale, entries in permission_copy.items():
    folder = resources / f'{locale}.lproj'
    folder.mkdir(exist_ok=True)
    (folder / 'InfoPlist.strings').write_text('\n'.join(f'"{key}" = "{value}";' for key, value in entries.items()) + '\n')

info.update(permission_copy['en'])
info_path = app / 'Contents/Info.plist'
if args.xcode_info:
    # Xcode expands these settings and signs the finished bundle itself.
    info_path = args.xcode_info
    info.update(CFBundleIdentifier='$(PRODUCT_BUNDLE_IDENTIFIER)',
                CFBundleExecutable='$(EXECUTABLE_NAME)',
                LSMinimumSystemVersion='$(MACOSX_DEPLOYMENT_TARGET)')
    for key, setting in [('CFBundleShortVersionString', 'MARKETING_VERSION'), ('CFBundleVersion', 'CURRENT_PROJECT_VERSION')]:
        if os.environ.get(setting):
            info[key] = os.environ[setting]
info_path.parent.mkdir(parents=True, exist_ok=True)
with info_path.open('wb') as f: plistlib.dump(info, f)
