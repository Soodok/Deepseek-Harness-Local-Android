#!/usr/bin/env python3
"""Regression guards for Linux Bash startup, PATH, and managed child env.

The device companion exercises Bash, threads, and HTTP under the app seccomp
domain. These guards keep each required branch connected in both BPF trees.
"""
import importlib.util
import json
import re
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CPP = ROOT / 'app/src/main/cpp/dsh_alpine.c'
ENGINE = ROOT / 'app/src/main/java/app/dsh/mobile/engine'
checks = failed = 0

def ok(condition, message):
    global checks, failed
    checks += 1
    failed += not condition
    print(('  ok   ' if condition else '  FAIL ') + message)

ALLOW, TRAP, KILL = 0x7fff0000, 0x00030000, 0x80000000


def decode_bpf(text, numbers):
    """Encode macros as sock_filter bytes so u8 narrowing cannot be overlooked."""
    constants = {'LOADER_BASE_HI': 0x20, 'AUDIT_ARCH_AARCH64': 0xc00000b7,
                 'AUDIT_ARCH_X86_64': 0xc000003e, 'SECCOMP_RET_ALLOW': ALLOW,
                 'SECCOMP_RET_TRAP': TRAP, 'SECCOMP_RET_KILL_PROCESS': KILL}
    constants.update({'SYS_' + name: nr for name, nr in numbers.items()})
    ops = {'BPF_LD|BPF_W|BPF_ABS': 0x20, 'BPF_JMP|BPF_JEQ|BPF_K': 0x15,
           'BPF_JMP|BPF_JGT|BPF_K': 0x25, 'BPF_JMP|BPF_JA': 0x05,
           'BPF_ALU|BPF_AND|BPF_K': 0x54, 'BPF_RET|BPF_K': 0x06}
    offsets = {'nr': 0, 'arch': 4, 'instruction_pointer': 8, 'args': 16}
    encoded = bytearray()
    for line in text.splitlines():
        match = re.fullmatch(r'\s*BPF_(STMT|JUMP)[(](.*)[)],\s*', line)
        if not match:
            raise AssertionError('unparsed BPF instruction: ' + line)
        kind, fields = match.groups()
        op, rest = fields.split(',', 1)
        if kind == 'JUMP':
            value, jt, jf = rest.rsplit(',', 2)
            jt, jf = int(jt), int(jf)
        else:
            value, jt, jf = rest, 0, 0
        value = value.strip()
        field = re.search(r'offsetof[(]struct seccomp_data, (\w+)[)](?: [+] (\d+))?', value)
        k = offsets[field[1]] + int(field[2] or 0) if field else constants.get(value)
        if k is None:
            k = int(value, 0)
        encoded.extend(struct.pack('<HBBI', ops[op.strip()], jt, jf, k))
    assert len(encoded) >= 8 * 10, 'empty or incomplete filter'
    return bytes(encoded), list(struct.iter_unpack('<HBBI', encoded))


def bpf_result(program, nr, audit, ip=0x400000, args=(0, 0, 0, 0, 0, 0)):
    data = struct.pack('<iI7Q', nr, audit, ip, *(a & 0xffffffffffffffff for a in args))
    pc = acc = 0
    for _ in range(len(program)):
        assert 0 <= pc < len(program), 'BPF branch leaves the program'
        op, jt, jf, k = program[pc]
        pc += 1
        if op == 0x20:
            acc = struct.unpack_from('<I', data, k)[0]
        elif op == 0x54:
            acc &= k
        elif op == 0x15:
            pc += jt if acc == k else jf
        elif op == 0x25:
            pc += jt if acc > k else jf
        elif op == 0x05:
            pc += k
        elif op == 0x06:
            return k
        else:
            raise AssertionError('unsupported BPF opcode: ' + str(op))
    raise AssertionError('BPF did not terminate')


def verify_bpf(program, numbers, audit):
    for nr in [-1, *range(512), 0x40000000, 0x7fffffff]:
        for arg0 in (-100, 0, 7, 0x4100):
            assert bpf_result(program, nr, audit, ip=0x2000020000,
                              args=(arg0, 0, 0, 0, 0, 0)) == ALLOW, ('loader', nr, arg0)
    assert bpf_result(program, numbers['clone3'], audit) == TRAP
    assert bpf_result(program, numbers['clone3'], audit, ip=0x2100020000) == TRAP
    assert bpf_result(program, numbers['clone3'], 0) == KILL
    assert bpf_result(program, 0x7fffffff, audit) == ALLOW
    if 'getpgrp' in numbers:
        assert bpf_result(program, numbers['getpgrp'], audit) == TRAP
    for name in ('openat', 'newfstatat', 'unlinkat', 'statx'):
        assert bpf_result(program, numbers[name], audit, args=(-100, 0, 0, 0, 0, 0)) == TRAP, name
        assert bpf_result(program, numbers[name], audit, args=(7, 0, 0, 0, 0, 0)) == ALLOW, name
    for flags, child_stack, expected in ((0, 0, ALLOW), (0x100, 0, ALLOW),
                                         (0x4100, 0, TRAP), (0x4100, 4096, ALLOW),
                                         (0x4100, 1 << 32, ALLOW)):
        assert bpf_result(program, numbers['clone'], audit,
                          args=(flags, child_stack, 0, 0, 0, 0)) == expected


