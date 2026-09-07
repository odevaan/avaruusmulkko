"""
Avaruus Astro - ADB Tethering & Ingest Utility
Streams captured Pixel 9 Pro XL RAW DNGs directly to Windows 11 in real-time.
"""

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

DEFAULT_PHONE_PATH = "/sdcard/Pictures/AvaruusAstro"
DEFAULT_LOCAL_DIR = Path(__file__).resolve().parent.parent / "data" / "sessions"


def get_adb_path() -> str:
    """Find adb.exe from environment or Android SDK location."""
    sdk_adb = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
    if sdk_adb.exists():
        return str(sdk_adb)
    return "adb"


def run_adb(args: list[str]) -> subprocess.CompletedProcess:
    adb = get_adb_path()
    return subprocess.run([adb] + args, capture_output=True, text=True, check=False)


def check_device() -> bool:
    res = run_adb(["devices"])
    lines = [line.strip() for line in res.stdout.strip().splitlines()[1:] if line.strip()]
    active_devices = [l for l in lines if "\tdevice" in l]
    if not active_devices:
        print("[!] No ADB device connected. Ensure USB debugging is enabled on Pixel 9 Pro XL.")
        return False
    print(f"[+] Connected device: {active_devices[0].split()[0]}")
    return True


def list_remote_sessions():
    res = run_adb(["shell", f"ls -1 {DEFAULT_PHONE_PATH} 2>/dev/null"])
    sessions = [s.strip() for s in res.stdout.splitlines() if s.strip()]
    if not sessions:
        print(f"No sessions found in {DEFAULT_PHONE_PATH}")
        return
    print("\nAvailable Astrophotography Sessions on Pixel 9 Pro XL:")
    for s in sessions:
        print(f"  - {s}")


def pull_session(session_name: str | None = None, local_dir: Path = DEFAULT_LOCAL_DIR):
    local_dir.mkdir(parents=True, exist_ok=True)
    remote_path = DEFAULT_PHONE_PATH
    if session_name:
        remote_path = f"{DEFAULT_PHONE_PATH}/{session_name}"

    print(f"Pulling DNGs from {remote_path} -> {local_dir}...")
    res = run_adb(["pull", remote_path, str(local_dir)])
    print(res.stdout)
    if res.returncode == 0:
        print("[+] Sync completed successfully!")
    else:
        print(f"[-] Sync failed: {res.stderr}")


def watch_and_sync(local_dir: Path = DEFAULT_LOCAL_DIR, poll_interval: float = 3.0):
    """
    Live Watcher: Monitors the phone for newly created DNG files
    and downloads them as soon as each exposure finishes.
    """
    local_dir.mkdir(parents=True, exist_ok=True)
    print(f"[*] Starting live tether watcher. Monitoring {DEFAULT_PHONE_PATH}...")
    print(f"[*] Saving files locally to: {local_dir}")
    print("[*] Press Ctrl+C to stop.\n")

    downloaded = set()
    # Populate existing local files
    for f in local_dir.rglob("*.dng"):
        downloaded.add(f.name)

    while True:
        try:
            res = run_adb(["shell", f"find {DEFAULT_PHONE_PATH} -name '*.dng' -type f 2>/dev/null"])
            if res.returncode == 0 and res.stdout.strip():
                remote_files = [line.strip() for line in res.stdout.splitlines() if line.strip()]
                for rfile in remote_files:
                    fname = Path(rfile).name
                    if fname not in downloaded:
                        # Extract session folder if present
                        rel_parts = Path(rfile).relative_to(DEFAULT_PHONE_PATH).parts
                        dest_dir = local_dir / Path(*rel_parts[:-1]) if len(rel_parts) > 1 else local_dir
                        dest_dir.mkdir(parents=True, exist_ok=True)

                        print(f"[*] New RAW frame detected: {fname}. Pulling...")
                        pull_res = run_adb(["pull", rfile, str(dest_dir / fname)])
                        if pull_res.returncode == 0:
                            print(f"[+] Downloaded: {fname}")
                            downloaded.add(fname)
                        else:
                            print(f"[-] Failed to pull {fname}: {pull_res.stderr}")

            time.sleep(poll_interval)
        except KeyboardInterrupt:
            print("\n[!] Stopped live tether.")
            break


def main():
    parser = argparse.ArgumentParser(description="Pixel 9 Pro XL Astrophotography Tether & Ingest")
    parser.add_argument("--check", action="store_true", help="Check ADB connection to phone")
    parser.add_argument("--list", action="store_true", help="List astro sessions on phone")
    parser.add_argument("--pull", nargs="?", const="", help="Pull session (optional: session name)")
    parser.add_argument("--watch", action="store_true", help="Live watch and download new DNGs automatically")
    parser.add_argument("--dest", type=Path, default=DEFAULT_LOCAL_DIR, help="Destination directory on PC")

    args = parser.parse_args()

    if not check_device():
        sys.exit(1)

    if args.list:
        list_remote_sessions()
    elif args.pull is not None:
        pull_session(args.pull if args.pull else None, args.dest)
    elif args.watch:
        watch_and_sync(args.dest)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
