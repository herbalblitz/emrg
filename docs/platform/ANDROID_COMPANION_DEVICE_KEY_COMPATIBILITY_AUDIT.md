# Android Companion Device-Key Compatibility Audit

Status: implementation gate; no Android device-key generation is enabled.

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

## Required next decision

Choose one protocol-wide path before implementing the device-key manager:

1. **Selected: P-256 ECDSA.** Host-side Pairing v2 transaction verification and strict request-frame validation now use P-256 SubjectPublicKeyInfo and ECDSA/SHA-256.
2. **Still required:** implement AndroidKeyStore P-256 key generation and signing, runtime capability checks, and physical-device tests across the supported API range. Android's official Keystore guide documents EC key generation and SHA-256 ECDSA signing, but actual device/hardware support still needs runtime checks and physical-device tests.

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

P-256 is selected for the wire protocol and host-side proof verification. This audit does not claim that all Android API 26+ devices can create a hardware-backed P-256 key; live Pairing v2 remains blocked until Android implementation and physical-device validation pass.
