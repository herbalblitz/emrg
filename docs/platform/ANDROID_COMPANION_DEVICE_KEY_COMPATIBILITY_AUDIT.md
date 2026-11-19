# Android Companion Device-Key Compatibility Audit

Status: P-256 Android Keystore key-manager and transport-neutral Pairing v2 client foundations added; physical-device validation and live integration remain blocked.

Date: 2026-11-19

## Scope

The Companion Pairing v2 prototype initially used Ed25519, but the cross-platform protocol decision is now P-256 ECDSA. The Android application has `minSdk = 26`. This audit records why P-256 is the better documented Android Keystore fit and what still requires device validation.

## Findings

- The Android Keystore security guide documents generating EC key pairs with `KeyPairGenerator`, `KeyGenParameterSpec`, and the `AndroidKeyStore` provider. Its documented signing example uses `SHA256withECDSA`.
- The guide describes StrongBox's required algorithm subset as RSA 2048, AES, ECDSA/ECDH P-256, and HMAC-SHA256. Ed25519 is not in that StrongBox subset.
- The public Android Keystore guide does not establish an Ed25519 key-generation and signing guarantee for every API 26+ device.
- Provider availability and secure-hardware algorithm support are separate questions. A generic JCA provider may support Ed25519 without proving that the private key is non-exportable in Android Keystore.
- Therefore neither API level alone nor a successful `Signature.getInstance("Ed25519")` lookup is sufficient evidence that an Ed25519 private key is protected by Android Keystore.

Official reference: https://developer.android.com/privacy-and-security/keystore

## Current compatibility decision

The host protocol now explicitly identifies `P-256` and validates DER-encoded P-256 SubjectPublicKeyInfo keys plus ECDSA/SHA-256 signatures. Do not add a silent algorithm fallback.

Do not generate a P-256 private key in a software provider and persist its private bytes under the existing AES credential-store key as if it were an Android Keystore device identity. That would weaken the explicit non-exportable device-key invariant in Pairing v2.

The Android app may keep `minSdk = 26` for its existing UI, QR parsing, credential storage, and other features. Pairing v2 must remain unavailable on a device unless a concrete provider/key-generation/signing capability check succeeds and the private key is proven to be held by the intended keystore provider. Capability failure must be explicit and fail closed.

## Implementation status

- Host Pairing v2 transaction verification now accepts only P-256 EC keys encoded as DER SubjectPublicKeyInfo and verifies ECDSA/SHA-256 DER signatures.
- The strict unauthenticated pairing-frame validator requires `key_algorithm: P-256`, bounded canonical Base64URL public keys, and bounded canonical Base64URL signatures. v1 authentication remains a separate parser.
- Host pairing transaction and protocol tests have been migrated to P-256; GitHub Actions run #149 passed on Ubuntu and Windows (202 passed on Ubuntu and 198 passed with 4 skipped on Windows).
- Added `AndroidKeystoreDeviceKeyManager` to create/load P-256 EC identities in Android Keystore, return only the encoded public key, sign challenges with `SHA256withECDSA`, and refuse exportable private keys. It compares the full curve, generator, order, and cofactor against `secp256r1` rather than relying only on a 256-bit field-size check. It has no software-key fallback.
- Added a transport-neutral `PairingV2Client` that builds P-256 request/redeem frames, validates transaction/device/key-digest/expiry/nonce/scope bindings in the exact host challenge, and signs the exact UTF-8 challenge bytes. JVM tests use an injected fake signer; they do not claim Android Keystore hardware coverage.
- The manager/client are not yet wired into the Android pairing coordinator, pinned WSS transport, or a host WSS pairing route. Device security-level reporting, API 26 physical-device testing, process-restart testing, and end-to-end Python/Android signature interoperability remain open.

This protocol decision is reflected in the wire schema, host validator, transaction core, and host tests. Android parser/coordinator integration and cross-platform test vectors remain before live pairing can be enabled.

## Validation still required

- API 26 and the lowest supported Android release on physical devices.
- At least one current Android release.
- AndroidKeyStore key creation and retrieval after process restart.
- Private-key non-exportability and key security level where available.
- Sign/verify interoperability against the Python host implementation.
- Unsupported provider, missing algorithm, keystore reset, and key invalidation all fail closed.
- No fallback that exports or stores the private key in app preferences/files.

## Status

P-256 is selected for the wire protocol. The Android Keystore key-manager foundation now exists, but this audit does not claim that all Android API 26+ devices can create a hardware-backed P-256 key. Live Pairing v2 remains blocked until Android build validation, physical-device testing, and coordinator integration pass.
