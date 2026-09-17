#!/usr/bin/env python
"""Example Hench script tool.

Contract: argv[1] = JSON string of the tool arguments.
Print the tool result to stdout, exit 0 on success.
Your script has the full bundled Python 3.12 stdlib + curl_cffi available.
"""
import json
import sys


def main() -> int:
    args = json.loads(sys.argv[1]) if len(sys.argv) > 1 else {}
    message = str(args.get("message", ""))
    if args.get("upper"):
        message = message.upper()
    print(json.dumps({"echo": message, "python": sys.version.split()[0]}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
