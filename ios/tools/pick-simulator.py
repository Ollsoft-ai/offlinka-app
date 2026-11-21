#!/usr/bin/env python3
"""Print the UDID of an available simulator by device name, on the newest iOS runtime that has it (macOS).

    ios/tools/pick-simulator.py "iPhone 17 Pro Max"
"""
import json
import re
import subprocess
import sys


def version(runtime):
    # com.apple.CoreSimulator.SimRuntime.iOS-26-5 -> (26, 5)
    match = re.search(r"iOS-(\d+)-(\d+)(?:-(\d+))?$", runtime)
    return tuple(int(x) for x in match.groups() if x is not None) if match else ()


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    name = sys.argv[1]
    listing = json.loads(subprocess.run(["xcrun", "simctl", "list", "devices", "available", "-j"],
                                        check=True, capture_output=True, text=True).stdout)
    found = [(version(runtime), device["udid"])
             for runtime, devices in listing["devices"].items() if ".iOS-" in runtime
             for device in devices if device["name"] == name]
    if not found:
        names = sorted({d["name"] for r, ds in listing["devices"].items() if ".iOS-" in r for d in ds})
        print(f"no available simulator named {name!r}; have: {', '.join(names)}", file=sys.stderr)
        return 1
    print(max(found)[1])
    return 0


if __name__ == "__main__":
    sys.exit(main())
