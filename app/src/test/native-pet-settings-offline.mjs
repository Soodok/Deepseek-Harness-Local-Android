import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

// Source contracts only: Flutter/Android runtime coverage is separate.
const root = new URL('../../../', import.meta.url);
const paths = {
  model: 'flutter_ui/lib/src/model/settings_model.dart',
  page: 'flutter_ui/lib/src/ui/settings/settings_page.dart',
  bridge: 'flutter_ui/lib/src/bridge/host_bridge.dart',
  native: 'app/src/main/java/app/dsh/mobile/NativeChatActivity.kt',
  detector: 'app/src/main/java/app/dsh/mobile/pet/PetPluginDetector.kt',
  service: 'app/src/main/java/app/dsh/mobile/pet/PetOverlayService.kt',
};
const sources = Object.fromEntries(Object.entries(paths).map(([key, path]) => [
  key, readFileSync(new URL(path, root), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, ''),
]));
const contracts = {
  'pet is a named settings destination, not an enable toggle': s => {
    assert.match(s.model, /^\s*pet\('悬浮桌宠',\s*'[^']+'\),/m);
  },
  'pet appears once in display and personalization with its own icon': s => {
    const group = s.page.match(/_SettingsGroup\('显示与个性化',\s*\[([^\]]*)\]/)?.[1];
    assert.ok(group, 'display and personalization group exists');
    assert.equal((group.match(/SettingsDestination\.pet\b/g) ?? []).length, 1);
    const groups = s.page.slice(s.page.indexOf('const _groups ='));
    assert.equal((groups.match(/SettingsDestination\.pet\b/g) ?? []).length, 1);
    assert.match(s.page, /SettingsDestination\.pet\s*=>\s*\(Icons\.pets_rounded,\s*colors\.thought\)/);
  },
  'the existing bridge forwards the destination name and fails closed': s => {
    assert.match(s.bridge, /openSettingsSection\(SettingsDestination destination\)\s*=>\s*_open\('openSettingsSection',\s*\{'section': destination\.name\}\)/);
    assert.match(s.bridge, /invokeMethod<bool>\(method, arguments\) == true/);
    assert.match(s.bridge, /on MissingPluginException\s*\{\s*return false;/);
    assert.match(s.bridge, /on PlatformException\s*\{\s*return false;/);
  },
  'Android routes pet to the existing settings Activity only': s => {
    const body = s.native.match(/private fun openSettingsSection\(section: String\?\): Boolean \{([\s\S]*?)\n    \}/)?.[1];
    assert.ok(body, 'existing section dispatcher exists');
    assert.match(body, /"pet"\s*->\s*Intent\(this, app\.dsh\.mobile\.pet\.PetSettingsActivity::class\.java\)/);
    assert.equal((body.match(/"pet"\s*->/g) ?? []).length, 1);
    assert.match(body, /else -> return false/);
    assert.match(body, /return runCatching \{ startActivity\(destination\) \}\.isSuccess/);
    assert.doesNotMatch(s.native, /PetPrefs|PetOverlayService|TYPE_APPLICATION_OVERLAY|ACTION_MANAGE_OVERLAY_PERMISSION/);
  },
  'the shared detector retains all three hard gates and bridge minimum': s => {
    assert.match(s.detector, /MIN_BRIDGE_VERSION = "0\.3\.1"/);
    assert.match(s.detector, /bridgeReady: Boolean get\(\) = bridgeInstalled && bridgeEnabled && bridgeVersionOk/);
    assert.match(s.detector, /fun canEnable\(ctx: Context\): Boolean =\s*Settings\.canDrawOverlays\(ctx\) && bridgeGateOpen\(ctx\) && packReady\(ctx\)/);
  },
  'existing restore requires opt-in, common gates and no running service': s => {
    assert.match(s.service, /fun restoreIfEnabled\(ctx: Context\) \{\s*if \(!PetPrefs\.enabled\(ctx\)\) return\s*if \(!PetPluginDetector\.canEnable\(ctx\)\) return\s*if \(isRunning\(\)\) return\s*runCatching \{ start\(ctx\) \}/);
  },
};
for (const [name, check] of Object.entries(contracts)) test(name, () => check(sources));

test('each smallest wiring or gate mutation is rejected without editing the worktree', t => {
  try {
    for (const check of Object.values(contracts)) check(sources);
  } catch {
    t.skip('mutation calibration needs a fully wired baseline');
    return;
  }
  const mutations = [
    ['model', /^\s*pet\([^\n]+\n/m, '', 'remove destination'],
    ['page', /    SettingsDestination\.pet,/, '', 'remove visible row'],
    ['page', /Icons\.pets_rounded/, 'Icons.palette_rounded', 'remove dedicated icon'],
    ['bridge', /'section': destination\.name/, "'section': 'plugins'", 'misroute bridge'],
    ['native', /"pet" -> Intent\(this, app\.dsh\.mobile\.pet\.PetSettingsActivity::class\.java\)/, '"pet" -> Intent(this, PluginStoreActivity::class.java)', 'wrong Activity'],
    ['native', /else -> return false/, 'else -> Intent(this, app.dsh.mobile.pet.PetSettingsActivity::class.java)', 'unknown route bypass'],
    ['native', /private fun openSettingsSection/, 'private fun autoEnablePet() { PetPrefs.setEnabled(this, true) }\n    private fun openSettingsSection', 'auto enable bypass'],
    ['detector', /Settings\.canDrawOverlays\(ctx\) && /, '', 'drop permission gate'],
    ['detector', /bridgeInstalled && bridgeEnabled && bridgeVersionOk/, 'bridgeInstalled && bridgeVersionOk', 'drop enabled bridge gate'],
    ['detector', /&& packReady\(ctx\)/, '', 'drop pack gate'],
    ['detector', /MIN_BRIDGE_VERSION = "0\.3\.1"/, 'MIN_BRIDGE_VERSION = "0.1.0"', 'accept incompatible bridge'],
    ['service', /if \(!PetPrefs\.enabled\(ctx\)\) return/, '', 'drop opt-in gate'],
  ];
  for (const [key, pattern, replacement, name] of mutations) {
    const mutated = { ...sources, [key]: sources[key].replace(pattern, replacement) };
    assert.notEqual(mutated[key], sources[key], 'mutation anchor: ' + name);
    assert.throws(() => {
      for (const check of Object.values(contracts)) check(mutated);
    }, assert.AssertionError, name);
  }
  t.diagnostic(mutations.length + ' in-memory mutations rejected');
});