def main():
    src = CPP.read_text(encoding='utf-8')
    code = re.sub(r'//[^\n]*|/[*].*?[*]/', '', src, flags=re.S)
    spec = importlib.util.spec_from_file_location('bpf_generator', ROOT / 'scripts/gen_bpf_tree.py')
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)
    ok('clone3' in gen.NEEDED, 'both architectures intercept clone3 for libc fallback')
    ok('getpgrp' in gen.LEGACY_X86_64, 'x86 getpgrp enters the translation handler')
    ok(re.search(r'if [(]nr == SYS_clone3[)]\s*[{]\s*r\[0\] = [(]unsigned long long[)][(]long[)]-ENOSYS;\s*return;', code) is not None,
       'clone3 returns ENOSYS without reissuing the Android-denied syscall')
    ok(re.search(r'if [(]nr == SYS_getpgrp[)].*?raw6[(]SYS_getpgid, 0, 0, 0, 0, 0, 0[)]', code, re.S) is not None,
       'legacy getpgrp is implemented by getpgid(0)')
    wrapper = code.split('greg_t *g = u->uc_mcontext.gregs;', 1)[-1].split('#endif', 1)[0]
    ok('resume_ip' in wrapper and 'r[8]' in wrapper.split('g[REG_RAX] =', 1)[-1],
       'x86 retry restores syscall number separately from argument/result')
    for arch in ('aarch64', 'x86_64'):
        numbers, _ = gen.load_numbers(arch)
        ok(numbers.get('clone3') == 435, arch + ' clone3 uses NDK-derived ABI number')
        if arch == 'x86_64':
            ok(numbers.get('getpgrp') == 111, 'x86 getpgrp ABI number is included')
    paths = []
    for name in ('LinuxEngineLauncher.kt', 'LinuxContainerRunner.kt', 'LinuxSandbox.kt'):
        text = (ENGINE / name).read_text(encoding='utf-8')
        match = re.search(r'CONTAINER_PATH\s*=\s*"([^"]+)"', text)
        paths.append(match.group(1) if match else '')
    match = re.search(r'#define\s+DSH_DEFAULT_PATH\s+"([^"]+)"', src)
    paths.append(match.group(1) if match else '')
    ok(bool(paths[0]) and len(set(paths)) == 1, 'engine, command runner and loader use identical PATH')
    for i, path in enumerate(paths):
        entries = path.split(':')
        ok(entries[0] == '/eng/linux-bin' and '/eng/bin' not in entries,
           'PATH %d keeps gates first without bionic bash/node' % i)
        ok('/opt/node/bin' in entries and '/usr/bin' in entries and '/bin' in entries,
           'PATH %d resolves Ubuntu Node and distro tools' % i)
    for arch in ('aarch64', 'x86_64'):
        generated = subprocess.run([sys.executable, str(ROOT / 'scripts/gen_bpf_tree.py'), arch],
                                   capture_output=True, text=True, encoding='utf-8')
        jumps = [(int(a), int(b)) for a, b in re.findall(
            r'BPF_JUMP\([^,]+, [^,]+, (\d+), (\d+)\)', generated.stdout)]
        ok(generated.returncode == 0, arch + ' BPF generator exits successfully')
        ok(bool(jumps) and all(0 <= a <= 255 and 0 <= b <= 255 for a, b in jumps),
           arch + ' conditional BPF jumps fit sock_filter.u8 offsets')
        numbers, _ = gen.load_numbers(arch)
        audit = 0xc00000b7 if arch == 'aarch64' else 0xc000003e
        try:
            encoded, program = decode_bpf(generated.stdout, numbers)
            verify_bpf(program, numbers, audit)
            ok(True, arch + ' encoded BPF validates loader, guest, clone and dirfd branches')
            embedded = src.split('static const struct sock_filter f[] = {', 1)[1]
            embedded = embedded.split('#if defined(DSH_ARCH_AARCH64)', 1)[1]
            embedded = embedded.split('#else', 1)[0] if arch == 'aarch64' else embedded.split('#else', 1)[1].split('#endif', 1)[0]
            ok(embedded.strip() == generated.stdout.strip(), arch + ' embedded filter equals generator output')
            abi = 'arm64-v8a' if arch == 'aarch64' else arch
            binary = ROOT / 'app/src/main/jniLibs' / abi / 'libdsh_alpine.so'
            ok(encoded in binary.read_bytes(), arch + ' binary contains the exact encoded filter')
            # Recreate the old JEQ without the JA trampoline, including C's u8 narrowing.
            old = list(program[:5]) + list(program[6:])
            old[4] = (0x15, (len(old) - 7) & 255, 0, 0x20)
            detected = False
            try:
                verify_bpf(old, numbers, audit)
            except AssertionError:
                detected = True
            ok(detected if arch == 'x86_64' else not detected,
               arch + ' old jump reproduces the x86 overflow boundary')
        except (AssertionError, KeyError, ValueError, struct.error) as error:
            ok(False, arch + ' BPF validation: ' + str(error))
    cfg = (ENGINE / 'EngineConfig.kt').read_text(encoding='utf-8')
    ok('File(root, if (guestView) "linux-bin" else "bin")' in cfg,
       'Linux gate assets are deployed separately from native runtime bin')
    linux_deploy = cfg.split('fun deployLinuxAgentGates', 1)[1].split('private fun applyAgentGates', 1)[0]
    ok('applySuGate' in linux_deploy and 'guestView = true' in linux_deploy,
       'Linux deployment keeps the privilege-mode su mask')
    ok('"/eng/linux-bin/mcp"' in cfg, 'MCP overlay uses the Linux gate directory')
    cap = (ROOT / 'app/src/main/java/app/dsh/mobile/engine/cap/LinuxCap.kt').read_text(encoding='utf-8')
    ok('.put("interp", opt.interpPath)' in cap, 'Linux status exposes the active guest interpreter for ABI-safe probes')
    supervisor = (ROOT / 'app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt').read_text(encoding='utf-8')
    overlay_fn = re.search(r'fun ensureAndroidOverlay\(.*mode: EngineModeSelector\.Mode', cfg, re.S)
    resolve_at = supervisor.find('val engineMode = EngineModeSelector.resolve(ctx)')
    overlay_at = supervisor.find('EngineConfig.ensureAndroidOverlay(ctx, engineMode)')
    ok(overlay_fn is not None, 'overlay accepts the resolved launch mode')
    ok(resolve_at >= 0 and overlay_at > resolve_at, 'Linux mode resolves before MCP overlay generation')
    launcher = (ENGINE / 'LinuxEngineLauncher.kt').read_text(encoding='utf-8')
    ok('DSH_ANDROID_PRIV_MODE=' in launcher, 'Linux engine exports actual privilege mode')
    plugin = ROOT / 'app/src/main/assets/android-plugins/container-env.mjs'
    ok(plugin.is_file(), 'managed container environment contributor is bundled')
    ok('name: ./android-plugins/container-env.mjs' in cfg and '"container-env.mjs"' in cfg,
       'container env contributor is deployed and registered by the overlay')
    if plugin.is_file():
        js = r"""
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
const plugin = await import(pathToFileURL(process.argv[1]).href);
let contribution;
plugin.apply({shellEnv:{register:c=>{contribution=c;}}});
assert(contribution);
const keys=['DSH_ANDROID_CONTAINER','DSH_NODE','DSH_DNS_PORT',
'DSH_TERMINAL_RUNNER','DSH_TERMINAL_ROOTFS','DSH_TERMINAL_INTERP','DSH_TERMINAL_BINDS'];
for(const key of keys) delete process.env[key];
assert.deepEqual(contribution.resolve(),{});
for(const [key,value] of Object.entries({DSH_ANDROID_CONTAINER:'1',DSH_NODE:'/opt/node/bin/node',
DSH_DNS_PORT:'15353',DSH_TERMINAL_RUNNER:'/data/runner',DSH_TERMINAL_ROOTFS:'/data/root fs',
DSH_TERMINAL_INTERP:'/lib/ld.so',DSH_TERMINAL_BINDS:'/eng:/data/engine\n/ws:/data/work space'}))process.env[key]=value;
process.env.DSH_FAKE_SECRET='must-not-forward';
let actual=contribution.resolve();
assert.deepEqual(Object.keys(actual).sort(),keys.sort());
for(const key of keys){assert(contribution.variables[key]?.description);assert.equal(actual[key],process.env[key]);}
process.env.DSH_DNS_PORT='25353';
assert.equal(contribution.resolve().DSH_DNS_PORT,'25353');
assert(!Object.hasOwn(actual,'DSH_FAKE_SECRET'));
console.log('container env: native absence, whitelist, multiline binds and fresh DNS passed');
"""
        result = subprocess.run(['node', '--input-type=module', '-e', js, str(plugin)],
                                capture_output=True, text=True, encoding='utf-8')
        ok(result.returncode == 0, 'managed env behavior: ' + (result.stdout or result.stderr).strip())
    print('checks=%d failed=%d' % (checks, failed))
    return 1 if failed else 0

if __name__ == '__main__':
    sys.exit(main())
