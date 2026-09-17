"""Asserts the outcome of scripts/smoke.sh. Reads the sink's requests on stdin."""

from __future__ import annotations

import json
import sys


def main() -> int:
    delivery_id, status, repeat_code = sys.argv[1], sys.argv[2], sys.argv[3]
    received = json.load(sys.stdin)

    problems = []
    if status != "SUCCEEDED":
        problems.append(f"delivery ended {status}, expected SUCCEEDED")
    if len(received) != 1:
        problems.append(f"the receiver saw {len(received)} deliveries, expected exactly 1")
    else:
        headers = received[0]["headers"]
        if headers.get("webhook-id") != delivery_id:
            problems.append(f"webhook-id was {headers.get('webhook-id')}, expected {delivery_id}")
        signature = headers.get("webhook-signature", "")
        if not signature.startswith("v1,"):
            problems.append(f"signature was {signature!r}, expected a v1 signature")
    if repeat_code != "200":
        problems.append(f"the idempotent repost answered {repeat_code}, expected 200")

    if problems:
        for problem in problems:
            print(f"smoke: {problem}", file=sys.stderr)
        return 1

    print("smoke: one signed delivery, succeeded, and the idempotent repost returned 200")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
