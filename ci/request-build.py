#!/usr/bin/env python3
"""Submit the current source tree to a build session and collect the APKs.

Flow (see docs/build-session.md):
  1. If no build-session run is active, dispatch one and wait until
     its session release appears (that release is the ready signal).
  2. Snapshot the publishable files of the source tree (tracked files
     plus untracked, non-ignored files) onto the build/inbox branch.
  3. Poll the session release for result-<short-sha>.json, download
     the APK assets for that build and verify their SHA-256.

Requires the `gh` CLI logged in as an account with repo + workflow
scopes (on the Muse VM: ysk-agent) and git SSH access to the repo.
"""
import argparse, hashlib, json, os, shutil, subprocess, sys, tempfile, time

def run(cmd, timeout=30, check=True, capture=True):
    r = subprocess.run(cmd, timeout=timeout,
                       capture_output=capture, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"command failed ({r.returncode}): {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r

def gh_json(args, timeout=30):
    return json.loads(run(["gh"] + args, timeout=timeout).stdout)

def find_active_run(repo):
    runs = gh_json(["run", "list", "--repo", repo, "--workflow",
                    "build-session.yml", "--limit", "10",
                    "--json", "databaseId,status,createdAt"])
    for r in runs:
        if r["status"] in ("in_progress", "queued"):
            return r["databaseId"]
    return None

def wait_for(predicate, deadline_s, interval=10, what="condition"):
    end = time.time() + deadline_s
    while time.time() < end:
        v = predicate()
        if v:
            return v
        time.sleep(interval)
    raise TimeoutError(f"timed out waiting for {what}")

def snapshot_push(repo, src):
    """Copy publishable files of src into a temp clone and force-push
    them as the build/inbox branch. Returns the new commit SHA."""
    files = run(["git", "-C", src, "ls-files", "-co", "--exclude-standard", "-z"]).stdout.split("\0")
    files = [f for f in files if f]
    tmp = tempfile.mkdtemp(prefix="inbox-")
    try:
        run(["git", "clone", "--quiet", f"git@github.com:{repo}.git", tmp + "/repo"], timeout=120)
        repo_dir = tmp + "/repo"
        for name in os.listdir(repo_dir):
            if name == ".git":
                continue
            p = os.path.join(repo_dir, name)
            shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
        for f in files:
            dst = os.path.join(repo_dir, f)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(os.path.join(src, f), dst)
        run(["git", "-C", repo_dir, "add", "-A"])
        run(["git", "-C", repo_dir, "-c", "user.name=ysk-agent",
             "-c", "user.email=ysk-agent@users.noreply.github.com",
             "commit", "--quiet", "--allow-empty", "-m",
             f"build request {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}"])
        sha = run(["git", "-C", repo_dir, "rev-parse", "HEAD"]).stdout.strip()
        run(["git", "-C", repo_dir, "push", "--force", "origin", "HEAD:build/inbox"], timeout=120)
        return sha
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

def release_assets(repo, tag):
    try:
        rel = gh_json(["api", f"repos/{repo}/releases/tags/{tag}"])
    except RuntimeError:
        return None
    return [a["name"] for a in rel.get("assets", [])]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="ysk-agent/phone-node")
    ap.add_argument("--src", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--session-minutes", type=int, default=30)
    ap.add_argument("--ready-timeout", type=int, default=900)
    ap.add_argument("--build-timeout", type=int, default=1200)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    run_id = find_active_run(args.repo)
    if run_id is None:
        print("no active session; dispatching a new one")
        run(["gh", "workflow", "run", "build-session.yml", "--repo", args.repo,
             "-f", f"session_minutes={args.session_minutes}"])
        run_id = wait_for(lambda: find_active_run(args.repo), 120, what="session run to appear")
    tag = f"session-{run_id}"
    print(f"session: run {run_id}, tag {tag}")

    wait_for(lambda: release_assets(args.repo, tag) is not None,
             args.ready_timeout, what="session ready signal")
    print("session is ready")

    sha = snapshot_push(args.repo, args.src)
    short = sha[:12]
    print(f"submitted checkout {sha} (short {short})")

    out = args.out or os.path.join(args.src, "ci-out", short)
    os.makedirs(out, exist_ok=True)
    result_name = f"result-{short}.json"

    def got_result():
        assets = release_assets(args.repo, tag) or []
        return result_name in assets
    wait_for(got_result, args.build_timeout, what=f"build result {result_name}")

    run(["gh", "release", "download", tag, "--repo", args.repo,
         "-D", out, "-p", result_name], timeout=60)
    result = json.load(open(os.path.join(out, result_name)))
    print(f"build status: {result['status']}")
    if result["status"] != "success":
        run(["gh", "release", "download", tag, "--repo", args.repo,
             "-D", out, "-p", f"build-{short}.log"], timeout=60, check=False)
        print(f"log downloaded to {out}")
        return 1
    for apk in ("phonenode-app", "phonenode-helper"):
        run(["gh", "release", "download", tag, "--repo", args.repo,
             "-D", out, "-p", f"{apk}-{short}.apk"], timeout=600)
    for name, meta in result["build_info"]["apks"].items():
        path = os.path.join(out, name.replace(".apk", f"-{short}.apk"))
        digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
        assert digest == meta["sha256"], f"sha256 mismatch for {name}"
        print(f"verified {path} ({meta['size']} bytes)")
    print(f"done: {out}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
