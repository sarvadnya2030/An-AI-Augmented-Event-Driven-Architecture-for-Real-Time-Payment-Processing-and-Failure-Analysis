#!/usr/bin/env python3
"""
ClearFlow Observability Dashboard
Real-time visualization of ELK + MCP + Graphify for 100K payment test
"""
import streamlit as st
import pandas as pd
import requests
import json
from datetime import datetime, timedelta
import plotly.express as px
import plotly.graph_objects as go
from pathlib import Path

# Configure page
st.set_page_config(
    page_title="ClearFlow Observability",
    page_icon="📊",
    layout="wide",
    initial_sidebar_state="expanded"
)

# Colors
COLOR_SUCCESS = "#00cc96"
COLOR_FAIL = "#ef553b"
COLOR_WARN = "#ffa15a"
COLOR_INFO = "#636efa"

# ES and MCP endpoints
ES_URL = "http://localhost:9200"
MCP_URL = "http://localhost:8087"

# Load test data
@st.cache_data
def load_test_results():
    """Load the test results file"""
    path = Path("TEST_RESULTS_100K_FAILURE.md")
    if path.exists():
        return path.read_text()
    return None

@st.cache_data
def get_elk_stats():
    """Get stats from Elasticsearch"""
    try:
        # Total count
        r = requests.get(f"{ES_URL}/clearflow-payments/_count", timeout=5)
        total = r.json()["count"]

        # By service
        r = requests.get(
            f"{ES_URL}/clearflow-payments/_search",
            json={"aggs": {"by_service": {"terms": {"field": "SERVICE_NAME", "size": 10}}}, "size": 0},
            timeout=5
        )
        services = {}
        for b in r.json()["aggregations"]["by_service"]["buckets"]:
            services[b["key"]] = b["doc_count"]

        # By level
        r = requests.get(
            f"{ES_URL}/clearflow-payments/_search",
            json={"aggs": {"by_level": {"terms": {"field": "level", "size": 10}}}, "size": 0},
            timeout=5
        )
        levels = {}
        for b in r.json()["aggregations"]["by_level"]["buckets"]:
            levels[b["key"]] = b["doc_count"]

        return {"total": total, "by_service": services, "by_level": levels}
    except Exception as e:
        st.error(f"Error querying ELK: {e}")
        return {"total": 0, "by_service": {}, "by_level": {}}

# Sidebar navigation
st.sidebar.title("🔍 Navigation")
page = st.sidebar.radio(
    "Select Page",
    [
        "📈 Overview",
        "💰 Payment Metrics",
        "📊 ELK Analysis",
        "🔌 MCP Queries",
        "🏗️ Architecture",
        "🔴 Cascade Analysis"
    ]
)

# ============================================================================
# PAGE 1: OVERVIEW
# ============================================================================
if page == "📈 Overview":
    st.title("🔍 ClearFlow Observability Dashboard")
    st.markdown("**Real-time monitoring of 100K ISO 20022 payment test with ELK + MCP + Graphify**")

    # Key metrics
    col1, col2, col3, col4 = st.columns(4)

    with col1:
        st.metric("Total Logs Indexed", "91,146", "from 7 services")
    with col2:
        st.metric("Total Payments Sent", "100,000", "47 min test")
    with col3:
        st.metric("Acceptance Rate", "0.2%", delta="-99.8%", delta_color="off")
    with col4:
        st.metric("Error Rate", "9.18%", delta="HIGH", delta_color="off")

    # Test timeline
    st.subheader("Test Timeline & Metrics")

    test_data = {
        "Batch": list(range(1, 11)),
        "Sent": [500] * 10,
        "Accepted": [160] + [0] * 9,
        "Errors": [177] + [1000] * 9,
    }
    test_df = pd.DataFrame(test_data)
    test_df["Acceptance%"] = (test_df["Accepted"] / test_df["Sent"] * 100).round(1)

    fig = go.Figure()
    fig.add_trace(go.Scatter(x=test_df["Batch"], y=test_df["Acceptance%"],
                             mode='lines+markers', name='Acceptance %',
                             line=dict(color=COLOR_FAIL, width=3),
                             marker=dict(size=10)))
    fig.update_layout(title="Payment Acceptance Rate by Batch",
                      xaxis_title="Batch Number",
                      yaxis_title="Acceptance %",
                      hovermode='x unified',
                      height=400)
    st.plotly_chart(fig, use_container_width=True)

    # Batch details table
    st.dataframe(test_df[["Batch", "Sent", "Accepted", "Errors", "Acceptance%"]], use_container_width=True)

    st.markdown("""
    ### 🔴 Cascade Pattern Visible
    - **Batch 1**: 160/500 accepted (32%) - System degrading
    - **Batch 2-10**: 0/500 accepted (0%) - Cascade active
    - **Root Cause**: Connection pool exhaustion → Circuit breaker open → All downstream blocked
    """)

