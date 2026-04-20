#!/bin/bash
# ClearFlow Live Traffic Startup Script
# Starts minimal infrastructure + all 8 microservices with dev profile

set -e

BASE="/home/admin-/Desktop/EDI6/clearflow"
JAVA_BIN="/usr/lib/jvm/java-21-openjdk-amd64/bin/java"
MVN="$HOME/.maven/maven-3.9.12/bin/mvn"
JAVA_HOME_PATH="/usr/lib/jvm/java-21-openjdk-amd64"
LOGS="$BASE/dev-logs"

mkdir -p "$LOGS"

echo "======================================================"
echo "  ClearFlow Live Traffic"
echo "======================================================"

# ── 1. Infrastructure ──────────────────────────────────────
echo ""
echo "[1/4] Starting infrastructure..."
cd "$BASE/infrastructure"

docker-compose up -d zookeeper kafka activemq-artemis redis mongodb cassandra elasticsearch

echo "      Waiting 50s for brokers to be ready..."
sleep 50

# Ensure Kafka is responsive
echo "      Verifying Kafka..."
for i in $(seq 1 12); do
  if docker-compose exec -T kafka kafka-topics --bootstrap-server kafka:9092 --list &>/dev/null 2>&1; then
    echo "      Kafka ready."
    break
  fi
  echo "      Kafka not ready yet ($i/12), retrying in 5s..."
  sleep 5
done

# Create Cassandra keyspace for audit service
echo "      Creating Cassandra keyspace clearflow_dev..."
docker-compose exec -T cassandra cqlsh -e \
  "CREATE KEYSPACE IF NOT EXISTS clearflow_dev WITH replication = {'class':'SimpleStrategy','replication_factor':1} AND durable_writes = true;" \
  2>/dev/null || echo "      (Cassandra keyspace creation failed — audit may log errors but pipeline will still work)"

# Fix ES disk watermark if ES is starting
echo "      Configuring Elasticsearch..."
sleep 10
curl -s -X PUT "localhost:9200/_cluster/settings" \
  -H "Content-Type: application/json" \
  -d '{"persistent":{"cluster.routing.allocation.disk.threshold_enabled":false}}' \
  &>/dev/null || true

# ── 2. Build ───────────────────────────────────────────────
echo ""
echo "[2/4] Building all services (skipping tests)..."
cd "$BASE"

JAVA_HOME="$JAVA_HOME_PATH" "$MVN" package -DskipTests --no-transfer-progress \
  -pl common,gateway,fraud-scoring,validation-enrichment,aml-compliance,routing-execution,settlement,audit,mcp-readonly-gateway \
  -am 2>&1 | grep -E "BUILD|ERROR|WARNING|\\[INFO\\] Building" | grep -v "^$"

echo "      Build complete."

# ── 3. Start services ──────────────────────────────────────
echo ""
echo "[3/4] Starting microservices..."

start_service() {
  local name=$1
  local port=$2
  local jar=$3

  if [ -f "$LOGS/$name.pid" ]; then
    old_pid=$(cat "$LOGS/$name.pid")
    kill "$old_pid" 2>/dev/null || true
  fi

  echo "      Starting $name on :$port..."
  SPRING_PROFILES_ACTIVE=dev \
  JAVA_HOME="$JAVA_HOME_PATH" \
    "$JAVA_BIN" \
    -Xmx256m \
    -jar "$BASE/$jar" \
    > "$LOGS/$name.log" 2>&1 &
  echo $! > "$LOGS/$name.pid"
}

start_service fraud-scoring       8081 "fraud-scoring/target/fraud-scoring-1.0.0.jar"
sleep 3
start_service validation-enrichment 8082 "validation-enrichment/target/validation-enrichment-1.0.0.jar"
sleep 3
start_service aml-compliance      8083 "aml-compliance/target/aml-compliance-1.0.0.jar"
sleep 3
start_service routing-execution   8084 "routing-execution/target/routing-execution-1.0.0.jar"
sleep 3
start_service settlement          8085 "settlement/target/settlement-1.0.0.jar"
sleep 3
start_service audit               8086 "audit/target/audit-1.0.0.jar"
sleep 3
start_service gateway             8080 "gateway/target/gateway-1.0.0.jar"
sleep 3
start_service mcp-readonly-gateway 8087 "mcp-readonly-gateway/target/mcp-readonly-gateway-1.0.0.jar"

# ── 4. Health check ────────────────────────────────────────
echo ""
echo "[4/4] Waiting 40s for services to start..."
sleep 40

echo ""
echo "======================================================"
echo "  Health Check"
echo "======================================================"
all_up=true
for entry in "gateway:8080" "fraud-scoring:8081" "validation-enrichment:8082" \
             "aml-compliance:8083" "routing-execution:8084" "settlement:8085" \
             "audit:8086" "mcp-readonly-gateway:8087"; do
  name=${entry%%:*}
  port=${entry##*:}
  code=$(curl -s -o /dev/null -w "%{http_code}" \
    "http://localhost:$port/actuator/health" --connect-timeout 3 2>/dev/null || echo "ERR")
  if [ "$code" = "200" ]; then
    echo "  ✓ $name (:$port)"
  else
    echo "  ✗ $name (:$port) → HTTP $code  (check $LOGS/$name.log)"
    all_up=false
  fi
done

echo ""
if $all_up; then
  echo "  All services UP. Run: python3 live_payment_sender.py"
else
  echo "  Some services failed. Check logs in: $LOGS/"
  echo "  Re-check: for p in 8080 8081 8082 8083 8084 8085 8086 8087; do curl -s localhost:\$p/actuator/health | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d[\"status\"])' 2>/dev/null || echo DOWN; done"
fi

echo ""
echo "  Logs : $LOGS/"
echo "  Stop : bash $BASE/stop_live_traffic.sh"
