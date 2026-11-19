# Android Companion Device-Key Compatibility Audit

Status: implementation gate; no Android device-key generation is enabled.

Date: 2026-11-19

## Scope

The Companion Pairing v2 host prototype currently accepts only raw Ed25519 public keys (32 bytes) and verifies 64-byte Ed25519 signatures. The Android application has `minSdk = 26`. This audit checks whether the current Android Keystore design can safely promise that algorithm across the supported Android range.

## Findings

- The Android Keystore security guide documents generating EC key pairs with `KeyPairGenerator`, `KeyGenParameterSpec`, and the `AndroidKeyStore` provider. Its documented signing example uses `SHA256withECDSA`.
- The guide describes StrongBox's required algorithm subset as RSA 2048, AES, ECDSA/ECDH P-256, and HMAC-SHA256. Ed25519 is not in that StrongBox subset.
- The public Android Keystore guide does not establish an Ed25519 key-generation and signing guarantee for every API 26+ device.
- Provider availability and secure-hardware algorithm support are separate questions. A generic JCA provider may support Ed25519 without proving that the private key is non-exportable in Android Keystore.
- Therefore neither API level alone nor a successful `Signature.getInstance("Ed25519")` lookup is sufficient evidence that an Ed25519 private key is protected by Android Keystore.

Official reference: https://developer.android.com/privacy-and-security/keystore

## Current compatibility decision

Do not implement a silent Ed25519-to-ECDSA fallback. The host currently validates Ed25519 keys and signatures; sending a different algorithm would fail protocol validation and silently changing the cryptographic contract would create an interoperability and review hazard.

Do not generate an Ed25519 key in a software provider and persist its private bytes under the existing AES credential-store key as if it were an Android Keystore device identity. That would weaken the explicit non-exportable device-key invariant in Pairing v2.

The Android app may keep `minSdk = 26` for its existing UI, QR parsing, credential storage, and other features. Pairing v2 must remain unavailable on a device unless a concrete provider/key-generation/signing capability check succeeds and the private key is proven to be held by the intended keystore provider. Capability failure must be explicit and fail closed.

## Required next decision

Choose one protocol-wide path before implementing the device-key manager:

1. **Keep Ed25519:** establish the minimum supported Android versions/devices and verify AndroidKeyStore Ed25519 generation, persistence across process restart, and signing on physical devices. Add runtime capability checks; do not infer support from API level alone.
2. **Adopt P-256 ECDSA:** update the host protocol, public-key encoding, signature verification, canonical challenge tests, and Android implementation together. Android's official Keystore guide documents EC key generation and SHA-256 ECDSA signing, but actual device/hardware support still needs runtime checks and physical-device tests.

This is a protocol decision, not an Android-only implementation detail. It must be reflected in the wire schema, host validator, transaction core, Android parser/coordinator, and cross-platform test vectors before live pairing is enabled.

## Validation still required

- API 26 and the lowest supported Android release on physical devices.
- At least one current Android release.
- AndroidKeyStore key creation and retrieval after process restart.
- Private-key non-exportability and key security level where available.
- Sign/verify interoperability against the Python host implementation.
- Unsupported provider, missing algorithm, keystore reset, and key invalidation all fail closed.
- No fallback that exports or stores the private key in app preferences/files.

## Status

This is an audit and blocking decision record only. It does not claim Android Ed25519 support, does not add a fallback algorithm, and does not enable live Pairing v2.