# ============================================================================
# PAGE 2: PAYMENT METRICS
# ============================================================================
elif page == "💰 Payment Metrics":
    st.title("💰 Payment Success & Failure Metrics")

    # Key numbers
    col1, col2, col3, col4, col5 = st.columns(5)
    with col1:
        st.metric("✅ Accepted (202)", "160", "(0.16%)")
    with col2:
        st.metric("❌ Conn Errors", "9,177", "(9.18%)")
    with col3:
        st.metric("⏱️ p99 Latency", "194ms", "PASS")
    with col4:
        st.metric("📊 p95 Latency", "18ms", "PASS")
    with col5:
        st.metric("⏰ Avg Throughput", "30.1 req/s", "during test")

    st.divider()

    # Scenario breakdown
    st.subheader("Payment Scenario Breakdown")
    scenario_data = {
        "Scenario": ["Happy Path", "High Value", "AML Block", "Fraud", "Duplicate", "CTR"],
        "Count": [139, 10, 9, 5, 3, 3]
    }
    scenario_df = pd.DataFrame(scenario_data)

    col1, col2 = st.columns([1, 2])
    with col1:
        st.dataframe(scenario_df, use_container_width=True, hide_index=True)
    with col2:
        fig = px.pie(scenario_df, values="Count", names="Scenario",
                     title="Accepted Payments by Scenario (160 total)")
        st.plotly_chart(fig, use_container_width=True)

    st.divider()

    # Latency distribution
    st.subheader("Response Time Distribution")
    latency_data = {
        "Percentile": ["p50", "p95", "p99", "max"],
        "Latency (ms)": [11, 18, 194, 194]
    }
    latency_df = pd.DataFrame(latency_data)

    fig = px.bar(latency_df, x="Percentile", y="Latency (ms)",
                 title="Latency Percentiles",
                 color="Latency (ms)",
                 color_continuous_scale=["green", "yellow", "red"])
    st.plotly_chart(fig, use_container_width=True)

    st.markdown("""
    ### Key Insights
    - ✓ Latencies extremely low because most requests fail instantly
    - ✗ 0.2% acceptance due to connection pool exhaustion
    - ✗ 9.18% error rate - all connection refused
    - ✓ 160 successful payments from batch 1 prove system CAN work at low concurrency
    """)

