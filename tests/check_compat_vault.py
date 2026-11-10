"""Checks the vaults written by the Kotlin side:  python tests/check_compat_vault.py /tmp/compat1 "kotlin pass 2" """
import sys, io, json, hashlib
sys.path.insert(0, __import__('os').path.join(__import__('os').path.dirname(__import__('os').path.abspath(__file__)), '..', 'desktop', 'app'))
import vaultcore as vc
d = sys.argv[1]; pw = sys.argv[2]
v = vc.Vault(d + '/DriveVault/vault')
assert v.kdf_name() == "argon2id", "Kotlin's password reset should have upgraded the vault to Argon2id"
v.unlock(pw)
ev, pr = v.history_read()
print('python sees', len(v.entries), 'entries; history', len(ev), 'events; problems', pr)
assert not pr
for k, e in v.entries.items():
    if e['t'] == 'f' and e['p'] != 'trash':
        h = hashlib.sha256(b''.join(v.read_range(k))).hexdigest()
        print('  ', e['n'], e['s'], h[:12], 'o=' + str(e.get('o')))
print('last events:', [x['ev'] for x in ev[-4:]])
info = v.recovery_info(); print('recovery:', info)
v.lock_now()
kt = vc.Vault(d + '/kt/DriveVault/vault')                       # brand new vault made by Kotlin
assert kt.kdf_name() == "argon2id"
kt.unlock("via phone 1")
hello = [k for k, e in kt.entries.items() if e['n'] == 'hello.txt'][0]
assert b''.join(kt.read_range(hello)) == b'hello from kotlin'
kt.lock_now()
print('kotlin-made Argon2id vault opens in python: OK')
print('COMPAT OK')
