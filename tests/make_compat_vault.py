"""Creates test vaults with the desktop code for the Kotlin compatibility test:
   python tests/make_compat_vault.py /tmp/compat1   then   cd android && ./gradlew :core:test --rerun -Ddv.testdir=/tmp/compat1
   - DriveVault/vault      : a pre-1.8 style vault (PBKDF2 password KDF); Kotlin resets its password, which upgrades it to Argon2id
   - py2/DriveVault/vault  : an Argon2id vault made by Python"""
import sys, io, os, json, hashlib, shutil
sys.path.insert(0, __import__('os').path.join(__import__('os').path.dirname(__import__('os').path.abspath(__file__)), '..', 'desktop', 'app'))
import vaultcore as vc
vc.ITERATIONS = vc.KEY_ITERATIONS = vc.QUESTION_ITERATIONS = 1000
vc.ARGON2 = {"m": 1024, "t": 2, "p": 1}
d = sys.argv[1]; shutil.rmtree(d, ignore_errors=True)
vc.NEW_KDF = "pbkdf2-sha256"
v = vc.Vault(d + '/DriveVault/vault'); v.create('py password 1')
vc.NEW_KDF = "argon2id"
assert v.kdf_name() == "pbkdf2-sha256"
docs = v.mkdir('root', 'Docs'); sub = v.mkdir(docs, 'Sub')
files = {}
for name, parent, size in [('empty.txt', 'root', 0), ('one.bin', docs, vc.CHUNK), ('big.bin', sub, 3 * vc.CHUNK + 777), ('తెలుగు ఫైల్.txt', 'root', 50)]:
    data = os.urandom(size); eid = v.add_file(parent, name, io.BytesIO(data)); files[eid] = hashlib.sha256(data).hexdigest()
t = v.add_file('root', 'trashed.txt', io.BytesIO(b'bye')); v.trash([t])
v.reorder('root', [k for k, e in v.entries.items() if e['p'] == 'root' and e['t'] == 'f'][::-1])
key = v.set_recovery_key('py password 1')
v.set_questions('py password 1', ['Q1?', 'Q2?', 'Q3?'], ['Blue Bell School!', 'తెలుగు పాఠశాల', 'Rex_99'])
v.history_append({'t': 1, 'ev': 'created', 'host': 'py'}); v.history_append({'t': 2, 'ev': 'encrypted', 'name': 'one.bin'})
v.lock_now()
a2 = vc.Vault(d + '/py2/DriveVault/vault'); a2.create('py argon 1')
assert a2.kdf_name() == "argon2id"
a2_file = a2.add_file('root', 'argon.txt', io.BytesIO('made by python with argon2id'.encode())); a2.lock_now()
json.dump({'key': key, 'files': files, 'trashed': t, 'argon_file': a2_file}, open(d + '/expect.json', 'w'))
print('python vaults ready', key)