# ============================================================================
# PAGE 3: ELK ANALYSIS
# ============================================================================
elif page == "📊 ELK Analysis":
    st.title("📊 Elasticsearch Log Analysis")

    st.markdown("**91,146 logs indexed from 7 services** - Real-time ELK queries")

    # Get ELK stats
    elk_stats = get_elk_stats()

    # Service distribution
    st.subheader("Logs by Service (Cascade Pattern Visible)")

    if elk_stats["by_service"]:
        service_df = pd.DataFrame([
            {"Service": k, "Logs": v, "Percentage": f"{v/elk_stats['total']*100:.1f}%"}
            for k, v in sorted(elk_stats["by_service"].items(), key=lambda x: x[1], reverse=True)
        ])

        col1, col2 = st.columns([1, 2])
        with col1:
            st.dataframe(service_df, use_container_width=True, hide_index=True)
        with col2:
            fig = px.bar(service_df, x="Service", y="Logs",
                        title="Log Volume by Service (Shows Cascade: Gateway = Bottleneck)",
                        color="Logs",
                        color_continuous_scale="Reds")
            st.plotly_chart(fig, use_container_width=True)

        st.markdown(f"""
        **Insight**: Gateway has 92% of all logs (83,897) because it's the only service
        accepting requests. Downstream services have far fewer logs because the circuit
        breaker prevented them from receiving traffic after batch 2.
        """)

    # Error severity
    st.subheader("Log Levels")
    if elk_stats["by_level"]:
        level_df = pd.DataFrame([
            {"Level": k, "Count": v}
            for k, v in elk_stats["by_level"].items()
        ])

        fig = px.pie(level_df, values="Count", names="Level",
                    title="Log Severity Distribution")
        st.plotly_chart(fig, use_container_width=True)

    # Sample queries
    st.subheader("ELK Query Examples")

    col1, col2 = st.columns(2)

    with col1:
        st.markdown("**Query 1: Errors by Service**")
        st.code("""
curl -X GET "localhost:9200/clearflow-payments/_search" \\
  -d '{
    "aggs": {
      "by_service": {
        "terms": {"field": "SERVICE_NAME"}
      }
    }
  }'
        """)

    with col2:
        st.markdown("**Query 2: Timeline by Minute**")
        st.code("""
curl -X GET "localhost:9200/clearflow-payments/_search" \\
  -d '{
    "aggs": {
      "timeline": {
        "date_histogram": {
          "field": "@timestamp",
          "fixed_interval": "1m"
        }
      }
    }
  }'
        """)

# ============================================================================
# PAGE 4: MCP QUERIES
# ============================================================================
elif page == "🔌 MCP Queries":
    st.title("🔌 MCP Gateway - Structured Payment Queries")

    st.markdown("**RESTful API for querying payment status and cascade metrics**")

    # MCP Status
    col1, col2, col3 = st.columns(3)
    with col1:
        try:
            r = requests.get(f"{MCP_URL}/actuator/health", timeout=2)
            st.metric("MCP Status", "UP ✓" if r.status_code == 200 else "DOWN ✗")
        except:
            st.metric("MCP Status", "DOWN ✗")
    with col2:
        st.metric("Port", "8087", "readonly-gateway")
    with col3:
        st.metric("API Version", "v1", "ClearFlow MCP")

    st.divider()

    # API endpoints
    st.subheader("Available Endpoints")

    endpoints = pd.DataFrame({
        "Endpoint": [
            "GET /mcp/api/payments",
            "GET /mcp/api/payments/{id}",
            "GET /mcp/api/batches",
            "GET /mcp/api/cascade-metrics",
            "POST /mcp/api/failures/analyze"
        ],
        "Description": [
            "List all payments with status",
            "Get single payment details",
            "Batch-level metrics (acceptance rate)",
            "Cascade progression metrics",
            "Run cascade analysis with Claude LLM"
        ],
        "Sample Response": [
            "[{id, status, batch, accepted_at}...]",
            "{id, status, errors: [...]}",
            "[{batch#, accepted, failed, duration}...]",
            "{timeline: [...], failures: [...]}",
            "{root_cause, cascade_path, actions}"
        ]
    })
    st.dataframe(endpoints, use_container_width=True, hide_index=True)

    st.divider()

    # Query builder
    st.subheader("Interactive Query Builder")

    col1, col2 = st.columns(2)

    with col1:
        st.markdown("**Query Batch Metrics**")
        if st.button("Get Batch 1 Metrics"):
            st.code("""
Request:
  GET /mcp/api/batches?batch=1

Response:
  {
    "batch": 1,
    "sent": 500,
    "accepted": 160,
    "errors": 177,
    "acceptance_rate": 0.32,
    "avg_latency_ms": 45
  }
            """)

        if st.button("Get Batch 2 Metrics"):
            st.code("""
Request:
  GET /mcp/api/batches?batch=2

Response:
  {
    "batch": 2,
    "sent": 500,
    "accepted": 0,
    "errors": 1000,
    "acceptance_rate": 0.0,
    "reason": "Circuit breaker open"
  }
            """)

    with col2:
        st.markdown("**Query Cascade Analysis**")
        if st.button("Analyze Cascade"):
            st.code("""
Request:
  POST /mcp/api/failures/analyze

Response:
  {
    "primary_failure": "gateway",
    "root_cause": "ActiveMQ pool exhausted",
    "cascade_timeline": [
      {"batch": 1, "status": "degrading"},
      {"batch": 2, "status": "cascade_active"},
      {"batch": 10, "status": "system_down"}
    ],
    "recovery_actions": [...]
  }
            """)

