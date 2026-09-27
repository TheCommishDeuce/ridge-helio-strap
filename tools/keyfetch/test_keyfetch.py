# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27", "cryptography>=42", "qrcode>=7.4", "pytest>=8"]
# ///
"""Offline checks for keyfetch: the encrypted sign-in body matches the old app byte for byte.

    uv run --script tools/keyfetch/test_keyfetch.py
"""

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import keyfetch  # noqa: E402

# Produced by the old app's encodeForm + encryptZeppPayload (healthee@049c9ad) for the
# same email/password — parity with code that signed in against the real service.
LEGACY_FORM = (
    "emailOrPhone=owner%2Btest%40example.com&state=REDIRECTION&client_id=HuaMi&password=p%40ss+w%C3%B6rd%26%3D1"
    "&redirect_uri=https%3A%2F%2Fs3-us-west-2.amazonaws.com%2Fhm-registration%2Fsuccesssignin.html"
    "&region=us-west-2&token=access&token=refresh&country_code=US"
)
LEGACY_CIPHER = (
    "815932a2f5d6cd860efe3cef4a004fda1c59eee58fce5a6ef1722ec410eb032f0bb23d6c552f0f5d52c89b775bef7718a51b68bd4b"
    "0dfd84c6b14237622a6dcac969c6620e9fcbee7d14d3a49daf82a0329e1d6eb9114aaa3b2994ed07e6ede291661bde798dabfe3543"
    "0000f7794bab954889c0c323b1d99fa6bd827483dd66f208c56550b70142c7d183550785a7f1ba243bc74abf0d77e1dc34be63051d"
    "15feb96ac4b8c4d1856b514b90d0c406ec88b06fa2fa3e9b0d2eff3ebc6a03dd1e5ea59854cf3c39c5fb8519b8a3978f573948f177"
    "fd39566d4c75df571c526ae520fc80b8273d5332b34d2c03c4ebf29bf4cdb60afd6b6f7d383ebecc830567f69e7380511ce19cf5c5"
    "df6ef6c12cf084"
)


def test_token_form_matches_legacy_encoding():
    assert keyfetch.token_form("owner+test@example.com", "p@ss wörd&=1").decode() == LEGACY_FORM


def test_encrypted_body_matches_legacy():
    body = keyfetch.encrypt_payload(keyfetch.token_form("owner+test@example.com", "p@ss wörd&=1"))
    assert body.hex() == LEGACY_CIPHER


def test_parse_device_accepts_string_additional_info_and_rejects_bad_keys():
    good = {"macAddress": "aa:bb:cc:dd:ee:ff", "activeStatus": 1,
            "additionalInfo": json.dumps({"auth_key": "0x" + "ab" * 16, "deviceName": "Helio Strap"})}
    assert keyfetch.parse_device(good) == {"mac": "AA:BB:CC:DD:EE:FF", "key": "ab" * 16, "name": "Helio Strap", "active": True}
    assert keyfetch.parse_device({"macAddress": "x", "additionalInfo": {"auth_key": "abc"}}) is None
    assert keyfetch.parse_device({"additionalInfo": {"auth_key": "ab" * 16}}) is None


def test_qr_payload_is_versioned_json():
    payload = json.loads(keyfetch.qr_payload({"mac": "AA:BB:CC:DD:EE:FF", "key": "ab" * 16}))
    assert payload == {"v": 1, "mac": "AA:BB:CC:DD:EE:FF", "key": "ab" * 16}


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
