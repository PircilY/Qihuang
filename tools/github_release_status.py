"""Read this repository's PR and CI metadata using existing Git credentials."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPO = "PircilY/Qihuang"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError("GitHub API redirect refused")


def inspect(sha):
    remote = subprocess.check_output(["git", "remote", "get-url", "origin"], cwd=ROOT).decode().strip()
    if remote.removesuffix(".git") != f"https://github.com/{REPO}":
        raise ValueError("release status is scoped to the configured project remote")
    env = {**os.environ, "GCM_INTERACTIVE": "never", "GIT_TERMINAL_PROMPT": "0"}
    credential = subprocess.run(["git", "credential", "fill"], cwd=ROOT, env=env,
                                input="protocol=https\nhost=github.com\n\n",
                                text=True, capture_output=True, timeout=30)
    values = dict(line.split("=", 1) for line in credential.stdout.splitlines() if "=" in line)
    token = values.get("password")
    opener = urllib.request.build_opener(NoRedirect())
    def get(path):
        request = urllib.request.Request(f"https://api.github.com/repos/{REPO}/{path}", headers={
            "Accept": "application/vnd.github+json", "User-Agent": "QihuangReleaseAudit/1.0",
            **({"Authorization": f"Bearer {token}"} if token else {})})
        with opener.open(request, timeout=30) as response:
            return json.load(response)
    result = {"repository": REPO, "headSha": sha, "credentialAvailable": bool(token)}
    try:
        pulls = get("pulls?state=open&per_page=100")
        result["openPullRequests"] = [
            {"number": pr["number"], "title": pr["title"], "url": pr["html_url"],
             "head": pr["head"]["sha"], "base": pr["base"]["ref"]} for pr in pulls]
        runs = get(f"actions/runs?head_sha={sha}&per_page=30")
        result["ciRuns"] = [{k: run.get(k) for k in (
            "id", "name", "status", "conclusion", "html_url", "head_sha")} for run in runs["workflow_runs"]]
        result["accessible"] = True
    except urllib.error.HTTPError as error:
        result.update(accessible=False, httpStatus=error.code)
    except (OSError, ValueError):
        result.update(accessible=False, error="API request failed; credentials not logged")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sha")
    parser.add_argument("--output", type=Path, default=ROOT / "runtime/github-release-status.json")
    args = parser.parse_args()
    sha = args.sha or subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip()
    if len(sha) != 40 or any(c not in "0123456789abcdef" for c in sha):
        parser.error("full commit SHA required")
    if not args.output.resolve().is_relative_to((ROOT / "runtime").resolve()):
        parser.error("release metadata output must stay in runtime")
    result = inspect(sha)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