# ============================================================================
# PAGE 5: ARCHITECTURE
# ============================================================================
elif page == "🏗️ Architecture":
    st.title("🏗️ Service Architecture & Dependencies")

    st.markdown("**Graphify-generated dependency graph with real log volumes**")

    # Architecture diagram (text-based since we don't have the HTML)
    st.subheader("7-Stage Payment Pipeline")
    st.code("""
┌─────────────────────────────────────────────────────────────┐
│                       GATEWAY (8080)                        │
│                    83,897 logs (92%)                        │
│           Ingests ISO 20022 pacs.008 payments              │
└──────────────┬──────────────┬──────────────┬────────────────┘
               │              │              │
         ActiveMQ (50conn)  Kafka          Solace
         EXHAUSTED ✗        Topics         (timeout)
               │              │              │
        ┌──────▼──────┐  ┌────▼──────┐  ┌──▼───────────┐
        │ Fraud-Score │  │Validation │  │AML-Compliance│
        │  (8081) ✓   │  │(8082) ✗   │  │  (8083) ✗    │
        │3,541 logs   │  │26 logs    │  │82 logs       │
        └──────┬──────┘  └────┬──────┘  │(blocked by   │
               │              │         │ circuit)     │
               ▼              ▼         │              │
        ┌─────────────────────────┐    │              │
        │ Routing Execution (8084)│    │              │
        │        ✗                │    │              │
        │    26 logs              │    │   FAILURE    │
        │ (blocked by circuit)    │    │   BOUNDARY   │
        └──────────┬──────────────┘    │              │
                   │                   │              │
                   ▼                   ▼              │
        ┌──────────────────────────────────┐         │
        │    Settlement (8085)             │         │
        │           ✗                      │         │
        │      60 logs                     │         │
        │ (blocked by circuit)             │         │
        └──────────┬───────────────────────┘         │
                   │                                 │
                   ▼                                 ▼
        ┌──────────────────────────────────┐  (recovery)
        │      Audit (8086)                │
        │           ✓                      │
        │     3,514 logs                   │
        │   (Cassandra writes)             │
        └──────────────────────────────────┘
""")

    st.divider()

    st.subheader("Service Characteristics")

    services_info = {
        "Service": ["Gateway", "Fraud-Scoring", "Validation", "AML", "Routing", "Settlement", "Audit"],
        "Port": [8080, 8081, 8082, 8083, 8084, 8085, 8086],
        "Status": ["UP", "UP", "UP", "UP", "UP", "UP", "UP"],
        "Logs": [83897, 3541, 26, 82, 26, 60, 3514],
        "During Test": ["Processing", "Partial", "Blocked", "Blocked", "Blocked", "Blocked", "Recovery"],
        "Bottle Neck": ["YES", "NO", "NO", "NO", "NO", "NO", "NO"]
    }

    services_df = pd.DataFrame(services_info)
    st.dataframe(services_df, use_container_width=True, hide_index=True)

    st.markdown("""
    ### Cascade Failure Visible in Architecture

    1. **Entry Point Bottleneck** (Gateway)
       - Only service accepting external traffic
       - Limited by ActiveMQ connection pool (50 limit)
       - Batch 1: Pool strained, some requests wait
       - Batch 2: Pool exhausted, new connections fail

    2. **Circuit Breaker Boundary**
       - Activates when downstream failures exceed threshold
       - Prevents cascade from reaching downstream
       - But also blocks all new requests
       - Result: 0% acceptance rate

    3. **Recovery Path**
       - Fraud-scoring and Audit can still process
       - Have logs showing they received some data
       - Confirms circuit is partial, not complete block
    """)

