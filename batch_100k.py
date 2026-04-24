#!/usr/bin/env python3
"""
ClearFlow Batched 100k Payment Test
100 batches × 1,000 payments, 5s cooldown between batches.
10 concurrent workers per batch — safe for single-machine dev stack.

Scenarios per batch:
  800 happy path · 80 high-value · 50 AML · 40 fraud · 20 duplicate · 10 CTR
"""
import json, random, sys, time, uuid, threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import defaultdict
from datetime import date, timedelta

try:
    import requests
except ImportError:
    print("pip install requests"); sys.exit(1)

GATEWAY     = "http://localhost:8080"
BATCH_SIZE  = 1_000
NUM_BATCHES = 100
WORKERS     = 10       # threads per batch — conservative
COOLDOWN    = 5        # seconds between batches

CLEAN = [
    {"name": "Alpine Logistics GmbH",  "iban": "DE89370400440532013000",      "bic": "DEUTDEDBXXX", "country": "DE"},
    {"name": "Euro Trade SARL",        "iban": "FR7630006000011234567890189",  "bic": "BNPAFRPPXXX", "country": "FR"},
    {"name": "HSBC Holdings PLC",      "iban": "GB29NWBK60161331926819",      "bic": "HBUKGB4BXXX", "country": "GB"},
    {"name": "UBS AG Zurich",          "iban": "CH5604835012345678009",        "bic": "UBSWCHZHXXX", "country": "CH"},
    {"name": "ING Bank NV",            "iban": "NL91ABNA0417164300",          "bic": "INGBNL2AXXX", "country": "NL"},
    {"name": "Banco Santander SA",     "iban": "ES9121000418450200051332",     "bic": "BSCHESMM",   "country": "ES"},
    {"name": "UniCredit SpA",          "iban": "IT60X0542811101000000123456",  "bic": "UNCRITMM",   "country": "IT"},
    {"name": "Raiffeisen Bank Intl",   "iban": "AT611904300234573201",         "bic": "RZOOAT2L",   "country": "AT"},
]
SANCTIONS = [
    {"name": "Al-Aqsa Foundation",   "iban": "IR000000000000000000000001", "bic": "IRANBANKXXX", "country": "IR"},
    {"name": "Pyongyang Trading Co", "iban": "KP000000000000000000000002", "bic": "NKORBANKXXX", "country": "KP"},
    {"name": "Crimea Export LLC",    "iban": "RU000000000000000000000003", "bic": "ROSSBANKXXX", "country": "RU"},
]
CHANNELS   = ["SWIFT", "SEPA", "FEDWIRE", "FASTER_PAYMENTS", "INTERNAL"]
CURRENCIES = ["USD", "EUR", "GBP", "CHF", "JPY", "SGD"]

# Per-batch scenario mix
SCENARIOS = (
    ["happy"]       * 800 +
    ["high_value"]  * 80  +
    ["aml"]         * 50  +
    ["fraud"]       * 40  +
    ["duplicate"]   * 20  +
    ["ctr"]         * 10
)

# Global
lock        = threading.Lock()
total_stats = defaultdict(int)
all_latencies = []
reuse_ids   = []   # instruction IDs to replay as duplicates

def build(scenario, dup_id=None):
    tomorrow = (date.today() + timedelta(days=1)).isoformat()
    d = random.choice(CLEAN)
    c = random.choice([p for p in CLEAN if p != d])

    if scenario == "aml":
        d = random.choice(SANCTIONS)
        amount = round(random.uniform(5_000, 50_000), 2)
    elif scenario == "high_value":
        amount = round(random.uniform(500_000, 2_000_000), 2)
    elif scenario in ("fraud", "ctr"):
        amount = round(random.uniform(9_800, 10_200), 2)
    else:
        amount = round(random.uniform(100, 750_000), 2)

    iid = dup_id if (scenario == "duplicate" and dup_id) else str(uuid.uuid4())
    return {
        "instructionId":  iid,
        "endToEndId":     f"E2E-{date.today().strftime('%Y%m%d')}-{random.randint(10000,99999)}",
        "uetr":           str(uuid.uuid4()),
        "debtor":   {"name": d["name"], "iban": d["iban"], "bic": d["bic"],
                     "address": f"{d['country']} HQ",     "country": d["country"]},
        "creditor": {"name": c["name"], "iban": c["iban"], "bic": c["bic"],
                     "address": f"{c['country']} Branch", "country": c["country"]},
        "amount": amount, "currency": random.choice(CURRENCIES),
        "valueDate": tomorrow, "purpose": "SUPP",
        "remittanceInfo": f"INV-{random.randint(10000,99999)}",
        "channel": random.choice(CHANNELS),
    }

