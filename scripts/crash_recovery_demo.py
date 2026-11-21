#!/usr/bin/env python3
"""Demonstrates lease-based crash recovery against the running compose stack.

1. Submits a 15 s SLEEP job and waits until a worker has claimed it (RUNNING).
2. SIGKILLs every worker container (no graceful shutdown, no heartbeat, no completion).
3. Restarts the workers.
4. The scheduler's lease reaper notices the expired lease, re-queues the job as attempt 2 with backoff,
   and a fresh worker finishes it. Prints the job's audit trail.

Takes roughly lease duration (30 s) + reaper interval (5 s) + backoff (1 s) + restart + 15 s.
"""
import json
import subprocess
import sys
import time
import urllib.request

API = "http://localhost:8080"


def call(method, path, body=None):
    req = urllib.request.Request(API + path, data=json.dumps(body).encode() if body else None, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.load(resp)


def status(job_id):
    return call("GET", f"/api/v1/jobs/{job_id}")


def main():
    job = call("POST", "/api/v1/jobs", {"title": "crash-recovery-demo", "type": "SLEEP",
                                        "payload": {"durationMs": 15000}, "maxRetries": 2})
    job_id = job["id"]
    print(f"submitted {job_id}")
    deadline = time.time() + 30
    while status(job_id)["status"] != "RUNNING":
        if time.time() > deadline:
            print("job never started running")
            return 1
        time.sleep(0.3)
    print("job is RUNNING -> killing all worker containers with SIGKILL")
    subprocess.run(["docker", "compose", "kill", "-s", "SIGKILL", "worker-service"], check=True, capture_output=True)
    killed_at = time.time()
    subprocess.run(["docker", "compose", "up", "-d", "worker-service"], check=True, capture_output=True)
    print("workers restarted; waiting for lease expiry and recovery ...")
    while True:
        current = status(job_id)
        if current["status"] in ("SUCCEEDED", "FAILED"):
            break
        if time.time() - killed_at > 150:
            print("timed out waiting for recovery")
            return 1
        time.sleep(1)
    print(f"final status {current['status']} on attempt {current['attempt']}, "
          f"{time.time() - killed_at:.0f}s after the kill; result={current['result']}\n")
    for event in call("GET", f"/api/v1/jobs/{job_id}/events"):
        print(f"{event['occurredAt'][11:19]}  {str(event['fromStatus']):9} -> {event['toStatus']:9} "
              f"attempt={event['attempt']}  {event['actor']:24} {event['detail']}")
    return 0 if current["status"] == "SUCCEEDED" and current["attempt"] == 2 else 1


if __name__ == "__main__":
    sys.exit(main())