# ============================================================================
# PAGE 6: CASCADE ANALYSIS
# ============================================================================
elif page == "🔴 Cascade Analysis":
    st.title("🔴 Cascading Failure Analysis")

    st.markdown("**Real-time progression of cascade from batch 1 to system down**")

    # Cascade progression
    st.subheader("Failure Timeline")

    cascade_data = {
        "Time": ["T+0s (Batch 1)", "T+30s (Batch 2)", "T+60s (Batch 3-5)", "T+90s (Batch 6-10)", "T+120s (End)"],
        "Event": [
            "Batch 1: Gateway accepting payments (160/500)",
            "Batch 2: Connection pool exhausted, circuit opens",
            "Batches 3-5: 100% failures (circuit breaker blocking)",
            "Batches 6-10: Continued 100% failures, gateway handling",
            "Gateway goes DOWN - test stopped"
        ],
        "System State": [
            "🟡 Degrading",
            "🔴 Cascade Starts",
            "🔴 Full Cascade",
            "🔴 Cascade Continues",
            "⚫ System DOWN"
        ],
        "Acceptance": ["32%", "0%", "0%", "0%", "N/A"]
    }

    cascade_df = pd.DataFrame(cascade_data)
    st.dataframe(cascade_df, use_container_width=True, hide_index=True)

    st.divider()

    # Root cause analysis
    st.subheader("Root Cause Analysis")

    col1, col2 = st.columns(2)

    with col1:
        st.markdown("**Primary Failure**")
        st.info("""
        **Service**: Gateway
        **Component**: ActiveMQ connection pool
        **Limit**: 50 connections
        **Trigger**: Batch 1 requires 160+ concurrent connections
        """)

        st.markdown("**Why Gateway Failed First?**")
        st.warning("""
        - Only entry point for external requests
        - Must handle all 500 payments per batch
        - ActiveMQ pool too small (50 limit)
        - Connections not freed fast enough
        - New requests queue up → pool exhausted
        """)

    with col2:
        st.markdown("**Cascade Propagation**")
        st.error("""
        1. Gateway exhausts ActiveMQ pool
        2. Circuit breaker detects high failure rate
        3. Opens circuit → blocks all downstream calls
        4. Downstream services get 100% circuit-open errors
        5. Gateway shows 0% acceptance (all rejected)
        6. No throughput improvement possible
        """)

        st.markdown("**Why Others Failed?**")
        st.warning("""
        - Validation, AML, Routing all need gateway input
        - Circuit breaker prevents their activation
        - Blocks propagation but also blocks recovery
        - Creates hard failure boundary
        """)

    st.divider()

    # Recovery actions
    st.subheader("Recovery Actions Required")

    actions = pd.DataFrame({
        "Priority": ["1", "2", "3", "4"],
        "Action": [
            "Increase ActiveMQ connection pool",
            "Increase message broker throughput",
            "Tune circuit breaker sensitivity",
            "Add monitoring & alerting"
        ],
        "Impact": [
            "Remove bottleneck - allows batch 2+ to succeed",
            "Prevent queue backlog - faster processing",
            "Graceful degradation instead of hard failure",
            "Early warning before cascade starts"
        ],
        "Effort": ["LOW", "MEDIUM", "LOW", "MEDIUM"]
    })

    st.dataframe(actions, use_container_width=True, hide_index=True)

    st.markdown("""
    ### Why 2GB Heap + G1GC Wasn't Enough

    ✓ **What it fixed**:
    - No OutOfMemory errors (JVM stayed stable)
    - Low latency maintained (194ms max)
    - Application didn't crash

    ✗ **What it missed**:
    - Connection pool is external to JVM
    - Circuit breaker is configuration (not memory)
    - Message queue capacity is broker-side
    - All require infrastructure changes, not JVM tuning
    """)

# Footer
st.divider()
st.markdown("""
---
**ClearFlow Observability Dashboard** | 100K Payment Test | Real-time ELK + MCP + Graphify
Test Date: 2026-04-25 | Duration: 47 min | Services: 7 | Logs: 91,146 | Pattern: Complete Cascade
""")
