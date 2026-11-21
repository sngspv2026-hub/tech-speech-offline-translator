#!/usr/bin/env python3
"""
Cloud APK Builder via GitHub Actions REST API.
Reads GITHUB_TOKEN safely from c:/Projekt/translate/.env (or ~/.env) without printing it,
creates/updates the GitHub repository, pushes the project tree via Git Data API (no local git binary needed!),
monitors the GitHub Actions workflow run (`android_build_and_test.yml`),
downloads the compiled APKs (`standard` and `airgapped`) into `dist/`,
and creates a GitHub Release with direct download URLs for the APKs.
"""

import argparse
import base64
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Optional

ROOT_DIR = Path(__file__).resolve().parent.parent
DIST_DIR = ROOT_DIR / "dist"


def load_token_safely() -> Optional[str]:
    for env_path in [ROOT_DIR / ".env", Path.home() / ".env"]:
        if env_path.exists():
            for line in env_path.read_text(encoding="utf-8-sig", errors="ignore").splitlines():
                line = line.strip().lstrip("\ufeff")
                if line.startswith("GITHUB_TOKEN="):
                    val = line.split("=", 1)[1].strip().strip('"').strip("'")
                    if val:
                        return val
    return os.environ.get("GITHUB_TOKEN")


class StripAuthOnCrossHostRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new_req = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new_req is not None and "api.github.com" not in newurl:
            if "Authorization" in new_req.headers:
                del new_req.headers["Authorization"]
            if "Authorization" in new_req.unredirected_hdrs:
                del new_req.unredirected_hdrs["Authorization"]
        return new_req


class GitHubApiClient:
    def __init__(self, token: str):
        self.token = token
        self.base_url = "https://api.github.com"
        self.opener = urllib.request.build_opener(StripAuthOnCrossHostRedirect())

    def request(
        self,
        method: str,
        path: str,
        data: Optional[Dict[str, Any]] = None,
        raw_bytes: Optional[bytes] = None,
        content_type: str = "application/json",
        full_url: Optional[str] = None,
    ) -> Any:
        url = full_url or f"{self.base_url}{path}"
        body = None
        if raw_bytes is not None:
            body = raw_bytes
        elif data is not None:
            body = json.dumps(data).encode("utf-8")

        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        req.add_header("User-Agent", "TechSpeechOfflineTranslator-CloudBuilder")
        if body is not None:
            req.add_header("Content-Type", content_type)

        for attempt in range(1, 5):
            try:
                with self.opener.open(req, timeout=90) as resp:
                    raw = resp.read()
                    if not raw:
                        return {}
                    if "application/json" in resp.headers.get("Content-Type", ""):
                        return json.loads(raw.decode("utf-8"))
                    return raw
            except urllib.error.HTTPError:
                raise
            except Exception as e:
                if attempt == 4:
                    raise
                time.sleep(2 * attempt)


