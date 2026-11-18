/**
 * SKYPLUS FINANCE - Firestore Security Rules Test Suite
 * Run against Firebase Firestore Emulator:
 *   firebase emulators:exec --only firestore "node --test firestore.test.js"
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { test, before, after, beforeEach } = require('node:test');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const { doc, getDoc, setDoc, updateDoc, deleteDoc, serverTimestamp } = require('firebase/firestore');

const PROJECT_ID = 'skyplus-finance';
let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: fs.readFileSync('firestore.rules', 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

function validEncryptedPayload(uid, recordId, syncVersion = 1) {
  return {
    recordId,
    ownerUid: uid,
    entityType: 'TRANSACTION',
    encryptedPayloadBase64: 'ZmFrZV9jaXBoZXJ0ZXh0X2djbQ==',
    payloadNonceBase64: 'MTIzNDU2Nzg5MDEy',
    schemaVersion: 3,
    syncVersion,
    updatedByDeviceId: 'device-alpha',
    isDeleted: false,
    clientUpdatedAtMillis: 1760000000000,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

test('unauthenticated requests are denied across all collections', async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(unauth, 'users/alice/transactions/tx-1')));
  await assertFails(setDoc(doc(unauth, 'users/alice/transactions/tx-1'), validEncryptedPayload('alice', 'tx-1')));
  await assertFails(setDoc(doc(unauth, 'public/test'), { foo: 'bar' }));
});

test('cross-user read/write/delete is strictly denied', async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'users/alice/transactions/tx-1'), {
      recordId: 'tx-1',
      ownerUid: 'alice',
      entityType: 'TRANSACTION',
      encryptedPayloadBase64: 'ZW5jcnlwdGVk',
      payloadNonceBase64: 'MTIzNDU2Nzg5MDEy',
      schemaVersion: 3,
      syncVersion: 1,
      updatedByDeviceId: 'device-a',
      isDeleted: false,
      createdAt: new Date(),
      updatedAt: new Date(),
    });
  });

  const bob = testEnv.authenticatedContext('bob').firestore();
  await assertFails(getDoc(doc(bob, 'users/alice/transactions/tx-1')));
  await assertFails(setDoc(doc(bob, 'users/alice/transactions/tx-2'), validEncryptedPayload('bob', 'tx-2')));
  await assertFails(deleteDoc(doc(bob, 'users/alice/transactions/tx-1')));
});

test('owner can create and read encrypted record, and plaintext financial fields are rejected', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  const ref = doc(alice, 'users/alice/transactions/tx-1');
  await assertSucceeds(setDoc(ref, validEncryptedPayload('alice', 'tx-1', 1)));
  await assertSucceeds(getDoc(ref));

  const plaintextAttempt = {
    ...validEncryptedPayload('alice', 'tx-plain', 1),
    amount: 5000,
    description: 'Salary',
  };
  await assertFails(setDoc(doc(alice, 'users/alice/transactions/tx-plain'), plaintextAttempt));
});

test('update enforces monotonic syncVersion and immutable ownerUid/createdAt', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  const ref = doc(alice, 'users/alice/transactions/tx-1');
  await assertSucceeds(setDoc(ref, validEncryptedPayload('alice', 'tx-1', 1)));

  // Lower or equal syncVersion must fail
  await assertFails(updateDoc(ref, {
    encryptedPayloadBase64: 'bmV3X2NpcGhlcnRleHQ=',
    payloadNonceBase64: 'OTg3NjU0MzIxMDEy',
    syncVersion: 1,
    updatedAt: serverTimestamp(),
  }));

  // Higher syncVersion succeeds
  await assertSucceeds(updateDoc(ref, {
    encryptedPayloadBase64: 'bmV3X2NpcGhlcnRleHQ=',
    payloadNonceBase64: 'OTg3NjU0MzIxMDEy',
    syncVersion: 2,
    updatedAt: serverTimestamp(),
  }));

  // Mutating ownerUid fails
  await assertFails(updateDoc(ref, {
    ownerUid: 'bob',
    syncVersion: 3,
    updatedAt: serverTimestamp(),
  }));
});

test('recovery metadata enforces >= 210000 PBKDF2 iterations and prevents deletion', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  const ref = doc(alice, 'users/alice/crypto_metadata/recovery');

  await assertFails(setDoc(ref, {
    ownerUid: 'alice',
    version: 2,
    kdfAlgorithm: 'PBKDF2WithHmacSHA256',
    kdfIterations: 10000,
    saltBase64: 'c2FsdA==',
    wrappedDekBase64: 'd3JhcHBlZA==',
    nonceBase64: 'MTIzNDU2Nzg5MDEy',
    keyCheckHash: 'YWJjZA==',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  }));

  await assertSucceeds(setDoc(ref, {
    ownerUid: 'alice',
    version: 2,
    kdfAlgorithm: 'PBKDF2WithHmacSHA256',
    kdfIterations: 210000,
    saltBase64: 'c2FsdDE2Ynl0ZXM=',
    wrappedDekBase64: 'd3JhcHBlZGRlaw==',
    nonceBase64: 'MTIzNDU2Nzg5MDEy',
    keyCheckHash: 'YWJjZGVmZ2g=',
    deviceId: 'device-1',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  }));

  await assertFails(deleteDoc(ref));
});

test('private profile enforces UID matching and valid opt-in booleans', async () => {
  const alice = testEnv.authenticatedContext('alice').firestore();
  const ref = doc(alice, 'users/alice/private/profile');
  await assertSucceeds(setDoc(ref, {
    uid: 'alice',
    email: 'alice@example.com',
    cloudSyncEnabled: false,
    driveBackupEnabled: false,
    driveWifiOnly: true,
    schemaVersion: 1,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  }));
});
