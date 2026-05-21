import React, { useState, useEffect } from 'react';

const C = {
  bg: '#0d1117', surface: '#161b22', border: '#30363d',
  accent: '#58a6ff', muted: '#8b949e', text: '#e6edf3',
};

function GraphifyViewer() {
  const [graphHtml, setGraphHtml] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    const fetchGraph = async () => {
      try {
        const response = await fetch('/graphify-out/graph.html');
        if (response.ok) {
          const html = await response.text();
          setGraphHtml(html);
        } else {
          setError('Graph file not found. Try running /graphify on the codebase.');
        }
      } catch (err) {
        setError(`Could not load Graphify graph: ${err.message}`);
      } finally {
        setLoading(false);
      }
    };

    fetchGraph();
  }, []);

  if (loading) {
    return (
      <div style={{
        background: C.surface,
        border: `1px solid ${C.border}`,
        borderRadius: 8,
        padding: 20,
        textAlign: 'center',
        color: C.muted,
      }}>
        <div style={{ fontSize: 16, marginBottom: 10 }}>📊 Loading Graphify visualization...</div>
        <div style={{ fontSize: 12 }}>This shows the entire ClearFlow codebase as an interactive knowledge graph</div>
      </div>
    );
  }

  if (error) {
    return (
      <div style={{
        background: C.surface,
        border: `1px solid ${C.border}`,
        borderRadius: 8,
        padding: 20,
        textAlign: 'center',
        color: C.muted,
      }}>
        <div style={{ fontSize: 14, color: '#f85149', marginBottom: 10 }}>⚠️ {error}</div>
        <div style={{ fontSize: 12 }}>
          The Graphify graph visualization is available at: /graphify-out/graph.html
        </div>
      </div>
    );
  }

  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      gap: 16,
      height: 'calc(100vh - 200px)',
    }}>
      <div style={{
        background: C.surface,
        border: `1px solid ${C.border}`,
        borderRadius: 8,
        padding: 16,
      }}>
        <h2 style={{ fontSize: 16, fontWeight: 700, color: C.text, margin: 0 }}>
          📊 ClearFlow Codebase Architecture Graph
        </h2>
        <p style={{ fontSize: 12, color: C.muted, margin: '8px 0 0 0' }}>
          Interactive visualization of all services, components, and dependencies. Click nodes to explore.
        </p>
      </div>

      <div style={{
        flex: 1,
        background: C.surface,
        border: `1px solid ${C.border}`,
        borderRadius: 8,
        overflow: 'hidden',
      }}>
        <iframe
          title="Graphify Graph"
          srcDoc={graphHtml}
          style={{
            width: '100%',
            height: '100%',
            border: 'none',
            borderRadius: 8,
          }}
        />
      </div>
    </div>
  );
}

export default GraphifyViewer;
