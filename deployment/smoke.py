"""Check public health and fail-closed API security on the local edge."""
import urllib.request
import urllib.error
import json
import time
base = "http://127.0.0.1:18080"
for service in ["auth", "pet", "document", "notification"]:
    for attempt in range(120):
        try:
            with urllib.request.urlopen(f"{base}/{service}/actuator/health", timeout=2) as response:
                if json.load(response)["status"] != "UP": raise ValueError("Not ready")
            break
        except (urllib.error.URLError, ValueError):
            if attempt == 119: raise
            time.sleep(1)
    for headers in [{}, {"Authorization": "Bearer forged-token"}]:
        try:
            urllib.request.urlopen(urllib.request.Request(f"{base}/{service}/api/v1/me", headers=headers), timeout=10)
        except urllib.error.HTTPError as error:
            assert error.code == 401, (service, error.code)
        else: raise AssertionError(f"{service} accepted an unauthorized request")
    print(f"{service}: health UP, anonymous and forged-token requests rejected")