def collect_project_files() -> List[Path]:
    excluded_dirs = {".git", ".gradle", ".idea", "build", "dist", "__pycache__"}
    excluded_files = {".env", "local.properties"}
    files = []
    for p in sorted(ROOT_DIR.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(ROOT_DIR)
        if any(part in excluded_dirs for part in rel.parts):
            continue
        if p.name in excluded_files or p.name.startswith(".env"):
            continue
        files.append(p)
    return files


def ensure_repo(client: GitHubApiClient, repo_name: str) -> str:
    user = client.request("GET", "/user")
    owner = user["login"]
    try:
        client.request("GET", f"/repos/{owner}/{repo_name}")
        print(f"[GitHub] Using existing repository: {owner}/{repo_name}")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print(f"[GitHub] Creating repository: {owner}/{repo_name} ...")
            client.request(
                "POST",
                "/user/repos",
                {
                    "name": repo_name,
                    "description": "Offline Technical Speech Translator for Android (RU/EN/TR)",
                    "private": False,
                    "auto_init": True,
                },
            )
            time.sleep(3)
        else:
            raise
    return owner


def push_project_via_git_data_api(client: GitHubApiClient, owner: str, repo: str) -> str:
    import hashlib
    ref = client.request("GET", f"/repos/{owner}/{repo}/git/ref/heads/main")
    base_commit_sha = ref["object"]["sha"]
    base_commit = client.request("GET", f"/repos/{owner}/{repo}/git/commits/{base_commit_sha}")
    base_tree_sha = base_commit["tree"]["sha"]

    existing_tree = client.request("GET", f"/repos/{owner}/{repo}/git/trees/{base_tree_sha}?recursive=1")
    remote_blobs = {
        item["path"]: item["sha"]
        for item in existing_tree.get("tree", [])
        if item.get("type") == "blob"
    }

    files = collect_project_files()
    tree_entries = []
    uploaded_count = 0
    for path in files:
        rel_posix = path.relative_to(ROOT_DIR).as_posix()
        raw = path.read_bytes()
        git_blob_sha = hashlib.sha1(f"blob {len(raw)}\0".encode("utf-8") + raw).hexdigest()
        mode = "100755" if rel_posix == "gradlew" else "100644"
        if remote_blobs.get(rel_posix) == git_blob_sha:
            continue
        blob = client.request(
            "POST",
            f"/repos/{owner}/{repo}/git/blobs",
            {
                "content": base64.b64encode(raw).decode("ascii"),
                "encoding": "base64",
            },
        )
        uploaded_count += 1
        tree_entries.append(
            {
                "path": rel_posix,
                "mode": mode,
                "type": "blob",
                "sha": blob["sha"],
            }
        )
    print(f"[GitHub] Uploaded {uploaded_count} changed file(s) out of {len(files)} total.")

    new_tree = client.request(
        "POST",
        f"/repos/{owner}/{repo}/git/trees",
        {"base_tree": base_tree_sha, "tree": tree_entries},
    )
    new_commit = client.request(
        "POST",
        f"/repos/{owner}/{repo}/git/commits",
        {
            "message": "Build Offline Technical Speech Translator APKs (standard + airgapped)",
            "tree": new_tree["sha"],
            "parents": [base_commit_sha],
        },
    )
    client.request(
        "PATCH",
        f"/repos/{owner}/{repo}/git/refs/heads/main",
        {"sha": new_commit["sha"], "force": True},
    )
    print(f"[GitHub] Pushed commit {new_commit['sha'][:8]} to {owner}/{repo}@main")
    return new_commit["sha"]


def print_failed_job_logs(client: GitHubApiClient, owner: str, repo: str, run_id: int) -> None:
    try:
        jobs = client.request("GET", f"/repos/{owner}/{repo}/actions/runs/{run_id}/jobs")
        for job in jobs.get("jobs", []):
            for step in job.get("steps", []):
                print(f"  Step [{step.get('number')}] {step.get('name')}: {step.get('conclusion')}")
            if job.get("conclusion") == "failure":
                log_bytes = client.request(
                    "GET", f"/repos/{owner}/{repo}/actions/jobs/{job['id']}/logs"
                )
                if isinstance(log_bytes, bytes):
                    log_text = log_bytes.decode("utf-8", errors="ignore")
                    lines = log_text.splitlines()
                    print("\n--- LAST 120 LINES OF FAILED JOB LOG ---")
                    print("\n".join(lines[-120:]))
    except Exception as e:
        print(f"Could not fetch detailed job logs: {e}")


def wait_for_workflow_and_download_apks(
    client: GitHubApiClient, owner: str, repo: str, head_sha: Optional[str] = None
) -> List[Path]:
    print("[GitHub Actions] Locating workflow run...")
    run_id = None
    run_html_url = ""
    for _ in range(30):
        runs = client.request("GET", f"/repos/{owner}/{repo}/actions/runs?per_page=5")
        workflow_runs = runs.get("workflow_runs", [])
        if head_sha:
            for r in workflow_runs:
                if r.get("head_sha") == head_sha:
                    run_id = r["id"]
                    run_html_url = r["html_url"]
                    break
        elif workflow_runs:
            run_id = workflow_runs[0]["id"]
            run_html_url = workflow_runs[0]["html_url"]
            break
        if run_id is not None:
            break
        time.sleep(4)

    if run_id is None:
        raise RuntimeError("Workflow run did not start within 120 seconds.")

    print(f"[GitHub Actions] Monitoring run {run_id}: {run_html_url}")
    while True:
        try:
            runs = client.request("GET", f"/repos/{owner}/{repo}/actions/runs?per_page=5")
            run_info = next(
                (r for r in runs.get("workflow_runs", []) if r["id"] == run_id),
                None,
            )
            if run_info is None:
                time.sleep(5)
                continue
            status = run_info.get("status")
            conclusion = run_info.get("conclusion")
            print(f"  status={status}, conclusion={conclusion}", flush=True)
            if status == "completed":
                if conclusion != "success":
                    print_failed_job_logs(client, owner, repo, run_id)
                    raise RuntimeError(
                        f"Cloud build failed (conclusion={conclusion}). See logs: {run_html_url}"
                    )
                break
        except RuntimeError:
            raise
        except urllib.error.HTTPError as e:
            if e.code in (404, 502, 503, 504):
                print(f"  transient HTTP {e.code}, retrying...", flush=True)
            else:
                raise
        except Exception as e:
            print(f"  transient network error ({e}), retrying...", flush=True)
        time.sleep(10)

    artifacts = client.request("GET", f"/repos/{owner}/{repo}/actions/runs/{run_id}/artifacts")
    extracted_apks: List[Path] = []
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    for art in artifacts.get("artifacts", []):
        dl_url = art["archive_download_url"]
        print(f"[GitHub Actions] Downloading artifact '{art['name']}'...")
        zip_bytes = client.request("GET", "", full_url=dl_url)
        with zipfile.ZipFile(io.BytesIO(zip_bytes), "r") as zf:
            for member in zf.namelist():
                if member.endswith(".apk") or member.endswith(".aab"):
                    target_name = Path(member).name
                    out_file = DIST_DIR / target_name
                    out_file.write_bytes(zf.read(member))
                    extracted_apks.append(out_file)
                    print(f"[OK] Saved artifact to: {out_file} ({out_file.stat().st_size} bytes)")
    # Also fetch and display live 6-direction translation test lines from job logs
    try:
        jobs = client.request("GET", f"/repos/{owner}/{repo}/actions/runs/{run_id}/jobs")
        for job in jobs.get("jobs", []):
            log_bytes = client.request("GET", f"/repos/{owner}/{repo}/actions/jobs/{job['id']}/logs")
            if isinstance(log_bytes, bytes):
                for line in log_bytes.decode("utf-8", errors="ignore").splitlines():
                    if "[LIVE TEST" in line or "Verified using" in line or "app-standard-release" in line:
                        print("  [CI LOG]", line.strip())
    except Exception:
        pass
    return extracted_apks


def publish_github_release(
    client: GitHubApiClient, owner: str, repo: str, apk_files: List[Path]
) -> str:
    tag = f"v1.1.0-{int(time.time())}"
    rel = client.request(
        "POST",
        f"/repos/{owner}/{repo}/releases",
        {
            "tag_name": tag,
            "name": f"TechSpeech Offline Translator {tag}",
            "body": "Automated Cloud Build: Standard APK + Air-Gapped APK (Zero INTERNET permission) + Offline Language Packs (.tpk).",
            "draft": False,
            "prerelease": False,
        },
    )
    upload_url_tmpl = rel["upload_url"].split("{")[0]
    for f in apk_files + list(DIST_DIR.glob("*.tpk")):
        up_url = f"{upload_url_tmpl}?name={f.name}"
        client.request(
            "POST",
            "",
            raw_bytes=f.read_bytes(),
            content_type="application/octet-stream",
            full_url=up_url,
        )
        print(f"[Release] Uploaded {f.name}")
    print(f"[OK] Release published: {rel['html_url']}")
    return rel["html_url"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--monitor-only", action="store_true", help="Attach to latest workflow run without pushing")
    args = parser.parse_args()

    token = load_token_safely()
    if not token:
        print("ERROR: GITHUB_TOKEN not found in .env", file=sys.stderr)
        return 2

    repo_name = os.environ.get("GITHUB_REPO_NAME", "tech-speech-offline-translator")
    client = GitHubApiClient(token)
    owner = ensure_repo(client, repo_name)
    sha = None if args.monitor_only else push_project_via_git_data_api(client, owner, repo_name)
    apks = wait_for_workflow_and_download_apks(client, owner, repo_name, sha)
    print(f"\n=== CLOUD APK BUILD COMPLETE ===\nDownloaded {len(apks)} artifacts to {DIST_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