def send(i, scenario):
    dup_id = random.choice(reuse_ids) if (scenario == "duplicate" and reuse_ids) else None
    payload = build(scenario, dup_id)
    t0 = time.monotonic()
    try:
        r = requests.post(f"{GATEWAY}/api/v1/payments", json=payload,
                          headers={"Content-Type": "application/json",
                                   "X-Correlation-Id": f"B-{i:06d}-{uuid.uuid4().hex[:6].upper()}"},
                          timeout=15)
        ms = (time.monotonic() - t0) * 1000
        code = r.status_code
        body = {}
        try: body = r.json()
        except: pass
        with lock:
            all_latencies.append(ms)
            total_stats[f"http_{code}"] += 1
            total_stats[f"sc_{scenario}"] += 1
            if code == 202:
                total_stats["accepted"] += 1
                iid = payload["instructionId"]
                if len(reuse_ids) < 200:
                    reuse_ids.append(iid)
            elif code == 409: total_stats["duplicate_hit"] += 1
            elif code == 429: total_stats["rate_limited"] += 1
            elif code >= 500: total_stats["server_error"] += 1
            else:             total_stats["other_reject"] += 1
        return code
    except requests.exceptions.ConnectionError:
        with lock: total_stats["conn_error"] += 1
        return 0
    except Exception:
        with lock: total_stats["exception"] += 1
        return -1

def pct(p):
    if not all_latencies: return 0
    s = sorted(all_latencies)
    return s[min(int(len(s)*p/100), len(s)-1)]

def health_ok():
    try:
        r = requests.get(f"{GATEWAY}/actuator/health", timeout=3)
        return r.status_code == 200 and r.json().get("status") == "UP"
    except:
        return False

def run_batch(batch_num, scenarios):
    results = []
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futs = {pool.submit(send, batch_num*BATCH_SIZE+i, sc): sc
                for i, sc in enumerate(scenarios)}
        for f in as_completed(futs):
            results.append(f.result())
    ok  = results.count(202)
    err = results.count(0) + sum(1 for c in results if c >= 500)
    return ok, err

# ── Main ─────────────────────────────────────────────────────────────────────
print("=" * 65)
print(f"  ClearFlow Batched 100k Test")
print(f"  {NUM_BATCHES} batches × {BATCH_SIZE:,} payments · {WORKERS} workers/batch · {COOLDOWN}s cooldown")
print("=" * 65)

if not health_ok():
    print("  ✗ Gateway not UP. Run: bash start_live_traffic.sh"); sys.exit(1)
print("  ✓ Gateway UP — starting\n")

grand_start = time.monotonic()
batch_scenarios = SCENARIOS[:]  # copy to shuffle per batch

print(f"  {'Batch':>5}  {'Sent':>6}  {'Accepted':>8}  {'Errors':>6}  {'req/s':>6}  {'Total':>8}")
print("  " + "-" * 55)

for b in range(NUM_BATCHES):
    random.shuffle(batch_scenarios)
    bt0 = time.monotonic()
    ok, err = run_batch(b, batch_scenarios)
    elapsed = time.monotonic() - bt0
    rps = BATCH_SIZE / elapsed
    done = (b + 1) * BATCH_SIZE

    print(f"  {b+1:>5}  {BATCH_SIZE:>6,}  {ok:>8,}  {err:>6,}  {rps:>6.1f}  {done:>8,}")
    sys.stdout.flush()

    if b < NUM_BATCHES - 1:
        # Brief health check every 10 batches
        if (b + 1) % 10 == 0:
            if not health_ok():
                print(f"\n  ✗ Gateway DOWN after batch {b+1}. Waiting 15s...")
                time.sleep(15)
                if not health_ok():
                    print("  ✗ Gateway still down. Stopping."); break
        time.sleep(COOLDOWN)

total_elapsed = time.monotonic() - grand_start

# ── Final report ─────────────────────────────────────────────────────────────
total_sent = NUM_BATCHES * BATCH_SIZE
print("\n" + "=" * 65)
print("  FINAL RESULTS")
print("=" * 65)
print(f"  Total sent        : {total_sent:,}")
print(f"  Total time        : {total_elapsed/60:.1f} min  ({total_sent/total_elapsed:.1f} req/s avg)")
print()
print(f"  ✓  Accepted  (202): {total_stats['accepted']:,}  ({total_stats['accepted']/total_sent*100:.1f}%)")
print(f"  ⟳  Duplicate (409): {total_stats['duplicate_hit']:,}")
print(f"  ⧗  RateLimit (429): {total_stats['rate_limited']:,}")
print(f"  ✗  Server err(5xx): {total_stats['server_error']:,}")
print(f"  ✗  Conn errors    : {total_stats['conn_error']:,}")
print()
print("  Latency (p50/p95/p99/max):")
print(f"    {pct(50):.0f}ms / {pct(95):.0f}ms / {pct(99):.0f}ms / {max(all_latencies,default=0):.0f}ms")
print()
print("  Scenario breakdown:")
for sc in ["happy","high_value","aml","fraud","duplicate","ctr"]:
    cnt = total_stats[f"sc_{sc}"]
    print(f"    {sc:<14} {cnt:>7,}")
print()
accept_rate = total_stats['accepted'] / total_sent
err_rate = (total_stats['conn_error'] + total_stats['server_error']) / total_sent
print("  SLA gates:")
print(f"    p99 < 500ms  : {'PASS ✓' if pct(99)<500  else 'FAIL ✗'}  ({pct(99):.0f}ms)")
print(f"    p95 < 200ms  : {'PASS ✓' if pct(95)<200  else 'FAIL ✗'}  ({pct(95):.0f}ms)")
print(f"    accept > 95% : {'PASS ✓' if accept_rate>.95 else 'FAIL ✗'}  ({accept_rate*100:.1f}%)")
print(f"    error  < 1%  : {'PASS ✓' if err_rate<.01  else 'FAIL ✗'}  ({err_rate*100:.2f}%)")
print("=" * 65)
