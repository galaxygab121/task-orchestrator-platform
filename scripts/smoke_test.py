#!/usr/bin/env python3
"""End-to-end acceptance / smoke test against a running stack (docker compose up --build -d).

Standard library only. Exit code 0 means every check passed.

    python3 scripts/smoke_test.py                 # full run incl. Kafka DLQ + duplicate-event checks
    python3 scripts/smoke_test.py --skip-docker   # HTTP-only checks (no `docker compose exec`)

Checks:
  * health of all three services and the Prometheus endpoint
  * >= 10 mixed bounded jobs succeed with independently computed expected results
  * a transient failure is retried with backoff and then succeeds
  * exhausted retries and a permanent failure end FAILED and appear on jobs.dlq.v1
  * re-publishing a dispatch event for a finished job does not execute it again
  * idempotent create (replay + 409 on mismatch), 400/404/409 error contracts, pagination
"""
import argparse
import json
import math
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

API = "http://localhost:8080"
SERVICES = {"orchestrator-api": 8080, "scheduler-service": 8081, "worker-service": 8082}
TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {name}" + (f" -- {detail}" if detail else ""))
    return ok


def http(method, path, body=None, headers=None, base=API):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode()
            return resp.status, dict(resp.headers), (json.loads(raw) if raw and raw.lstrip()[:1] in "{[" else raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = raw
        return e.code, dict(e.headers), parsed


def resolve_ports(use_docker):
    """Worker host ports come from a range (to allow --scale), so ask Compose where each service landed."""
    ports = dict(SERVICES)
    if not use_docker:
        return ports
    for name, container_port in SERVICES.items():
        try:
            out = compose("port", "--index", "1", name, str(container_port), timeout=15).stdout.strip()
            if out:
                ports[name] = int(out.rsplit(":", 1)[1])
        except (OSError, subprocess.SubprocessError, ValueError):
            pass
    return ports


def wait_for_health(deadline_s, ports):
    end = time.time() + deadline_s
    pending = dict(ports)
    while pending and time.time() < end:
        for name, port in list(pending.items()):
            try:
                status, _, body = http("GET", "/actuator/health", base=f"http://localhost:{port}")
                if status == 200 and body.get("status") == "UP":
                    pending.pop(name)
            except (urllib.error.URLError, ConnectionError, TimeoutError):
                pass
        time.sleep(1)
    for name in SERVICES:
        check(f"health UP: {name}", name not in pending)
    return not pending


def submit(title, job_type, payload, priority=3, max_retries=3, key=None):
    headers = {"Idempotency-Key": key} if key else None
    status, hdrs, body = http("POST", "/api/v1/jobs", {
        "title": title, "type": job_type, "payload": payload, "priority": priority, "maxRetries": max_retries,
    }, headers)
    if status != 202:
        raise RuntimeError(f"submit {title} failed: {status} {body}")
    return body["id"], hdrs


def wait_terminal(job_ids, deadline_s):
    end = time.time() + deadline_s
    jobs = {}
    while time.time() < end:
        for jid in job_ids:
            if jid in jobs and jobs[jid]["status"] in TERMINAL:
                continue
            _, _, jobs[jid] = http("GET", f"/api/v1/jobs/{jid}")
        if all(j["status"] in TERMINAL for j in jobs.values()) and len(jobs) == len(job_ids):
            break
        time.sleep(0.5)
    return jobs


def fib(n):
    a, b = 0, 1
    for _ in range(n):
        a, b = b, a + b
    return a


def compose(*args, stdin=None, timeout=60):
    return subprocess.run(["docker", "compose", *args], input=stdin, capture_output=True, text=True, timeout=timeout)


def read_dlq():
    proc = compose("exec", "-T", "kafka", "kafka-console-consumer", "--bootstrap-server", "kafka:9092",
                   "--topic", "jobs.dlq.v1", "--from-beginning", "--timeout-ms", "8000",
                   "--property", "print.key=true", "--property", "key.separator=|")
    return proc.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-docker", action="store_true", help="skip checks that need `docker compose exec`")
    parser.add_argument("--deadline", type=int, default=120, help="seconds to wait for jobs to finish")
    args = parser.parse_args()
    started = time.time()

    if not wait_for_health(120, resolve_ports(not args.skip_docker)):
        return finish(started)
    status, _, metrics = http("GET", "/actuator/prometheus")
    check("prometheus endpoint exposes orchestrator metrics", status == 200 and "orchestrator_outbox_pending" in metrics)
    status, _, docs = http("GET", "/v3/api-docs")
    check("OpenAPI document served", status == 200 and "/api/v1/jobs" in json.dumps(docs))

    run = uuid.uuid4().hex[:8]
    # ---- 12 mixed, bounded jobs with independently computed expectations ----
    expected = {}
    for n in (10, 20, 30, 35, 40):
        jid, _ = submit(f"{run}-fib-{n}", "FIBONACCI", {"n": n}, priority=(n % 5) + 1)
        expected[jid] = ("FIBONACCI", lambda r, n=n: r["value"] == fib(n), f"F({n})={fib(n)}")
    for values in ([1, 2, 3, 4, 5], [10.5, -3, 7, 7, 0.25, 99], list(range(1, 101))):
        jid, _ = submit(f"{run}-sum-{len(values)}", "DATA_SUMMARY", {"values": values}, priority=4)

        def ok(r, v=values):
            return (r["count"] == len(v) and math.isclose(r["sum"], sum(v)) and r["min"] == min(v)
                    and r["max"] == max(v) and math.isclose(r["mean"], statistics.fmean(v))
                    and math.isclose(r["median"], statistics.median(v))
                    and math.isclose(r["stdDev"], statistics.pstdev(v), abs_tol=1e-9))
        expected[jid] = ("DATA_SUMMARY", ok, f"summary of {len(values)} values")
    for ms in (200, 800, 1500, 3000):
        jid, _ = submit(f"{run}-sleep-{ms}", "SLEEP", {"durationMs": ms}, priority=2)
        expected[jid] = ("SLEEP", lambda r, ms=ms: r["sleptMs"] == ms, f"slept {ms} ms")

    transient_id, _ = submit(f"{run}-transient", "FIBONACCI", {"n": 15, "failTransientlyTimes": 2}, max_retries=3)
    exhausted_id, _ = submit(f"{run}-exhausted", "FIBONACCI", {"n": 5, "failTransientlyTimes": 5}, max_retries=1)
    permanent_id, _ = submit(f"{run}-permanent", "DATA_SUMMARY", {"values": [1], "failPermanently": True}, max_retries=3)

    all_ids = list(expected) + [transient_id, exhausted_id, permanent_id]
    print(f"Submitted {len(all_ids)} jobs; waiting up to {args.deadline}s for completion ...")
    t0 = time.time()
    jobs = wait_terminal(all_ids, args.deadline)
    elapsed = time.time() - t0

    for jid, (job_type, verify, label) in expected.items():
        job = jobs[jid]
        ok = job["status"] == "SUCCEEDED" and job["result"] is not None and verify(job["result"])
        check(f"{job_type} {label}", ok, f"status={job['status']} result={job['result']}")

    t = jobs[transient_id]
    check("transient failure retried then SUCCEEDED", t["status"] == "SUCCEEDED" and t["attempt"] == 3
          and t["result"]["value"] == fib(15), f"status={t['status']} attempt={t['attempt']}")
    _, _, history = http("GET", f"/api/v1/jobs/{transient_id}/events")
    retries = [e for e in history if e["fromStatus"] == "RUNNING" and e["toStatus"] == "QUEUED"]
    check("retry history shows bounded backoff", len(retries) == 2 and "in 1000ms" in retries[0]["detail"]
          and "in 2000ms" in retries[1]["detail"], "; ".join(e["detail"] for e in retries))

    e = jobs[exhausted_id]
    check("exhausted retries -> FAILED after maxRetries+1 attempts", e["status"] == "FAILED" and e["attempt"] == 2,
          f"status={e['status']} attempt={e['attempt']} lastError={e['lastError']}")
    p = jobs[permanent_id]
    check("permanent failure -> FAILED without retry", p["status"] == "FAILED" and p["attempt"] == 1,
          f"status={p['status']} attempt={p['attempt']}")

    # ---- idempotency ----
    key = f"smoke-{run}"
    body = ("idem", "FIBONACCI", {"n": 7})
    first, h1 = submit(f"{run}-{body[0]}", body[1], body[2], key=key)
    second, h2 = submit(f"{run}-{body[0]}", body[1], body[2], key=key)
    check("Idempotency-Key replay returns same job", first == second and h2.get("Idempotent-Replayed") == "true")
    status, _, err = http("POST", "/api/v1/jobs", {"title": "different", "type": "FIBONACCI", "payload": {"n": 8}},
                          {"Idempotency-Key": key})
    check("Idempotency-Key with different body -> 409", status == 409 and err.get("code") == "IDEMPOTENCY_KEY_REUSED")

    # ---- error contracts ----
    status, _, err = http("POST", "/api/v1/jobs", {"title": "bad", "type": "SLEEP", "payload": {"durationMs": 999999}})
    check("out-of-bounds payload -> 400", status == 400 and err.get("code") == "INVALID_PAYLOAD", str(err.get("errors")))
    status, _, err = http("POST", "/api/v1/jobs", {"title": "bad", "type": "SHELL", "payload": {"cmd": "id"}})
    check("non-allowlisted type -> 400", status == 400)
    status, _, _ = http("GET", f"/api/v1/jobs/{uuid.uuid4()}")
    check("unknown job -> 404", status == 404)
    status, _, err = http("POST", f"/api/v1/jobs/{transient_id}/cancel")
    check("cancel finished job -> 409", status == 409 and err.get("code") == "INVALID_STATE_TRANSITION")

    # ---- pagination ----
    _, _, page0 = http("GET", "/api/v1/jobs?size=5&page=0")
    _, _, page1 = http("GET", "/api/v1/jobs?size=5&page=1")
    ids0 = {j["id"] for j in page0["content"]}
    ids1 = {j["id"] for j in page1["content"]}
    check("pagination is stable and non-overlapping", len(ids0) == 5 and len(ids1) == 5 and not ids0 & ids1
          and page0["totalElements"] >= len(all_ids), f"total={page0['totalElements']} pages={page0['totalPages']}")
    _, _, failed = http("GET", "/api/v1/jobs?status=FAILED&size=100")
    check("status filter returns failed jobs", {exhausted_id, permanent_id} <= {j["id"] for j in failed["content"]})

    if not args.skip_docker:
        dlq = read_dlq()
        check("FAILED jobs are on jobs.dlq.v1", exhausted_id in dlq and permanent_id in dlq)
        leaked = any("failTransientlyTimes" in line for line in dlq.splitlines() if exhausted_id in line)
        check("DLQ event is sanitised (no payload)", not leaked)

        # Re-deliver a dispatch for an already-succeeded attempt; it must be ignored.
        dup_target = next(iter(expected))
        event = {"schemaVersion": 1, "eventId": str(uuid.uuid4()), "jobId": dup_target, "attempt": 1,
                 "type": "FIBONACCI", "occurredAt": "2026-01-01T00:00:00Z"}
        line = f"{dup_target}|{json.dumps(event)}\n" * 3
        compose("exec", "-T", "kafka", "kafka-console-producer", "--bootstrap-server", "kafka:9092", "--topic", "jobs.v1",
                "--property", "parse.key=true", "--property", "key.separator=|", stdin=line)
        time.sleep(4)
        _, _, history = http("GET", f"/api/v1/jobs/{dup_target}/events")
        succeeded = sum(1 for e in history if e["toStatus"] == "SUCCEEDED")
        running = sum(1 for e in history if e["toStatus"] == "RUNNING")
        check("duplicate dispatch events do not re-execute", succeeded == 1 and running == 1,
              f"RUNNING x{running}, SUCCEEDED x{succeeded}")
    else:
        print("[SKIP] Kafka DLQ and duplicate-event checks (--skip-docker)")

    print(f"\nObserved: {len(all_ids)} jobs reached a terminal state in {elapsed:.1f}s wall-clock "
          f"(single local run, includes the 3s SLEEP job and 1s+2s retry backoff; not a benchmark).")
    return finish(started)


def finish(started):
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed in {time.time() - started:.1f}s")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
