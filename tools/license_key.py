#!/usr/bin/env python3
"""Generate an offline activation key for a device shown under Settings > Account Details.

Usage: python3 tools/license_key.py 7A:3F:0C:91:B2:E4 [LIFE|M001|M003|M006|M012]

The key format and secret must match LicenseKeys in
app/src/main/java/com/maslarski/iptv/domain/license/LicenseRepository.kt.
"""
import hashlib
import hmac
import sys

SECRET = b"iptv-player-activation-v1"
PLANS = {"LIFE": "Lifetime", "M001": "1 month", "M003": "3 months", "M006": "6 months", "M012": "12 months"}


def make_key(device_id: str, plan: str) -> str:
    plan = plan.upper()
    if plan not in PLANS:
        raise SystemExit(f"unknown plan {plan}; choose one of {', '.join(PLANS)}")
    digest = hmac.new(SECRET, f"{device_id.strip().upper()}:{plan}".encode(), hashlib.sha256).hexdigest().upper()[:12]
    return f"{plan}-{digest[0:4]}-{digest[4:8]}-{digest[8:12]}"


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    device = sys.argv[1]
    plan = sys.argv[2] if len(sys.argv) > 2 else "LIFE"
    print(f"{PLANS[plan.upper()]} key for {device}: {make_key(device, plan)}")
